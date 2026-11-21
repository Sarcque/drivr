package app.drivr

import app.drivr.i18n.L10n
import android.util.Log
import app.drivr.traffic.LatLon
import app.drivr.traffic.RouteTraffic
import app.drivr.traffic.TrafficAhead
import app.drivr.traffic.TrafficEvent
import app.drivr.traffic.TrafficRepository
import app.drivr.traffic.RouteChoice
import app.drivr.traffic.BorderChecks
import app.drivr.traffic.ScoredRoute
import app.drivr.cameras.CameraAhead
import app.drivr.cameras.SectionTracker
import app.drivr.cameras.SpeedCamera
import app.drivr.cameras.SpeedCameraClient
import app.drivr.places.LatLng
import app.drivr.places.Place
import app.drivr.places.PlaceCategory
import app.drivr.places.PlaceResult
import app.drivr.places.PlacesUpdate
import app.drivr.places.PlacesRepository
import app.drivr.ui.remainingGeometry
import kotlinx.coroutines.Job
import androidx.lifecycle.viewModelScope
import com.stadiamaps.ferrostar.core.DefaultNavigationViewModel
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.NavigationUiState
import com.stadiamaps.ferrostar.core.annotation.valhalla.valhallaExtendedOSRMAnnotationPublisher
import com.stadiamaps.ferrostar.core.location.NavigationLocationProvider
import com.stadiamaps.ferrostar.core.location.toUserLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind

sealed interface RouteStatus {
  data object Idle : RouteStatus

  data class Loading(val message: String = L10n.s(R.string.route_finding)) : RouteStatus

  data class Failed(val message: String, val canRetry: Boolean = true) : RouteStatus
}

/** Ways to the destination at the start of a trip, quickest (with live traffic) first. */
data class RouteChoiceState(
    val options: List<RouteOption>,
    /** The one being driven; the others are drawn grey. */
    val selected: Int = 0,
)

/** A quicker way found while driving, because of traffic on the current route. Never auto-applied. */
data class FasterRouteState(
    val option: RouteOption,
    val savesSeconds: Double,
    /** Expected delay on the current route ahead (traffic and closures). */
    val currentDelaySeconds: Double,
)

/** The places panel: what was asked, what came back, and which place is open. */
data class PlacesState(
    val open: Boolean = false,
    val query: String = "",
    val category: PlaceCategory? = null,
    val alongRoute: Boolean = false,
    val loading: Boolean = false,
    /** First results are shown; more (along the route) are still coming. */
    val refining: Boolean = false,
    /** A soft notice, e.g. the route part timed out but nearby results are shown. */
    val note: String? = null,
    val results: List<PlaceResult> = emptyList(),
    val error: String? = null,
    val selected: PlaceResult? = null,
    /** Exact extra time for the selected place, from Valhalla (route via the stop vs. without). */
    val exactDetourS: Double? = null,
    /** Country with an official fuel price feed here, or null (no open prices, e.g. NL). */
    val priceCountry: String? = null,
    /** Stops added to the current trip, in order. */
    val stops: List<Place> = emptyList(),
)

/** Where a planned route starts: the phone's own location, or a place the driver picked. */
sealed interface RouteStart {
  data object Here : RouteStart

  data class At(val place: Place) : RouteStart
}

/** A field of the route planner, or a saved place being set. */
/** Voice commands that need the screen (an Activity) to finish. */
enum class VoiceEvent { ShareEta, AskEndTrip, CloseEndTrip }

enum class PlanField { FROM, TO, HOME, WORK }

/**
 * The route planner: from (your location or any address), to, and the routes between them, shown
 * on the map before anything starts. From another address the trip can't be driven for real, so
 * it plays as a simulated preview drive instead.
 */
data class PlanState(
    val open: Boolean = false,
    val from: RouteStart = RouteStart.Here,
    val to: Place? = null,
    /** The field being typed into (the suggestion list shows), or null. */
    val editing: PlanField? = null,
    val query: String = "",
    val results: List<PlaceResult> = emptyList(),
    val searching: Boolean = false,
    val searchError: String? = null,
    /** Waiting for a long-press on the map to set this field. */
    val pickingOnMap: PlanField? = null,
    /** Routes from [from] to [to], quickest (with live traffic) first. */
    val options: List<RouteOption> = emptyList(),
    val selected: Int = 0,
) {
  val fromHere: Boolean get() = from == RouteStart.Here
}

@OptIn(ExperimentalCoroutinesApi::class)
class DrivrViewModel(
    private val core: FerrostarCore = AppModule.ferrostarCore,
    private val locationProvider: NavigationLocationProvider = AppModule.locationProvider,
    private val liveLocation: app.drivr.location.DrivrLocationProvider? = AppModule.liveLocation,
    private val traffic: TrafficRepository = AppModule.traffic,
    private val placesRepo: PlacesRepository = AppModule.places,
    private val cameraClient: SpeedCameraClient = AppModule.speedCameras,
    private val settingsStore: app.drivr.settings.SettingsStore = AppModule.settings,
    private val alternatives: AlternativeRoutesClient = AppModule.alternativeRoutes,
    private val borderReports: app.drivr.traffic.BorderReportsClient = AppModule.borderReports,
    private val savedStore: app.drivr.places.SavedPlacesStore = AppModule.savedPlaces,
) : DefaultNavigationViewModel(core, valhallaExtendedOSRMAnnotationPublisher()) {

  private val hasLocationPermission = MutableStateFlow(false)
  private val browsingLocation = MutableStateFlow<UserLocation?>(DemoRoute.origin)
  /** The phone's own GPS fix; null until the first one arrives (the map shows the demo spot). */
  private val liveFix = MutableStateFlow<UserLocation?>(null)
  /** The last route request, so a failed one can be retried with one tap. */
  private var lastRequest: (() -> Unit)? = null

  private val _routeStatus = MutableStateFlow<RouteStatus>(RouteStatus.Idle)
  val routeStatus = _routeStatus.asStateFlow()

  // While not navigating, show the live (or demo) location so the map has somewhere to sit.
  override val navigationUiState: StateFlow<NavigationUiState> =
      combine(super.navigationUiState, browsingLocation) { ui, loc ->
            if (ui.isNavigating()) ui else ui.copy(location = loc)
          }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), NavigationUiState.empty())

  /**
   * Wrong way / off route, from the raw GPS fix (Ferrostar's own location is snapped to the route,
   * course included, so it can't see a car driving against the route).
   */
  private val routeGuard = app.drivr.nav.RouteGuard()
  private val _routeStanding = MutableStateFlow<app.drivr.nav.RouteStanding>(app.drivr.nav.RouteStanding.OnRoute)
  val routeStanding: StateFlow<app.drivr.nav.RouteStanding> = _routeStanding.asStateFlow()

  private fun checkRouteStanding(fix: UserLocation) {
    val ui = super.navigationUiState.value
    val geometry = ui.routeGeometry
    if (!ui.isNavigating() || geometry == null || geometry.size < 2) {
      routeGuard.reset()
      _routeStanding.value = app.drivr.nav.RouteStanding.OnRoute
      return
    }
    val before = _routeStanding.value
    val now =
        routeGuard.update(
            app.drivr.nav.RawFix(
                position = app.drivr.ar.LatLng(fix.coordinates.lat, fix.coordinates.lng),
                courseDeg = fix.courseOverGround?.degrees?.toDouble(),
                speedMps = fix.speed?.value ?: 0.0,
                accuracyM = fix.horizontalAccuracy,
                timeMs = fix.timestamp.toEpochMilli(),
            ),
            geometry.map { app.drivr.ar.LatLng(it.lat, it.lng) },
        )
    _routeStanding.value = now
    if (now is app.drivr.nav.RouteStanding.WrongWay && before !is app.drivr.nav.RouteStanding.WrongWay) {
      speak(L10n.s(R.string.voice_wrong_way))
    }
  }

  /** Says [text] through the navigation voice (which honours the voice setting). */
  private fun speak(text: String) {
    runCatching {
          core.spokenInstructionObserver?.onSpokenInstructionTrigger(
              uniffi.ferrostar.SpokenInstruction(text, null, 0.0, java.util.UUID.randomUUID()))
        }
        .onFailure { Log.w(TAG, "Could not speak", it) }
  }

  /** Live traffic events (NDW), and the ones on the route ahead, nearest first. */
  val trafficEvents: StateFlow<List<TrafficEvent>> = traffic.events

  val trafficAhead: StateFlow<List<TrafficAhead>> =
      combine(super.navigationUiState, traffic.events) { ui, events ->
            val route = if (ui.isNavigating()) remainingGeometry(ui) else null
            if (route == null || events.isEmpty()) emptyList()
            else RouteTraffic.ahead(route.map { LatLon(it.lat, it.lng) }, events)
          }
          .flowOn(Dispatchers.Default)
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val settings: StateFlow<app.drivr.settings.Settings> = settingsStore.state

  private val _routeChoice = MutableStateFlow<RouteChoiceState?>(null)
  /** The route chooser shown at the start of a trip when there is more than one sensible way. */
  val routeChoice = _routeChoice.asStateFlow()
  private var choiceTimeout: Job? = null

  private val _fasterRoute = MutableStateFlow<FasterRouteState?>(null)
  /** A faster way around traffic ahead, waiting for the driver to switch or keep the route. */
  val fasterRoute = _fasterRoute.asStateFlow()
  private var fasterRouteJob: Job? = null
  private var fasterRouteTimeout: Job? = null
  private var lastFasterRouteCheckMs = 0L

  fun updateSettings(change: (app.drivr.settings.Settings) -> app.drivr.settings.Settings) {
    settingsStore.update(change)
    applyVoice()
  }

  private fun applyVoice() {
    core.spokenInstructionObserver?.setMuted(!settingsStore.state.value.voice)
  }

  /** Fixed speed cameras and average-speed sections on the current route (OSM). */
  private val cameras = MutableStateFlow<List<SpeedCamera>>(emptyList())
  val cameraList: StateFlow<List<SpeedCamera>> = cameras.asStateFlow()
  private val sectionTracker = SectionTracker()

  /** Cameras on the route ahead, nearest first, and the average speed inside a section. */
  val camerasAhead: StateFlow<Pair<List<CameraAhead>, Double?>> =
      combine(super.navigationUiState, cameras, settingsStore.state) { ui, cams, st ->
            val route = if (ui.isNavigating() && cams.isNotEmpty() && st.speedCameras) remainingGeometry(ui) else null
            val ahead =
                if (route == null) emptyList()
                else SpeedCameraClient.ahead(route.map { LatLng(it.lat, it.lng) }, cams)
            ahead to sectionTracker.update(ahead.firstOrNull { it.inside }, System.currentTimeMillis())
          }
          .flowOn(Dispatchers.Default)
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<CameraAhead>() to null)

  private val _places = MutableStateFlow(PlacesState())
  val places = _places.asStateFlow()
  private var placesJob: Job? = null

  /** The trip's final destination, so stops can be inserted before it. */
  private var destination: Waypoint? = null
  private var destinationName: String? = null
  /** The destination's name for the current trip, if it has one. */
  val destinationLabel: String? get() = destinationName
  private var simulating = false

  init {
    traffic.start(viewModelScope)
    watchRouteForCameras()
    viewModelScope.launch { trafficAhead.collect(::maybeLookForFasterRoute) }
    // GPS lost while driving a route (tunnel): the location provider carries on along it.
    viewModelScope.launch {
      super.navigationUiState
          .map { ui -> ui.routeGeometry?.takeIf { ui.isNavigating() } }
          .distinctUntilChanged { a, b -> a === b }
          .collect { geometry -> liveLocation?.followRoute(geometry?.map { it.lat to it.lng }, geometry) }
    }
    viewModelScope.launch {
      hasLocationPermission
          .flatMapLatest { granted ->
            if (granted) locationProvider.locationUpdates(1000L).map { it.toUserLocation() }
            else flowOf(DemoRoute.origin)
          }
          .collect {
            browsingLocation.value = it
            if (hasLocationPermission.value) liveFix.value = it
            checkRouteStanding(it)
          }
    }
  }

  private fun watchRouteForCameras() {
    viewModelScope.launch(Dispatchers.IO) {
      super.navigationUiState
          .map { it.routeGeometry }
          .distinctUntilChanged()
          .collectLatest { geometry ->
            cameras.value = emptyList()
            if (geometry == null || geometry.size < 2) return@collectLatest
            runCatching { cameraClient.alongRoute(geometry.map { LatLng(it.lat, it.lng) }) }
                .onSuccess { cameras.value = it }
                .onFailure { Log.w(TAG, "Speed cameras unavailable", it) }
          }
    }
  }

  fun onLocationPermission(granted: Boolean) {
    hasLocationPermission.value = granted
  }

  /** Simulated drive along the demo route; works without GPS or permissions. */
  fun startDemoDrive() {
    lastRequest = ::startDemoDrive
    navigate(
        from = DemoRoute.origin,
        to = DemoRoute.destination,
        name = DemoRoute.destinationName,
        simulate = true,
    )
  }


  /**
   * Routes from where the phone really is. Waits (up to 20 s) for the first GPS fix instead of
   * routing from the demo start in Utrecht, which used to give a route far away from the car.
   */
  private fun routeFromHere(to: GeographicCoordinate, name: String?, stops: List<Place> = emptyList(), simulate: Boolean = false) {
    lastRequest = { routeFromHere(to, name, stops, simulate) }
    viewModelScope.launch {
      val from =
          if (simulate || !hasLocationPermission.value) browsingLocation.value
          else
              liveFix.value
                  ?: run {
                    _routeStatus.value = RouteStatus.Loading(L10n.s(R.string.route_waiting_gps))
                    withTimeoutOrNull(20_000) { liveFix.filterNotNull().first() }
                  }
      if (from == null) {
        _routeStatus.value = RouteStatus.Failed(L10n.s(R.string.route_no_gps))
        return@launch
      }
      navigate(from, to, name, simulate, stops)
    }
  }

  private fun navigate(
      from: UserLocation,
      to: GeographicCoordinate,
      name: String?,
      simulate: Boolean,
      stops: List<Place> = emptyList(),
  ) {
    _routeStatus.value = RouteStatus.Loading()
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val dest = Waypoint(coordinate = to, kind = WaypointKind.BREAK)
        val waypoints = stops.map { it.waypoint() } + dest
        // Ask for alternatives too. If that fails, fall back to the plain request, with one quiet
        // retry: the free routing server sometimes drops a request.
        val routes =
            runCatching { alternatives.routes(from, waypoints) }
                .recoverCatching {
                  if (it is kotlinx.coroutines.CancellationException) throw it
                  Log.w(TAG, "Routing with alternatives failed, trying the plain request", it)
                  core.getRoutes(from, waypoints)
                }
                .recoverCatching {
                  if (it is kotlinx.coroutines.CancellationException) throw it
                  Log.w(TAG, "Routing failed once, retrying", it)
                  kotlinx.coroutines.delay(1500)
                  core.getRoutes(from, waypoints)
                }
                .getOrThrow()
                .filter { it.geometry.size >= 2 }
        if (routes.isEmpty()) error(L10n.s(R.string.route_none))
        // Valhalla's times have no live traffic: rank with the NDW jams on each route. Going into
        // Germany over a checkpoint, also ask for a way around it and for live check reports.
        val ranked = withBorderChecks(from, waypoints, routes)
        beginTrip(ranked, dest, name, simulate, stops)
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Routing failed", e)
        _routeStatus.value = RouteStatus.Failed(routingError(e))
      }
    }
  }

  /** Starts driving the first of [ranked]; the others are offered in the route choice card. */
  private fun beginTrip(ranked: List<RouteOption>, dest: Waypoint, name: String?, simulate: Boolean, stops: List<Place>) {
    val route = ranked.first().route
    Log.i(TAG, "Route: ${route.geometry.size} points, ${route.distance.toInt()} m, ${ranked.size} option(s)")
    clearFasterRoute()
    if (simulate) locationProvider.enableSimulationOn(route)
    else locationProvider.disableSimulation()
    core.startNavigation(route = route)
    applyVoice()
    destination = dest
    destinationName = name
    simulating = simulate
    _places.value = _places.value.copy(stops = stops)
    _routeStatus.value = RouteStatus.Idle
    showRouteChoice(ranked)
    _routeStarted.tryEmit(Unit)
  }

  // ---- Route choice: alternatives at the start, and a faster way around jams while driving -----

  /**
   * Ranks [routes]. When one enters Germany over a checkpoint (and the setting is on), or the driver
   * wants to avoid reported checks, fetches live reports (Autobahn GmbH; NDW is already in the
   * traffic) and asks for the best route around the checkpoints / reported checks. Both extra calls
   * are short-timed and optional: without them the plain ranking stands. With "always avoid", the
   * route around comes first (and is driven), even when it is slower.
   */
  private suspend fun withBorderChecks(
      from: UserLocation,
      waypoints: List<Waypoint>,
      routes: List<uniffi.ferrostar.Route>,
  ): List<RouteOption> {
    val st = settingsStore.state.value
    val events = traffic.events.value
    val geoms = routes.map { r -> r.geometry.map { LatLon(it.lat, it.lng) } }
    val checks =
        if (st.borderChecks || st.alwaysAvoidBorderChecks) geoms.mapNotNull { BorderChecks.checkOn(it)?.crossing }.distinct()
        else emptyList()
    val roads =
        if (st.alwaysAvoidChecks)
            BorderChecks.motorways(routes.flatMap { r -> r.steps.flatMap { listOf(it.roadName, RouteChoice.stepRoad(it.roadName, it.instruction, 0.0).name) } })
        else emptyList()
    if (checks.isEmpty() && roads.isEmpty()) return rankRoutes(routes, events)
    _routeStatus.value = RouteStatus.Loading(L10n.s(R.string.route_checking_border))
    val live = withTimeoutOrNull(BORDER_REPORTS_TIMEOUT_MS) { borderReports.reports(checks, roads) }
    val controls = live?.controls.orEmpty()
    val controlsToAvoid = if (st.alwaysAvoidChecks) geoms.flatMap { BorderChecks.controlsOn(it, controls) }.distinct() else emptyList()
    val polygons = BorderChecks.avoidFor(checks, controlsToAvoid.map { it.at })
    val around =
        if (polygons.length() == 0) null
        else
            withTimeoutOrNull(BORDER_ROUTE_TIMEOUT_MS) {
              runCatching { alternatives.routes(from, waypoints, avoid = polygons) }
                  .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.w(TAG, "No route around the checks", it) }
                  .getOrNull()
                  ?.firstOrNull { it.geometry.size >= 2 }
            }
    val all = routes + listOfNotNull(around)
    val avoiding = routes.map { null } + listOfNotNull(around?.let { checks.firstOrNull() })
    val avoidingControls = routes.map { 0 } + listOfNotNull(around?.let { controlsToAvoid.size })
    Log.i(TAG, "Checks: border ${checks.joinToString { it.label }}, ${controlsToAvoid.size} reported on route; around: ${around != null}; live: ${live != null}")
    val ranked =
        rankRoutes(all, events, BorderContext(live?.border, avoiding, controls = controls, avoidingControls = avoidingControls))
    val forceAround = (st.alwaysAvoidBorderChecks && checks.isNotEmpty()) || (st.alwaysAvoidChecks && controlsToAvoid.isNotEmpty())
    val aroundOption = ranked.firstOrNull { it.route === around }
    return if (forceAround && aroundOption != null) listOf(aroundOption) + (ranked - aroundOption) else ranked
  }

  private fun showRouteChoice(ranked: List<RouteOption>) {
    choiceTimeout?.cancel()
    if (ranked.size < 2) {
      _routeChoice.value = null
      return
    }
    // Up to three ways, plus the way around a border check whenever there is one.
    val shown = ranked.take(3).let { top ->
      val around = ranked.firstOrNull { it.score.border?.let { b -> b.avoids != null || b.avoidsControls > 0 } == true }
      if (around == null || around in top) top else top + around
    }
    _routeChoice.value = RouteChoiceState(shown)
    // Gone by itself after a while, so the drive starts calm even if nobody taps it.
    choiceTimeout = viewModelScope.launch {
      kotlinx.coroutines.delay(CHOICE_VISIBLE_MS)
      _routeChoice.value = null
    }
  }

  /** Drive another of the routes offered at the start. */
  fun chooseRoute(index: Int) {
    val choice = _routeChoice.value ?: return
    val option = choice.options.getOrNull(index) ?: return
    if (index == choice.selected) return
    _routeChoice.value = choice.copy(selected = index)
    // Picking one restarts the countdown: they are looking at it.
    showRouteChoiceTimeoutAgain()
    if (simulating) locationProvider.enableSimulationOn(option.route)
    core.startNavigation(route = option.route)
    applyVoice()
  }

  private fun showRouteChoiceTimeoutAgain() {
    choiceTimeout?.cancel()
    choiceTimeout = viewModelScope.launch {
      kotlinx.coroutines.delay(CHOICE_VISIBLE_MS)
      _routeChoice.value = null
    }
  }

  /** Close the chooser and drive the selected route. */
  fun confirmRouteChoice() {
    choiceTimeout?.cancel()
    _routeChoice.value = null
  }

  /**
   * When a jam (or closure) ahead costs real time, asks Valhalla once for other ways from here and
   * offers the quickest if it saves enough. At most one check every few minutes; never switches
   * by itself.
   */
  private fun maybeLookForFasterRoute(ahead: List<TrafficAhead>) {
    if (!settingsStore.state.value.fasterRoutes) return
    if (ahead.isEmpty() || _fasterRoute.value != null || _routeChoice.value != null) return
    if (fasterRouteJob?.isActive == true) return
    val now = System.currentTimeMillis()
    if (now - lastFasterRouteCheckMs < FASTER_ROUTE_CHECK_EVERY_MS) return
    val near = ahead.filter { it.distanceM < LOOK_AHEAD_M }
    val delay = near.filter { it.event.kind != app.drivr.traffic.TrafficKind.ROAD_CLOSED }.sumOf { RouteChoice.delayOf(it) }
    val closed = near.any { it.event.kind == app.drivr.traffic.TrafficKind.ROAD_CLOSED && it.endM != null }
    if (delay < RouteChoice.MIN_SAVING_S && !closed) return
    lastFasterRouteCheckMs = now
    fasterRouteJob = viewModelScope.launch(Dispatchers.Default) { lookForFasterRoute() }
  }

  private suspend fun lookForFasterRoute() {
    val ui = super.navigationUiState.value
    if (!ui.isNavigating()) return
    val from = ui.location ?: return
    val progress = ui.progress ?: return
    val waypoints =
        (core.state.value.tripState as? uniffi.ferrostar.TripState.Navigating)?.remainingWaypoints?.takeIf { it.isNotEmpty() }
            ?: destination?.let { listOf(it) }
            ?: return
    val routes =
        try {
          alternatives.routes(from, waypoints).filter { it.geometry.size >= 2 }
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Looking for a faster route failed", e)
          return
        }
    val events = traffic.events.value
    val currentGeometry = remainingGeometry(ui)?.map { LatLon(it.lat, it.lng) } ?: return
    val (delay, closures) = RouteChoice.traffic(currentGeometry, events)
    val current = ScoredRoute(progress.durationRemaining, progress.distanceRemaining, delay, closures)
    val ranked = rankRoutes(routes, events)
    val best = RouteChoice.betterThan(current, currentGeometry, ranked.map { it to it.score }) { it.geometry } ?: run {
      Log.i(TAG, "No faster route than the current one (${ranked.size} checked)")
      return
    }
    // Still the same trip, still nothing else on screen?
    if (!super.navigationUiState.value.isNavigating() || _routeChoice.value != null) return
    _fasterRoute.value =
        FasterRouteState(
            best.first,
            savesSeconds = current.totalSeconds - best.second.totalSeconds,
            currentDelaySeconds = current.totalSeconds - current.baseSeconds,
        )
    fasterRouteTimeout?.cancel()
    fasterRouteTimeout = viewModelScope.launch {
      // The offer goes stale as we drive on (we may pass the turn-off): drop it after a while.
      kotlinx.coroutines.delay(FASTER_ROUTE_VISIBLE_MS)
      _fasterRoute.value = null
    }
  }

  /** The driver chose the faster route. */
  fun switchToFasterRoute() {
    val offer = _fasterRoute.value ?: return
    clearFasterRoute()
    if (simulating) locationProvider.enableSimulationOn(offer.option.route)
    core.replaceRoute(offer.option.route)
    applyVoice()
  }

  /** The driver keeps the current route. */
  fun keepCurrentRoute() = clearFasterRoute()

  private fun clearFasterRoute() {
    fasterRouteTimeout?.cancel()
    _fasterRoute.value = null
    // Don't ask again straight away.
    lastFasterRouteCheckMs = System.currentTimeMillis()
  }

  /** Fires when a new trip starts, so the map can show the whole route for a moment. */
  private val _routeStarted = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val routeStarted: kotlinx.coroutines.flow.SharedFlow<Unit> = _routeStarted

  fun retryRoute() {
    lastRequest?.invoke()
  }

  fun dismissRouteStatus() {
    _routeStatus.value = RouteStatus.Idle
  }

  private fun routingError(e: Exception): String {
    val chain = generateSequence(e as Throwable) { it.cause }.toList()
    return when {
      chain.any { it is java.net.UnknownHostException } -> L10n.s(R.string.route_offline)
      chain.any { it is java.net.SocketTimeoutException || it is java.io.InterruptedIOException } ->
          L10n.s(R.string.route_timeout)
      else -> L10n.s(R.string.route_failed, e.message ?: e.javaClass.simpleName)
    }
  }

  // ---- Places: fuel, charging, food, shops; near you or along the route --------------------------

  private fun here(): LatLng? {
    val loc = super.navigationUiState.value.location ?: browsingLocation.value ?: return null
    return LatLng(loc.coordinates.lat, loc.coordinates.lng)
  }

  private fun routeAhead(): List<LatLng>? {
    val ui = super.navigationUiState.value
    if (!ui.isNavigating()) return null
    return remainingGeometry(ui)?.map { LatLng(it.lat, it.lng) }
  }

  fun openPlaces() {
    _places.value = _places.value.copy(open = true, alongRoute = super.navigationUiState.value.isNavigating())
  }

  fun closePlaces() {
    placesJob?.cancel()
    _places.value = PlacesState(stops = _places.value.stops)
  }

  fun searchCategory(category: PlaceCategory) {
    _places.value = _places.value.copy(open = true, category = category, query = "", selected = null)
    loadProgressive { here, route -> placesRepo.byCategory(category, here, route) }
  }

  /** A search that shows the first close results right away and refines them as more arrive. */
  private fun loadProgressive(source: (LatLng, List<LatLng>?) -> kotlinx.coroutines.flow.Flow<PlacesUpdate>) {
    placesJob?.cancel()
    val here = here() ?: return noLocationYet()
    val route = routeAhead()
    _places.value =
        _places.value.copy(
            loading = true, refining = false, results = emptyList(), error = null, note = null,
            alongRoute = route != null, priceCountry = placesRepo.priceCountry(here))
    placesJob =
        viewModelScope.launch {
          try {
            source(here, route).collect { u ->
              _places.value =
                  _places.value.copy(loading = false, refining = !u.done, results = u.results, note = u.note)
            }
          } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "Places search failed", e)
            _places.value =
                _places.value.copy(
                    loading = false, refining = false,
                    error = placesError(e))
          }
        }
  }

  private fun noLocationYet() {
    _places.value = _places.value.copy(loading = false, error = L10n.s(R.string.places_waiting_location))
  }

  /** What went wrong, in words a driver can act on. */
  private fun placesError(e: Exception): String =
      L10n.s(R.string.places_failed, app.drivr.places.PlacesException.reason(e))

  /** Runs the last search again (after an error). */
  fun retryPlaces() {
    val s = _places.value
    if (s.category != null) searchCategory(s.category) else if (s.query.isNotBlank()) searchText(s.query)
  }

  fun searchText(query: String) {
    _places.value = _places.value.copy(query = query, category = null, selected = null)
    if (query.isBlank()) {
      _places.value = _places.value.copy(results = emptyList(), loading = false, error = null)
      return
    }
    load(debounceMs = 350) { here, route -> placesRepo.search(query.trim(), here, route) }
  }

  private fun load(debounceMs: Long = 0, block: suspend (LatLng, List<LatLng>?) -> List<PlaceResult>) {
    placesJob?.cancel()
    val here = here() ?: return noLocationYet()
    val route = routeAhead()
    _places.value =
        _places.value.copy(
            loading = true, error = null, alongRoute = route != null, priceCountry = placesRepo.priceCountry(here))
    placesJob =
        viewModelScope.launch {
          if (debounceMs > 0) kotlinx.coroutines.delay(debounceMs)
          try {
            val results = block(here, route)
            _places.value = _places.value.copy(loading = false, results = results)
          } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "Places search failed", e)
            _places.value =
                _places.value.copy(loading = false, error = placesError(e))
          }
        }
  }

  /** Opens a place; while navigating, asks Valhalla for the real extra time of stopping there. */
  fun selectPlace(result: PlaceResult?) {
    _places.value = _places.value.copy(selected = result, exactDetourS = null)
    val dest = destination ?: return
    if (result == null || !super.navigationUiState.value.isNavigating()) return
    val from = super.navigationUiState.value.location ?: return
    val remaining = super.navigationUiState.value.progress?.durationRemaining ?: return
    viewModelScope.launch(Dispatchers.IO) {
      runCatching {
            val via = core.getRoutes(from, listOf(result.place.waypoint(), dest)).first()
            via.steps.sumOf { it.duration } - remaining
          }
          .onSuccess { extra ->
            if (_places.value.selected?.place?.id == result.place.id)
                _places.value = _places.value.copy(exactDetourS = extra.coerceAtLeast(0.0))
          }
    }
  }

  /** Drive straight to a place (replaces the current trip). */
  fun driveTo(place: Place) {
    rememberRecent(place)
    closePlanner()
    val navigatingFrom = super.navigationUiState.value.location
    closePlaces()
    val to = GeographicCoordinate(place.lat, place.lon)
    if (navigatingFrom != null && super.navigationUiState.value.isNavigating()) {
      lastRequest = { driveTo(place) }
      navigate(navigatingFrom, to, place.name, simulate = simulating)
    } else {
      routeFromHere(to, place.name)
    }
  }

  /** Add a stop before the destination and re-route; the trip carries on afterwards. */
  fun addStop(place: Place) {
    val dest = destination ?: return driveTo(place)
    val from = super.navigationUiState.value.location ?: return
    val stops = _places.value.stops + place
    closePlaces()
    lastRequest = { navigate(from, dest.coordinate, destinationName, simulate = simulating, stops = stops) }
    navigate(from, dest.coordinate, destinationName, simulate = simulating, stops = stops)
  }

  private fun Place.waypoint() =
      Waypoint(coordinate = GeographicCoordinate(lat, lon), kind = WaypointKind.BREAK)

  // ---- Route planner: from anywhere to anywhere, saved places and recents ------------------------

  val savedPlaces: StateFlow<app.drivr.places.SavedPlaces> = savedStore.state

  private val _plan = MutableStateFlow(PlanState())
  val plan = _plan.asStateFlow()
  private var planJob: Job? = null
  private var planSearchJob: Job? = null

  /** Fires when the planner has routes, so the map can fit them. */
  private val _planRoutesReady = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val planRoutesReady: kotlinx.coroutines.flow.SharedFlow<Unit> = _planRoutesReady

  /** Opens the planner, from your location unless [from] says otherwise. Without [to] it asks where to. */
  fun openPlanner(to: Place? = null, from: RouteStart = RouteStart.Here) {
    closePlaces()
    _plan.value = PlanState(open = true, from = from, to = to, editing = if (to == null) PlanField.TO else null)
    if (to != null) planRoutes()
  }

  /** Set home or work: the planner's search list, for that one field. */
  fun editSavedPlace(field: PlanField) {
    closePlaces()
    val p = _plan.value
    _plan.value = if (p.open) p.copy(editing = field, query = "", results = emptyList()) else PlanState(open = true, editing = field)
  }

  fun closePlanner() {
    planJob?.cancel()
    planSearchJob?.cancel()
    if (_plan.value.open && _routeStatus.value is RouteStatus.Loading) _routeStatus.value = RouteStatus.Idle
    _plan.value = PlanState()
  }

  fun editPlanField(field: PlanField) {
    _plan.value = _plan.value.copy(editing = field, query = "", results = emptyList(), searchError = null, pickingOnMap = null)
  }

  /** Back from the suggestion list. Closes the planner when there is nothing to show behind it. */
  fun cancelPlanEdit() {
    planSearchJob?.cancel()
    val p = _plan.value
    // Nothing behind the list (opened to pick a destination or to set home): close it all.
    if (p.to == null) return closePlanner()
    _plan.value = p.copy(editing = null, query = "", results = emptyList(), pickingOnMap = null)
  }

  fun planSearch(query: String) {
    _plan.value = _plan.value.copy(query = query, searchError = null)
    planSearchJob?.cancel()
    if (query.isBlank()) {
      _plan.value = _plan.value.copy(results = emptyList(), searching = false)
      return
    }
    val here = here() ?: return
    _plan.value = _plan.value.copy(searching = true)
    planSearchJob =
        viewModelScope.launch {
          kotlinx.coroutines.delay(350)
          try {
            val results = placesRepo.search(query.trim(), here, null)
            _plan.value = _plan.value.copy(searching = false, results = results)
          } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "Planner search failed", e)
            _plan.value = _plan.value.copy(searching = false, searchError = placesError(e))
          }
        }
  }

  /** A suggestion was picked for the field being edited. */
  fun choosePlanPlace(place: Place) {
    val p = _plan.value
    when (val field = p.editing ?: p.pickingOnMap ?: return) {
      PlanField.HOME, PlanField.WORK -> {
        savedStore.update { if (field == PlanField.HOME) it.copy(home = place) else it.copy(work = place) }
        // Opened only to set home/work: done. Otherwise back to the route.
        if (p.to == null && p.from == RouteStart.Here) closePlanner()
        else _plan.value = p.copy(editing = null, pickingOnMap = null, query = "", results = emptyList())
      }
      PlanField.FROM -> setPlan(p.copy(from = RouteStart.At(place)))
      PlanField.TO -> setPlan(p.copy(to = place))
    }
  }

  /** "Your location" picked as the start. */
  fun choosePlanHere() {
    val p = _plan.value
    setPlan(p.copy(from = RouteStart.Here))
  }

  private fun setPlan(next: PlanState) {
    _plan.value = next.copy(editing = null, pickingOnMap = null, query = "", results = emptyList(), options = emptyList(), selected = 0)
    if (next.to != null) planRoutes()
  }

  /** The next long-press on the map sets [field]. */
  fun pickPlanOnMap(field: PlanField) {
    _plan.value = _plan.value.copy(editing = null, pickingOnMap = field, query = "", results = emptyList())
  }

  /**
   * Long-press on the map while not driving. Picking a point for the planner sets that field;
   * otherwise it plans a route to the point (it used to start driving straight away).
   */
  fun onMapLongPress(at: GeographicCoordinate) {
    val pin = droppedPin(at)
    val p = _plan.value
    if (p.open && p.pickingOnMap != null) choosePlanPlace(pin) else openPlanner(to = pin, from = if (p.open) p.from else RouteStart.Here)
    // Name the pin after the address there, when the geocoder knows it.
    viewModelScope.launch {
      val found = placesRepo.whatIsAt(LatLng(at.lat, at.lng)) ?: return@launch
      val named = pin.copy(name = found.name, address = found.address ?: pin.address)
      val now = _plan.value
      _plan.value =
          now.copy(
              to = if (now.to?.id == pin.id) named else now.to,
              from = (now.from as? RouteStart.At)?.takeIf { it.place.id == pin.id }?.let { RouteStart.At(named) } ?: now.from,
          )
      savedStore.update { s ->
        s.copy(home = s.home?.takeIf { it.id != pin.id } ?: named.takeIf { s.home?.id == pin.id },
            work = s.work?.takeIf { it.id != pin.id } ?: named.takeIf { s.work?.id == pin.id })
      }
    }
  }

  private fun droppedPin(at: GeographicCoordinate) =
      Place(
          id = "pin/${"%.5f".format(java.util.Locale.ROOT, at.lat)},${"%.5f".format(java.util.Locale.ROOT, at.lng)}",
          name = L10n.s(R.string.plan_dropped_pin),
          category = null,
          lat = at.lat,
          lon = at.lng,
          address = "%.5f, %.5f".format(java.util.Locale.ROOT, at.lat, at.lng),
      )

  /** Start and destination change places (your location goes to the destination as an address is needed there). */
  fun swapPlan() {
    val p = _plan.value
    val oldFrom = (p.from as? RouteStart.At)?.place
    val to = p.to ?: return
    if (oldFrom == null) {
      // "From your location to X" becomes "from X to … your location": route to where the phone is now.
      val here = here() ?: return
      setPlan(p.copy(from = RouteStart.At(to), to = Place("here", L10n.s(R.string.plan_your_location), null, here.lat, here.lon)))
    } else if (to.id == "here") {
      setPlan(p.copy(from = RouteStart.Here, to = oldFrom))
    } else {
      setPlan(p.copy(from = RouteStart.At(to), to = oldFrom))
    }
  }

  fun selectPlanRoute(index: Int) {
    val p = _plan.value
    if (index in p.options.indices) _plan.value = p.copy(selected = index)
  }

  /** Routes for the planner. From your location it waits for GPS, like a normal trip start. */
  fun planRoutes() {
    val p = _plan.value
    val to = p.to ?: return
    lastRequest = ::planRoutes
    planJob?.cancel()
    planJob =
        viewModelScope.launch {
          val from: UserLocation =
              when (val start = p.from) {
                is RouteStart.At -> UserLocation(GeographicCoordinate(start.place.lat, start.place.lon), 5.0, null, java.time.Instant.now(), null)
                RouteStart.Here ->
                    (if (!hasLocationPermission.value) browsingLocation.value else liveFix.value)
                        ?: run {
                          _routeStatus.value = RouteStatus.Loading(L10n.s(R.string.route_waiting_gps))
                          withTimeoutOrNull(20_000) { liveFix.filterNotNull().first() }
                        }
                        ?: run {
                          _routeStatus.value = RouteStatus.Failed(L10n.s(R.string.route_no_gps))
                          return@launch
                        }
              }
          _routeStatus.value = RouteStatus.Loading()
          try {
            val waypoints = listOf(Waypoint(GeographicCoordinate(to.lat, to.lon), WaypointKind.BREAK))
            val ranked =
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                  val routes =
                      runCatching { alternatives.routes(from, waypoints) }
                          .recoverCatching {
                            if (it is kotlinx.coroutines.CancellationException) throw it
                            core.getRoutes(from, waypoints)
                          }
                          .getOrThrow()
                          .filter { it.geometry.size >= 2 }
                  if (routes.isEmpty()) error(L10n.s(R.string.route_none))
                  withBorderChecks(from, waypoints, routes)
                }
            if (_plan.value.to?.id != to.id || !_plan.value.open) return@launch
            _plan.value = _plan.value.copy(options = ranked.take(3), selected = 0)
            _routeStatus.value = RouteStatus.Idle
            _planRoutesReady.tryEmit(Unit)
          } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.e(TAG, "Planning failed", e)
            _routeStatus.value = RouteStatus.Failed(routingError(e))
          }
        }
  }

  /**
   * Drives the chosen route. From your location that is the real trip; from another address it is
   * a simulated preview drive along it (the phone isn't there, so real guidance would reroute at once).
   */
  fun startPlannedTrip() {
    val p = _plan.value
    val to = p.to ?: return
    val option = p.options.getOrNull(p.selected) ?: return
    if (to.id != "here") rememberRecent(to)
    (p.from as? RouteStart.At)?.place?.takeIf { it.id != "here" }?.let { rememberRecent(it) }
    planJob?.cancel()
    _plan.value = PlanState()
    val dest = Waypoint(GeographicCoordinate(to.lat, to.lon), WaypointKind.BREAK)
    beginTrip(listOf(option), dest, to.name, simulate = !p.fromHere, stops = emptyList())
  }

  private fun rememberRecent(place: Place) {
    if (place.id == "here") return
    savedStore.update { it.withRecent(place) }
  }

  fun clearSavedPlace(field: PlanField) {
    savedStore.update {
      when (field) {
        PlanField.HOME -> it.copy(home = null)
        PlanField.WORK -> it.copy(work = null)
        else -> it
      }
    }
  }

  fun clearRecents() = savedStore.update { it.copy(recent = emptyList()) }

  /** "On my way to Amersfoort, arriving around 10:42 (35 min)." for the share sheet; null when not driving. */
  fun shareEtaText(now: java.time.LocalTime = java.time.LocalTime.now()): String? {
    val ui = super.navigationUiState.value
    if (!ui.isNavigating()) return null
    val progress = ui.progress ?: return null
    val delay = trafficAhead.value.sumOf { it.event.delaySeconds ?: 0.0 }
    val total = progress.durationRemaining + delay
    val at = now.plusSeconds(total.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    val left = app.drivr.ui.formatDuration(total)
    val name = destinationName
    return if (name != null) L10n.s(R.string.share_eta_to, name, at, left) else L10n.s(R.string.share_eta, at, left)
  }

  override fun toggleMute() {
    updateSettings { it.copy(voice = !it.voice) }
  }

  override fun stopNavigation() {
    _plan.value = PlanState()
    destination = null
    simulating = false
    choiceTimeout?.cancel()
    _routeChoice.value = null
    fasterRouteJob?.cancel()
    clearFasterRoute()
    _routeStatus.value = RouteStatus.Idle
    _places.value = PlacesState()
    locationProvider.disableSimulation()
    core.stopNavigation()
  }

  // ---- "Hey Drivr" voice commands ----------------------------------------------------------------

  private val voiceEngine by lazy { AppModule.voice }
  private val assistant by lazy {
    app.drivr.voice.VoiceAssistant(app.drivr.voice.CommandGrammar.forLanguage(L10n.locale.language), ::runVoiceCommand)
  }
  private val _voice = MutableStateFlow<app.drivr.voice.VoiceUi>(app.drivr.voice.VoiceUi.Off)
  /** The voice card on the map: mic chip, "Listening…", the answer. */
  val voice: StateFlow<app.drivr.voice.VoiceUi> = _voice.asStateFlow()
  val voiceModel: StateFlow<app.drivr.voice.ModelState> get() = voiceEngine.state
  private val _voiceProblem = MutableStateFlow<String?>(null)
  /** "unsupported" when the speech engine can't run on this phone; otherwise the last error, or null. */
  val voiceProblem: StateFlow<String?> = _voiceProblem.asStateFlow()
  private val _voiceEvents = kotlinx.coroutines.flow.MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 4)
  /** Things only the screen can do: the share sheet, the end-trip question. */
  val voiceEvents: kotlinx.coroutines.flow.SharedFlow<VoiceEvent> = _voiceEvents
  private var appOnScreen = false
  private var micAllowed = false
  private var voiceTicker: Job? = null
  /** "End the trip?" asked by voice and still on screen. */
  private var endTripAsked = false
  private var voiceWatch: Job? = null

  /** Drivr came on screen / went to the background: the microphone is only used on screen. */
  fun onAppOnScreen(onScreen: Boolean) {
    appOnScreen = onScreen
    if (onScreen && voiceWatch == null) watchVoiceSettings()
    updateListening()
  }

  fun onMicPermission(granted: Boolean) {
    micAllowed = granted
    if (granted && settingsStore.state.value.voiceCommands) voiceEngine.download()
    updateListening()
  }

  fun downloadSpeechModel() {
    _voiceProblem.value = null
    voiceEngine.download()
  }

  fun deleteSpeechModels() {
    voiceEngine.deleteModels()
    updateListening()
  }

  fun speechModelsMb(): Int = voiceEngine.downloadedMb()

  /** Model download finished, or the setting changed: start or stop the microphone. */
  private fun watchVoiceSettings() {
    voiceEngine.useLanguage(L10n.locale.language)
    voiceWatch =
        viewModelScope.launch {
          combine(settingsStore.state.map { it.voiceCommands }.distinctUntilChanged(), voiceEngine.state) { on, model -> on to model }
              .collect { (on, model) ->
                // Language switched with voice commands on: fetch that language's model too.
                if (on && micAllowed && model == app.drivr.voice.ModelState.Missing) voiceEngine.download()
                updateListening()
              }
        }
  }

  private fun updateListening() {
    val on =
        settingsStore.state.value.voiceCommands && micAllowed && appOnScreen &&
            voiceEngine.state.value == app.drivr.voice.ModelState.Ready && _voiceProblem.value != "unsupported"
    if (on) {
      voiceEngine.start(
          assistant.grammar,
          isSpeaking = AppModule::drivrIsSpeaking,
          onPartial = { if (assistant.onPartial(it)) app.drivr.voice.Earcon.wake() ; publishVoice() },
          onResult = { if (assistant.onResult(it)) app.drivr.voice.Earcon.wake(); publishVoice() },
          onError = { problem ->
            _voiceProblem.value = problem
            voiceEngine.stop()
            assistant.setListening(false)
            publishVoice()
          },
      )
    } else {
      voiceEngine.stop()
    }
    assistant.setListening(on)
    publishVoice()
  }

  /** Tap on the mic chip: talk without the wake phrase. */
  fun tapVoice() {
    assistant.tapToTalk()
    if (assistant.ui is app.drivr.voice.VoiceUi.Awake) app.drivr.voice.Earcon.wake()
    publishVoice()
  }

  fun dismissVoice() {
    assistant.dismiss()
    publishVoice()
  }

  /** The end-trip question was answered or closed on screen. */
  fun onEndTripClosed() {
    endTripAsked = false
  }

  private fun publishVoice() {
    _voice.value = assistant.ui
    // Cards close by themselves: tick while one is showing.
    if (assistant.ui is app.drivr.voice.VoiceUi.Waiting || assistant.ui is app.drivr.voice.VoiceUi.Off) return
    if (voiceTicker?.isActive == true) return
    voiceTicker =
        viewModelScope.launch {
          while (assistant.ui !is app.drivr.voice.VoiceUi.Waiting && assistant.ui !is app.drivr.voice.VoiceUi.Off) {
            kotlinx.coroutines.delay(500)
            assistant.expire()
            _voice.value = assistant.ui
          }
        }
  }

  /** Says [reply] (when the voice is on) and hands it to the card. */
  private fun answer(text: String, ok: Boolean = true, asks: Boolean = false): app.drivr.voice.VoiceReply {
    speak(text)
    return app.drivr.voice.VoiceReply(text, ok, asks)
  }

  private fun runVoiceCommand(command: app.drivr.voice.VoiceCommand): app.drivr.voice.VoiceReply {
    val ui = super.navigationUiState.value
    val driving = ui.isNavigating()
    val notDriving = { answer(L10n.s(R.string.voice_r_not_driving), ok = false) }
    return when (command) {
      app.drivr.voice.VoiceCommand.MUTE -> {
        updateSettings { it.copy(voice = false) }
        app.drivr.voice.VoiceReply(L10n.s(R.string.voice_r_muted))
      }
      app.drivr.voice.VoiceCommand.UNMUTE -> {
        updateSettings { it.copy(voice = true) }
        answer(L10n.s(R.string.voice_r_unmuted))
      }
      app.drivr.voice.VoiceCommand.AVOID_TOLLS -> {
        updateSettings { it.copy(avoidTolls = true) }
        if (driving && rerouteWithPreferences()) answer(L10n.s(R.string.voice_r_avoid_tolls_reroute))
        else answer(L10n.s(R.string.voice_r_avoid_tolls))
      }
      app.drivr.voice.VoiceCommand.AVOID_MOTORWAYS -> {
        updateSettings { it.copy(avoidMotorways = true) }
        if (driving && rerouteWithPreferences()) answer(L10n.s(R.string.voice_r_avoid_motorways_reroute))
        else answer(L10n.s(R.string.voice_r_avoid_motorways))
      }
      app.drivr.voice.VoiceCommand.FASTER_ROUTE -> {
        val offer = _fasterRoute.value
        when {
          !driving -> notDriving()
          offer != null -> fasterRouteReply(offer)
          else -> {
            checkFasterRouteNow()
            answer(L10n.s(R.string.voice_r_checking_faster))
          }
        }
      }
      app.drivr.voice.VoiceCommand.GO_HOME ->
          savedPlaces.value.home?.let {
            driveTo(it)
            answer(L10n.s(R.string.voice_r_going_home))
          } ?: answer(L10n.s(R.string.voice_r_no_home), ok = false)
      app.drivr.voice.VoiceCommand.GO_WORK ->
          savedPlaces.value.work?.let {
            driveTo(it)
            answer(L10n.s(R.string.voice_r_going_work))
          } ?: answer(L10n.s(R.string.voice_r_no_work), ok = false)
      app.drivr.voice.VoiceCommand.FIND_FUEL -> findNearby(PlaceCategory.FUEL)
      app.drivr.voice.VoiceCommand.FIND_CHARGING -> findNearby(PlaceCategory.CHARGING)
      app.drivr.voice.VoiceCommand.FIND_PARKING -> findNearby(PlaceCategory.PARKING)
      app.drivr.voice.VoiceCommand.SHARE_ETA -> {
        if (!driving) notDriving()
        else {
          _voiceEvents.tryEmit(VoiceEvent.ShareEta)
          app.drivr.voice.VoiceReply(L10n.s(R.string.voice_r_share))
        }
      }
      app.drivr.voice.VoiceCommand.STOP_NAVIGATION -> {
        if (!driving) notDriving()
        else {
          endTripAsked = true
          _voiceEvents.tryEmit(VoiceEvent.AskEndTrip)
          answer(L10n.s(R.string.voice_r_end_ask), asks = true)
        }
      }
      app.drivr.voice.VoiceCommand.ETA -> {
        val progress = ui.progress
        if (!driving || progress == null) notDriving()
        else {
          val total = progress.durationRemaining + trafficAhead.value.sumOf { it.event.delaySeconds ?: 0.0 }
          val at = java.time.LocalTime.now().plusSeconds(total.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
          answer(L10n.s(R.string.voice_r_eta, at, app.drivr.ui.formatDuration(total)))
        }
      }
      app.drivr.voice.VoiceCommand.NEXT_TURN -> {
        val text = ui.visualInstruction?.primaryContent?.text
        val dist = ui.progress?.distanceToNextManeuver
        when {
          !driving -> notDriving()
          text.isNullOrBlank() || dist == null -> answer(L10n.s(R.string.voice_r_no_turn), ok = false)
          else -> answer(L10n.s(R.string.voice_r_next_turn, app.drivr.ui.formatDistance(dist), text))
        }
      }
      app.drivr.voice.VoiceCommand.TRAFFIC_AHEAD -> {
        if (!driving) notDriving()
        else {
          val next = trafficAhead.value.firstOrNull { it.distanceM < LOOK_AHEAD_M }
          when {
            next == null -> answer(L10n.s(R.string.voice_r_traffic_none))
            next.inside -> answer(L10n.s(R.string.voice_r_traffic_inside, app.drivr.ui.formatDistance(next.endM ?: 0.0)))
            else -> {
              val delay = next.event.delaySeconds?.takeIf { it >= 60 }?.let { " · " + L10n.s(R.string.delay_plus_min, (it / 60).toInt()) } ?: ""
              answer(L10n.s(R.string.voice_r_traffic, next.event.kind.label, app.drivr.ui.formatDistance(next.distanceM)) + delay)
            }
          }
        }
      }
      app.drivr.voice.VoiceCommand.YES ->
          when {
            endTripAsked && driving -> {
              endTripAsked = false
              _voiceEvents.tryEmit(VoiceEvent.CloseEndTrip)
              stopNavigation()
              answer(L10n.s(R.string.voice_r_trip_ended))
            }
            _fasterRoute.value != null -> {
              switchToFasterRoute()
              answer(L10n.s(R.string.voice_r_switched))
            }
            _routeChoice.value != null -> {
              confirmRouteChoice()
              answer(L10n.s(R.string.voice_r_route_chosen))
            }
            else -> answer(L10n.s(R.string.voice_r_nothing_to_answer), ok = false)
          }
      app.drivr.voice.VoiceCommand.NO ->
          when {
            endTripAsked -> {
              endTripAsked = false
              _voiceEvents.tryEmit(VoiceEvent.CloseEndTrip)
              answer(L10n.s(R.string.voice_r_keep_driving))
            }
            _fasterRoute.value != null -> {
              keepCurrentRoute()
              answer(L10n.s(R.string.voice_r_keep_driving))
            }
            else -> answer(L10n.s(R.string.voice_r_nothing_to_answer), ok = false)
          }
      // Handled by the assistant itself.
      app.drivr.voice.VoiceCommand.CANCEL, app.drivr.voice.VoiceCommand.HELP -> app.drivr.voice.VoiceReply("")
    }
  }

  private fun fasterRouteReply(offer: FasterRouteState) =
      answer(L10n.s(R.string.voice_r_faster_found, app.drivr.ui.formatDuration(offer.savesSeconds)), asks = true)

  private fun findNearby(category: PlaceCategory): app.drivr.voice.VoiceReply {
    closePlanner()
    openPlaces()
    searchCategory(category)
    return answer(L10n.s(R.string.voice_r_searching, category.label.lowercase(L10n.locale)))
  }

  /** "Avoid tolls" while driving: the same trip again from here, with the new preferences. */
  private fun rerouteWithPreferences(): Boolean {
    val dest = destination ?: return false
    val from = super.navigationUiState.value.location ?: return false
    val stops = _places.value.stops
    lastRequest = { navigate(from, dest.coordinate, destinationName, simulate = simulating, stops = stops) }
    navigate(from, dest.coordinate, destinationName, simulate = simulating, stops = stops)
    return true
  }

  /** "Is there a faster route?": look now (not waiting for a jam), and say what came out. */
  private fun checkFasterRouteNow() {
    if (fasterRouteJob?.isActive == true) return
    lastFasterRouteCheckMs = System.currentTimeMillis()
    fasterRouteJob =
        viewModelScope.launch(Dispatchers.Default) {
          lookForFasterRoute()
          kotlinx.coroutines.withContext(Dispatchers.Main) {
            val offer = _fasterRoute.value
            val reply =
                if (offer != null) fasterRouteReply(offer)
                else answer(L10n.s(R.string.voice_r_no_faster), ok = false)
            assistant.show(reply)
            publishVoice()
          }
        }
  }

  override fun onCleared() {
    super.onCleared()
    voiceEngine.stop()
  }

  companion object {
    private const val TAG = "DrivrViewModel"
    private const val CHOICE_VISIBLE_MS = 25_000L
    private const val BORDER_ROUTE_TIMEOUT_MS = 12_000L
    private const val BORDER_REPORTS_TIMEOUT_MS = 6_000L
    private const val FASTER_ROUTE_VISIBLE_MS = 45_000L
    private const val FASTER_ROUTE_CHECK_EVERY_MS = 4 * 60_000L
    /** Only jams this close count: further ones may clear before we get there. */
    private const val LOOK_AHEAD_M = 60_000.0
  }
}
