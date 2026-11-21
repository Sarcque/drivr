package app.drivr

import android.content.Context
import com.stadiamaps.ferrostar.composeui.notification.DefaultForegroundNotificationBuilder
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider.Companion.toOkHttpClientProvider
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
 * - Location: Android's own LocationManager (no Google Play Services), high accuracy, filtered,
 *   smoothed and bridged through tunnels by [app.drivr.location.DrivrLocationProvider].
 * - Routing: Valhalla (FOSS), see BuildConfig.VALHALLA_URL.
 * - Map: MapLibre Native (FOSS) with Drivr's own style on OpenFreeMap tiles (style/ in the repo).
 * - Traffic: NDW open data (Dutch government, free, no key).
 */
object AppModule {
  private lateinit var appContext: Context

  fun init(context: Context) {
    appContext = context.applicationContext
  }

  /** The phone's real position (high accuracy, filtered; see DrivrLocationProvider). */
  val liveLocation: app.drivr.location.DrivrLocationProvider by lazy {
    app.drivr.location.DrivrLocationProvider(appContext)
  }

  val locationProvider: NavigationLocationProvider by lazy {
    NavigationLocationProvider(
        liveProviding = liveLocation,
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
    // Turn instructions and the spoken prompts come back in the app language.
    val options = mapOf("units" to "kilometers", "language" to app.drivr.i18n.L10n.valhallaLanguage())
    settings.state // loads the avoid settings into RoutePreferences before the first request
    FerrostarCore(
        // Wrapped so every request, reroutes included, carries the avoid settings (tolls, ferries…).
        routeProvider =
            com.stadiamaps.ferrostar.core.RouteProvider.RouteAdapter(
                PreferencesRouteAdapter(uniffi.ferrostar.RouteAdapter.fromWellKnownRouteProvider(provider.withJsonOptions(options)))),
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

  /** The same Valhalla routing, asking for up to three alternatives (trip start and jam checks). */
  val alternativeRoutes: AlternativeRoutesClient by lazy {
    val provider =
        WellKnownRouteProvider.Valhalla(BuildConfig.VALHALLA_URL, "auto")
            .withJsonOptions(mapOf("units" to "kilometers", "language" to app.drivr.i18n.L10n.valhallaLanguage(), "alternates" to 3))
    AlternativeRoutesClient(
        OkHttpClient.Builder().callTimeout(Duration.ofSeconds(25)).build(),
        uniffi.ferrostar.RouteAdapter.fromWellKnownRouteProvider(provider),
    )
  }

  /** Live border-check reports from the Autobahn GmbH open traffic API (free, no key). */
  val borderReports: app.drivr.traffic.BorderReportsClient by lazy {
    app.drivr.traffic.BorderReportsClient(OkHttpClient.Builder().callTimeout(Duration.ofSeconds(6)).build())
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

  /** Home, work and recent destinations (on the phone only). */
  val savedPlaces: app.drivr.places.SavedPlacesStore by lazy { app.drivr.places.SavedPlacesStore(appContext) }

  val settings: app.drivr.settings.SettingsStore by lazy { app.drivr.settings.SettingsStore(appContext) }

  /** Now-playing and play/pause/skip for whatever music app is playing (needs notification access). */
  val music: app.drivr.music.MusicController by lazy { app.drivr.music.MusicController(appContext) }

  val ttsObserver: AndroidTtsObserver by lazy { AndroidTtsObserver(appContext) }

  /** "Hey Drivr": offline speech models and the microphone listener (only used when switched on). */
  val voice: app.drivr.voice.VoiceEngine by lazy {
    app.drivr.voice.VoiceEngine(appContext, OkHttpClient.Builder().readTimeout(Duration.ofSeconds(60)).build())
  }

  /** The navigation voice's engine, so voice commands can tell when Drivr itself is talking. */
  @Volatile var tts: android.speech.tts.TextToSpeech? = null
  @Volatile private var lastSpokeMs = 0L

  /** True while Drivr is speaking and briefly after, so the recogniser doesn't hear Drivr's own voice. */
  fun drivrIsSpeaking(): Boolean {
    val now = android.os.SystemClock.elapsedRealtime()
    if (tts?.isSpeaking == true) lastSpokeMs = now
    return now - lastSpokeMs < 700
  }
}
