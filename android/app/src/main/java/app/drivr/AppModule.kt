package app.drivr

import android.content.Context
import com.stadiamaps.ferrostar.composeui.notification.DefaultForegroundNotificationBuilder
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider.Companion.toOkHttpClientProvider
import com.stadiamaps.ferrostar.core.location.AndroidLocationProvider
import com.stadiamaps.ferrostar.core.location.NavigationLocationProvider
import com.stadiamaps.ferrostar.core.location.SimulatedLocationProvider
import com.stadiamaps.ferrostar.core.location.toAndroidLocation
import com.stadiamaps.ferrostar.core.service.FerrostarForegroundServiceManager
import com.stadiamaps.ferrostar.core.withJsonOptions
import app.drivr.cameras.SpeedCameraRepository
import app.drivr.traffic.TrafficRepository
import java.time.Duration
import okhttp3.OkHttpClient
import uniffi.ferrostar.CourseFiltering
import uniffi.ferrostar.NavigationControllerConfig
import uniffi.ferrostar.RouteDeviationTracking
import uniffi.ferrostar.WaypointAdvanceMode
import uniffi.ferrostar.WellKnownRouteProvider
import uniffi.ferrostar.stepAdvanceDistanceEntryAndExit
import uniffi.ferrostar.stepAdvanceDistanceToEndOfStep

/**
 * Hand-rolled dependency wiring. Every service here is free and open:
 * - Location: Android's own LocationManager (no Google Play Services).
 * - Routing: Valhalla (FOSS), see BuildConfig.VALHALLA_URL.
 * - Map: MapLibre Native (FOSS) with Drivr's own style on OpenFreeMap tiles (style/ in the repo).
 * - Traffic: NDW open data (Dutch government, free, no key).
 * - Speed cameras: OpenStreetMap via the public Overpass API (free, no key, fair use).
 */
object AppModule {
  private lateinit var appContext: Context

  fun init(context: Context) {
    appContext = context.applicationContext
  }

  val locationProvider: NavigationLocationProvider by lazy {
    NavigationLocationProvider(
        liveProviding = AndroidLocationProvider(appContext),
        simulatedProvider =
            SimulatedLocationProvider(
                warpFactor = 2u,
                initialLocation = DemoRoute.origin.toAndroidLocation(),
            ),
    )
  }

  private val httpClient by lazy {
    OkHttpClient.Builder().callTimeout(Duration.ofSeconds(20)).build().toOkHttpClientProvider()
  }

  val ferrostarCore: FerrostarCore by lazy {
    val provider = WellKnownRouteProvider.Valhalla(BuildConfig.VALHALLA_URL, "auto")
    // Valhalla options: metric units, and ask for lane-level banner data (Ferrostar requests
    // OSRM-format output with banner instructions, which carry the per-lane indications).
    val options = mapOf("units" to "kilometers", "language" to "en-GB")
    FerrostarCore(
        provider.withJsonOptions(options),
        httpClient = httpClient,
        locationProvider = locationProvider,
        foregroundServiceManager =
            FerrostarForegroundServiceManager(
                appContext, DefaultForegroundNotificationBuilder(appContext)),
        navigationControllerConfig =
            NavigationControllerConfig(
                WaypointAdvanceMode.WaypointWithinRange(100.0),
                stepAdvanceDistanceEntryAndExit(30u, 5u, 32u),
                stepAdvanceDistanceToEndOfStep(10u, 32u),
                RouteDeviationTracking.StaticThreshold(15U, 50.0),
                CourseFiltering.SNAP_TO_ROUTE,
            ),
    )
  }

  /** Live Dutch traffic from NDW open data (free, no key). */
  val traffic: TrafficRepository by lazy {
    TrafficRepository(OkHttpClient.Builder().callTimeout(Duration.ofSeconds(30)).build())
  }

  /** Fixed speed cameras and section controls from OpenStreetMap (Overpass, free, no key). */
  val speedCameras: SpeedCameraRepository by lazy {
    SpeedCameraRepository(OkHttpClient.Builder().callTimeout(Duration.ofSeconds(40)).build())
  }

  val ttsObserver: AndroidTtsObserver by lazy { AndroidTtsObserver(appContext) }
}
