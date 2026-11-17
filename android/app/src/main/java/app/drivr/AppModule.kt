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
import app.drivr.cameras.SpeedCameraClient
import app.drivr.places.FranceFuelPrices
import app.drivr.places.FuelPriceRepository
import app.drivr.places.OverpassClient
import app.drivr.places.PhotonClient
import app.drivr.places.PlacesRepository
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

  /** Fixed speed cameras and average-speed sections from OSM, along the route. */
  val speedCameras: SpeedCameraClient by lazy {
    // Other mirror order and one server at a time, so these long background queries leave the
    // main server free for the driver's own searches.
    SpeedCameraClient(
        OverpassClient(
            OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(45))
                .addInterceptor { chain ->
                  chain.proceed(chain.request().newBuilder().header("User-Agent", PhotonClient.USER_AGENT).build())
                }
                .build(),
            endpoints = OverpassClient.BACKGROUND_ENDPOINTS,
            parallel = 1,
            staggerMs = 50_000,
        ))
  }

  /** Places: OSM via Overpass, typed search via Photon, official fuel prices where published. */
  val places: PlacesRepository by lazy {
    val http =
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(4))
            .readTimeout(Duration.ofSeconds(9))
            .callTimeout(Duration.ofSeconds(10))
            .addInterceptor { chain ->
              // OSM services ask clients to identify themselves.
              chain.proceed(chain.request().newBuilder().header("User-Agent", PhotonClient.USER_AGENT).build())
            }
            .build()
    PlacesRepository(
        OverpassClient(http),
        PhotonClient(http),
        FuelPriceRepository(listOf(FranceFuelPrices(http))),
    )
  }

  val settings: app.drivr.settings.SettingsStore by lazy { app.drivr.settings.SettingsStore(appContext) }

  val ttsObserver: AndroidTtsObserver by lazy { AndroidTtsObserver(appContext) }
}
