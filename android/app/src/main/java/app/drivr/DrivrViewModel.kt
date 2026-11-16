package app.drivr

import android.os.SystemClock
import android.util.Log
import app.drivr.cameras.Enforcement
import app.drivr.cameras.EnforcementAhead
import app.drivr.cameras.RouteEnforcement
import app.drivr.cameras.SpeedCameraRepository
import app.drivr.traffic.LatLon
import app.drivr.traffic.RouteTraffic
import app.drivr.traffic.TrafficAhead
import app.drivr.traffic.TrafficEvent
import app.drivr.traffic.TrafficRepository
import app.drivr.ui.remainingGeometry
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

@OptIn(ExperimentalCoroutinesApi::class)
class DrivrViewModel(
    private val core: FerrostarCore = AppModule.ferrostarCore,
    private val locationProvider: NavigationLocationProvider = AppModule.locationProvider,
    private val traffic: TrafficRepository = AppModule.traffic,
    private val speedCameras: SpeedCameraRepository = AppModule.speedCameras,
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

  /** Fixed cameras and section controls around the current route (OSM), loaded once per route. */
  private val _enforcement = MutableStateFlow<List<Enforcement>>(emptyList())
  val enforcement: StateFlow<List<Enforcement>> = _enforcement.asStateFlow()

  val enforcementAhead: StateFlow<List<EnforcementAhead>> =
      combine(super.navigationUiState, _enforcement) { ui, all ->
            val route = if (ui.isNavigating()) remainingGeometry(ui) else null
            if (route == null || all.isEmpty()) emptyList()
            else RouteEnforcement.ahead(route.map { LatLon(it.lat, it.lng) }, all)
          }
          .flowOn(Dispatchers.Default)
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  /**
   * Average speed since entering the section control we're in, like the cameras measure it:
   * distance driven in the section over time spent in it.
   */
  val sectionAverageKmh: StateFlow<Int?> = run {
    var sectionId: String? = null
    var enteredAtMs = 0L
    var entryLeftM = 0.0
    enforcementAhead
        .map { list ->
          val inside = list.firstOrNull { it.isInsideSection }
          if (inside == null) {
            sectionId = null
            return@map null
          }
          val left = inside.endDistanceM ?: return@map null
          val now = SystemClock.elapsedRealtime()
          if (inside.enforcement.id != sectionId) {
            sectionId = inside.enforcement.id
            enteredAtMs = now
            entryLeftM = left
          }
          val seconds = (now - enteredAtMs) / 1000.0
          if (seconds < 5) null else ((entryLeftM - left) / seconds * 3.6).toInt()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
  }

  init {
    traffic.start(viewModelScope)
    viewModelScope.launch {
      super.navigationUiState
          .map { it.routeGeometry }
          .distinctUntilChanged()
          .collect { geometry ->
            _enforcement.value =
                if (geometry.isNullOrEmpty()) emptyList()
                else speedCameras.forRoute(geometry.map { LatLon(it.lat, it.lng) })
          }
    }
    viewModelScope.launch {
      hasLocationPermission
          .flatMapLatest { granted ->
            if (granted) locationProvider.locationUpdates(2000L).map { it.toUserLocation() }
            else flowOf(DemoRoute.origin)
          }
          .collect { browsingLocation.value = it }
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
  ) {
    _routeStatus.value = RouteStatus.Loading
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val routes =
            core.getRoutes(from, listOf(Waypoint(coordinate = to, kind = WaypointKind.BREAK)))
        val route = routes.first()
        if (simulate) locationProvider.enableSimulationOn(route)
        else locationProvider.disableSimulation()
        core.startNavigation(route = route)
        _routeStatus.value = RouteStatus.Idle
      } catch (e: Exception) {
        Log.e(TAG, "Routing failed", e)
        _routeStatus.value = RouteStatus.Failed(e.message ?: "Routing failed")
      }
    }
  }

  override fun toggleMute() {
    val observer = core.spokenInstructionObserver ?: return
    observer.setMuted(!observer.isMuted)
  }

  override fun stopNavigation() {
    locationProvider.disableSimulation()
    core.stopNavigation()
  }

  companion object {
    private const val TAG = "DrivrViewModel"
  }
}
