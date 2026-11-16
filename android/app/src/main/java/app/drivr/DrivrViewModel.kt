package app.drivr

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

  data object Loading : RouteStatus

  data class Failed(val message: String) : RouteStatus
}

/** The places panel: what was asked, what came back, and which place is open. */
data class PlacesState(
    val open: Boolean = false,
    val query: String = "",
    val category: PlaceCategory? = null,
    val alongRoute: Boolean = false,
    val loading: Boolean = false,
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
) : DefaultNavigationViewModel(core, valhallaExtendedOSRMAnnotationPublisher()) {

  private val hasLocationPermission = MutableStateFlow(false)
  private val browsingLocation = MutableStateFlow<UserLocation?>(DemoRoute.origin)

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

  /** Fixed speed cameras and average-speed sections on the current route (OSM). */
  private val cameras = MutableStateFlow<List<SpeedCamera>>(emptyList())
  val cameraList: StateFlow<List<SpeedCamera>> = cameras.asStateFlow()
  private val sectionTracker = SectionTracker()

  /** Cameras on the route ahead, nearest first, and the average speed inside a section. */
  val camerasAhead: StateFlow<Pair<List<CameraAhead>, Double?>> =
      combine(super.navigationUiState, cameras) { ui, cams ->
            val route = if (ui.isNavigating() && cams.isNotEmpty()) remainingGeometry(ui) else null
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
          .collect { browsingLocation.value = it }
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
  fun startDemoDrive() =
      navigate(
          from = DemoRoute.origin,
          to = DemoRoute.destination,
          name = DemoRoute.destinationName,
          simulate = true,
      )

  /** Real navigation from the current location. */
  fun navigateTo(destination: GeographicCoordinate) {
    val here = browsingLocation.value ?: return
    navigate(from = here, to = destination, name = null, simulate = false)
  }

  private fun navigate(
      from: UserLocation,
      to: GeographicCoordinate,
      name: String?,
      simulate: Boolean,
      stops: List<Place> = emptyList(),
  ) {
    _routeStatus.value = RouteStatus.Loading
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val dest = Waypoint(coordinate = to, kind = WaypointKind.BREAK)
        val waypoints = stops.map { it.waypoint() } + dest
        val routes = core.getRoutes(from, waypoints)
        val route = routes.first()
        if (simulate) locationProvider.enableSimulationOn(route)
        else locationProvider.disableSimulation()
        core.startNavigation(route = route)
        destination = dest
        destinationName = name
        simulating = simulate
        _places.value = _places.value.copy(stops = stops)
        _routeStatus.value = RouteStatus.Idle
      } catch (e: Exception) {
        Log.e(TAG, "Routing failed", e)
        _routeStatus.value = RouteStatus.Failed(e.message ?: "Routing failed")
      }
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
    load { here, route -> placesRepo.byCategory(category, here, route) }
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
    val here = here() ?: return
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
                _places.value.copy(loading = false, error = "Couldn't reach the map server. Try again.")
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
    val from = super.navigationUiState.value.location ?: browsingLocation.value ?: return
    closePlaces()
    navigate(from, GeographicCoordinate(place.lat, place.lon), place.name, simulate = simulating)
  }

  /** Add a stop before the destination and re-route; the trip carries on afterwards. */
  fun addStop(place: Place) {
    val dest = destination ?: return driveTo(place)
    val from = super.navigationUiState.value.location ?: return
    val stops = _places.value.stops + place
    closePlaces()
    navigate(from, dest.coordinate, destinationName, simulate = simulating, stops = stops)
  }

  private fun Place.waypoint() =
      Waypoint(coordinate = GeographicCoordinate(lat, lon), kind = WaypointKind.BREAK)

  override fun toggleMute() {
    val observer = core.spokenInstructionObserver ?: return
    observer.setMuted(!observer.isMuted)
  }

  override fun stopNavigation() {
    destination = null
    _places.value = PlacesState()
    locationProvider.disableSimulation()
    core.stopNavigation()
  }

  companion object {
    private const val TAG = "DrivrViewModel"
  }
}
