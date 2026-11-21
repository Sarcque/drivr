package app.drivr.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The phone's position for driving: Android's own location services (no Google Play Services),
 * asked for the best accuracy once a second, then cleaned up by [FixFilter] and bridged through
 * tunnels by [TunnelBridge].
 *
 * Replaces Ferrostar's AndroidLocationProvider, which asked the fused provider with the default
 * "balanced power" quality (Wi-Fi/cell-tower accuracy on many phones, tens of metres off while
 * driving), passed every fix through unfiltered, and dropped the speed on phones that don't report
 * a speed accuracy.
 *
 * One shared subscription: the navigation core and the rest of the app see the same fixes.
 */
class DrivrLocationProvider(context: Context) : NavigationLocationProviding {
  private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
  private val mainExecutor = androidx.core.content.ContextCompat.getMainExecutor(context)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val filter = FixFilter()
  private val bridge = TunnelBridge()
  @Volatile private var lastOut: Location? = null

  /** The route being driven, so GPS gaps (tunnels) are bridged along it. Null when not navigating. */
  fun followRoute(points: List<Pair<Double, Double>>?, key: Any?) {
    scope.launch { bridge.setRoute(points, key) }
  }

  @SuppressLint("MissingPermission")
  override suspend fun lastLocation(): Location? =
      lastOut ?: runCatching { freshLastKnown() }.getOrNull()

  private val shared: Flow<Location> =
      callbackFlow {
            // Long gap since the last session (app was in the background): start fresh.
            val prev = filter.last
            if (prev != null && SystemClock.elapsedRealtime() - prev.timeMs > 60_000) filter.reset()

            fun handle(raw: Fix, wallTimeMs: Long) {
              // Predicted (tunnel) fixes skip the filter: it only ever learns from measurements,
              // so a real fix after the tunnel is never mistaken for an out-of-order one.
              val out = if (raw.predicted) raw else filter.accept(raw)?.also(bridge::onRealFix) ?: return
              val loc = out.toLocation(wallTimeMs)
              lastOut = loc
              trySend(loc)
            }

            val listener = LocationListener { l -> l.toFix()?.let { handle(it, l.time) } }
            val providers = mutableListOf<String>()
            runCatching {
                  freshLastKnown()?.let { l -> l.toFix()?.let { handle(it, l.time) } }
                  val all = lm.allProviders.toSet()
                  val main =
                      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManager.FUSED_PROVIDER in all &&
                          lm.isProviderEnabled(LocationManager.FUSED_PROVIDER))
                          LocationManager.FUSED_PROVIDER
                      else LocationManager.GPS_PROVIDER
                  request(main, 1000L, listener)
                  providers += main
                  // Plain GPS can take a while for its first fix (cold start, indoors): Wi-Fi/cell
                  // gives a rough one meanwhile; the filter ignores those once GPS is good.
                  if (main == LocationManager.GPS_PROVIDER && LocationManager.NETWORK_PROVIDER in all) {
                    request(LocationManager.NETWORK_PROVIDER, 4000L, listener)
                    providers += LocationManager.NETWORK_PROVIDER
                  }
                  Log.d(TAG, "Location from $providers")
                }
                .onFailure { Log.w(TAG, "No location updates", it) }

            // Tunnel: no fixes for a few seconds while driving a route → carry on along it.
            val ticker = launch {
              while (isActive) {
                delay(1000)
                bridge.predict(SystemClock.elapsedRealtime())?.let { handle(it, System.currentTimeMillis()) }
              }
            }

            awaitClose {
              ticker.cancel()
              runCatching { lm.removeUpdates(listener) }
            }
          }
          .shareIn(scope, SharingStarted.WhileSubscribed(3000), replay = 1)

  /** Every caller gets the same shared, cleaned-up fixes (about once a second). */
  override fun locationUpdates(intervalMillis: Long): Flow<Location> = shared

  @SuppressLint("MissingPermission")
  private fun request(provider: String, intervalMs: Long, listener: LocationListener) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val req =
          LocationRequest.Builder(intervalMs)
              .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
              .setMinUpdateIntervalMillis(intervalMs / 2)
              .setMinUpdateDistanceMeters(0f)
              .build()
      lm.requestLocationUpdates(provider, req, mainExecutor, listener)
    } else {
      lm.requestLocationUpdates(provider, intervalMs, 0f, listener, Looper.getMainLooper())
    }
  }

  /** The system's last known position, only when it's recent enough to be where the car is. */
  @SuppressLint("MissingPermission")
  private fun freshLastKnown(): Location? =
      listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
          .filter { it in lm.allProviders }
          .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
          .filter { SystemClock.elapsedRealtime() - it.elapsedRealtimeNanos / 1_000_000 < 60_000 }
          .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }

  private companion object {
    const val TAG = "DrivrLocation"
  }
}

internal fun Location.toFix(): Fix? {
  if (!latitude.isFinite() || !longitude.isFinite()) return null
  return Fix(
      lat = latitude,
      lon = longitude,
      accuracyM = if (hasAccuracy()) accuracy.toDouble() else 50.0,
      speedMps = if (hasSpeed()) speed.toDouble() else null,
      bearingDeg = if (hasBearing() && hasSpeed() && speed > 0.5f) bearing.toDouble() else null,
      // The monotonic clock: GPS / network time can jump, this can't.
      timeMs = elapsedRealtimeNanos / 1_000_000,
  )
}

/** Back to an Android Location, with speed and course always marked as known when we have them. */
internal fun Fix.toLocation(wallTimeMs: Long): Location =
    Location(if (predicted) "drivr-predicted" else "drivr").apply {
      latitude = lat
      longitude = lon
      accuracy = accuracyM.toFloat()
      time = wallTimeMs
      elapsedRealtimeNanos = timeMs * 1_000_000
      speedMps?.let {
        speed = it.toFloat()
        // Ferrostar only reads the speed when an accuracy comes with it.
        speedAccuracyMetersPerSecond = 1.0f
      }
      bearingDeg?.let {
        bearing = normDeg(it).toFloat()
        bearingAccuracyDegrees = 10f
      }
    }
