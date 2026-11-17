package app.drivr

import app.drivr.i18n.L10n
import android.util.Log
import app.drivr.traffic.LatLon
import app.drivr.traffic.RouteTraffic
import app.drivr.traffic.TrafficAhead
import app.drivr.traffic.TrafficEvent
import app.drivr.traffic.TrafficRepository
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
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind

sealed interface RouteStatus {
  data object Idle : RouteStatus

  data class Loading(val message: String = L10n.s(R.string.route_finding)) : RouteStatus

  data class Failed(val message: String, val canRetry: Boolean = true) : RouteStatus
}

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

@OptIn(ExperimentalCoroutinesApi::class)
class DrivrViewModel(
    private val core: FerrostarCore = AppModule.ferrostarCore,
    private val locationProvider: NavigationLocationProvider = AppModule.locationProvider,
    private val traffic: TrafficRepository = AppModule.traffic,
    private val placesRepo: PlacesRepository = AppModule.places,
    private val cameraClient: SpeedCameraClient = AppModule.speedCameras,
    private val settingsStore: app.drivr.settings.SettingsStore = AppModule.settings,
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
  private var simulating = false

  init {
    traffic.start(viewModelScope)
    watchRouteForCameras()
    viewModelScope.launch {
      hasLocationPermission
          .flatMapLatest { granted ->
            if (granted) locationProvider.locationUpdates(2000L).map { it.toUserLocation() }
            else flowOf(DemoRoute.origin)
          }
          .collect {
            browsingLocation.value = it
            if (hasLocationPermission.value) liveFix.value = it
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

  /** Real navigation from the current location. */
  fun navigateTo(destination: GeographicCoordinate) = routeFromHere(destination, name = null)

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
        // One quiet retry: the free routing server sometimes drops a request.
        val routes =
            runCatching { core.getRoutes(from, waypoints) }
                .recoverCatching {
                  if (it is kotlinx.coroutines.CancellationException) throw it
                  Log.w(TAG, "Routing failed once, retrying", it)
                  kotlinx.coroutines.delay(1500)
                  core.getRoutes(from, waypoints)
                }
                .getOrThrow()
        val route = routes.firstOrNull() ?: error(L10n.s(R.string.route_none))
        check(route.geometry.size >= 2) { L10n.s(R.string.route_no_line) }
        Log.i(TAG, "Route: ${route.geometry.size} points, ${route.distance.toInt()} m")
        if (simulate) locationProvider.enableSimulationOn(route)
        else locationProvider.disableSimulation()
        core.startNavigation(route = route)
        applyVoice()
        destination = dest
        destinationName = name
        simulating = simulate
        _places.value = _places.value.copy(stops = stops)
        _routeStatus.value = RouteStatus.Idle
        _routeStarted.tryEmit(Unit)
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Routing failed", e)
        _routeStatus.value = RouteStatus.Failed(routingError(e))
      }
    }
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

  override fun toggleMute() {
    updateSettings { it.copy(voice = !it.voice) }
  }

  override fun stopNavigation() {
    destination = null
    simulating = false
    _routeStatus.value = RouteStatus.Idle
    _places.value = PlacesState()
    locationProvider.disableSimulation()
    core.stopNavigation()
  }

  companion object {
    private const val TAG = "DrivrViewModel"
  }
}
