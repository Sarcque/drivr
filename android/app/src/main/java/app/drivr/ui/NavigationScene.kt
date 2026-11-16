package app.drivr.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import app.drivr.ar.ArJunctionView
import com.stadiamaps.ferrostar.maplibreui.routeline.RouteOverlayBuilder
import com.stadiamaps.ferrostar.core.NavigationUiState
import com.stadiamaps.ferrostar.core.measurement.MeasurementSpeedUnit
import com.stadiamaps.ferrostar.maplibreui.NavigationMapPuckStyle
import com.stadiamaps.ferrostar.composeui.config.withProgressView
import kotlin.math.roundToInt
import uniffi.ferrostar.VisualInstructionContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.core.content.ContextCompat
import app.drivr.DrivrViewModel
import app.drivr.RouteStatus
import com.stadiamaps.ferrostar.composeui.config.NavigationViewComponentBuilder
import com.stadiamaps.ferrostar.composeui.config.withCustomOverlayView
import com.stadiamaps.ferrostar.composeui.config.withInstructionsView
import com.stadiamaps.ferrostar.composeui.runtime.KeepScreenOnDisposableEffect
import com.stadiamaps.ferrostar.maplibreui.NavigationMapClickResult
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraOptions
import com.stadiamaps.ferrostar.maplibreui.runtime.rememberNavigationMapState
import com.stadiamaps.ferrostar.maplibreui.views.DynamicallyOrientingNavigationView
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.style.BaseStyle

/**
 * Driving view camera: low and steep, like looking over the bonnet. The puck sits in the lower
 * third so most of the screen shows the road ahead.
 */
@Composable
fun drivingCameraOptions(): NavigationCameraOptions {
  val config = LocalConfiguration.current
  return NavigationCameraOptions(
      browsingZoom = 15.5,
      navigationZoom = 17.6,
      navigationTilt = 60.0,
      browsingPadding = PaddingValues(0.dp),
      navigationPadding = PaddingValues(top = (config.screenHeightDp * 0.55f).dp),
  )
}

/** Drivr's own map style (style/drivr-style.json in the repo), on free OpenFreeMap tiles. */
@Composable
fun rememberDrivrStyle(): BaseStyle {
  val context = LocalContext.current
  return remember {
    BaseStyle.Json(context.assets.open("drivr-style.json").bufferedReader().use { it.readText() })
  }
}

@Composable
fun NavigationScene(viewModel: DrivrViewModel) {
  KeepScreenOnDisposableEffect()
  RequestLocationPermission(viewModel)

  val uiState by viewModel.navigationUiState.collectAsState()
  val routeStatus by viewModel.routeStatus.collectAsState()
  val mapState = rememberNavigationMapState()
  val trafficEvents by viewModel.trafficEvents.collectAsState()
  val trafficAhead by viewModel.trafficAhead.collectAsState()
  var showTurns by rememberSaveable { mutableStateOf(false) }
  val places by viewModel.places.collectAsState()
  androidx.activity.compose.BackHandler(enabled = places.open || places.selected != null) {
    if (places.selected != null) viewModel.selectPlace(null) else viewModel.closePlaces()
  }

  // AR junction view: opens by hand, or automatically ~300 m before each maneuver when Auto is on.
  var arManual by rememberSaveable { mutableStateOf(false) }
  var arAuto by rememberSaveable { mutableStateOf(true) }
  val nearManeuver = (uiState.progress?.distanceToNextManeuver ?: Double.MAX_VALUE) < 300.0
  val showAr = uiState.isNavigating() && (arManual || (arAuto && nearManeuver))

  Box(Modifier.fillMaxSize()) {
  DynamicallyOrientingNavigationView(
      modifier = Modifier.fillMaxSize(),
      baseStyle = rememberDrivrStyle(),
      routeOverlayBuilder = RouteOverlayBuilder(navigationPath = { DrivrRouteLine(it) }),
      navigationMapState = mapState,
      navigationCameraOptions = drivingCameraOptions(),
      viewModel = viewModel,
      showDefaultPuck = false,
      mapContent = { state ->
        TrafficLayers(trafficEvents)
        PlacesLayer(
            results = places.selected?.let { listOf(it) } ?: places.results,
            selectedId = places.selected?.place?.id,
        )
        ChevronPuck(state)
      },
      views =
          NavigationViewComponentBuilder.Default()
              .withInstructionsView { modifier, state ->
                state.visualInstruction?.let {
                  InstructionBanner(
                      instruction = it,
                      distanceToManeuverMeters = state.progress?.distanceToNextManeuver,
                      modifier = modifier,
                      then = state.nextManeuver(),
                  )
                }
              }
              .withProgressView { modifier, state, onExit ->
                state.progress?.let { progress ->
                  // Valhalla's ETA has no live traffic; add NDW's reported delays on the route.
                  val delayS = trafficAhead.sumOf { it.event.delaySeconds ?: 0.0 }
                  Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    trafficAhead.firstOrNull { it.distanceM < 30_000 }?.let { TrafficAheadCard(it) }
                    TripCards(
                        speedLimitKmh = state.speedLimitKmh(),
                        speedKmh = state.location?.speed?.value?.let { (it * 3.6).roundToInt() },
                        distanceRemainingM = progress.distanceRemaining,
                        durationRemainingS = progress.durationRemaining + delayS,
                        trafficDelayS = delayS,
                        onEnd = onExit,
                    )
                  }
                }
              }
              .withCustomOverlayView { modifier ->
                if (!uiState.isNavigating() && !places.open) {
                  StartPanel(
                      status = routeStatus,
                      onDemoDrive = viewModel::startDemoDrive,
                      modifier = modifier.align(Alignment.BottomCenter),
                  )
                }
              },
      onTapExit = { viewModel.stopNavigation() },
      onMapLongClick = { position, _ ->
        if (!uiState.isNavigating()) viewModel.navigateTo(position)
        NavigationMapClickResult.Consume
      },
      mapOptions =
          MapOptions(
              ornamentOptions = OrnamentOptions(isCompassEnabled = false, isScaleBarEnabled = false)
          ),
  )

  if (uiState.isNavigating() && !showAr && !places.open) {
    Column(
        Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      StopsButton(onClick = viewModel::openPlaces)
      ArToggle(
          auto = arAuto,
          onOpen = { arManual = true },
          onToggleAuto = { arAuto = !arAuto },
          onTurns = { showTurns = !showTurns },
          modifier = Modifier,
      )
    }
  }

  // Search bar and categories while browsing; the places sheet or a place's details when open.
  if (!uiState.isNavigating()) {
    Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
      PlacesSearchBar(places.query, viewModel::searchText, onFocus = viewModel::openPlaces)
      if (!places.open) {
        Spacer(Modifier.height(10.dp))
        CategoryChips(null, viewModel::searchCategory)
      }
    }
  }
  if (places.open) {
    val selected = places.selected
    val sheetModifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
    if (selected != null) {
      PlaceDetailCard(
          result = selected,
          navigating = uiState.isNavigating(),
          exactDetourS = places.exactDetourS,
          onAddStop = { viewModel.addStop(selected.place) },
          onDrive = { viewModel.driveTo(selected.place) },
          onBack = { viewModel.selectPlace(null) },
          priceCountry = places.priceCountry,
          modifier = sheetModifier,
      )
    } else {
      PlacesSheet(
          state = places,
          onSelectCategory = viewModel::searchCategory,
          onSelect = viewModel::selectPlace,
          onClose = viewModel::closePlaces,
          priceCountry = places.priceCountry,
          modifier = sheetModifier,
      )
    }
  }
  if (showTurns && uiState.isNavigating() && !showAr) {
    TurnList(
        steps = uiState.remainingSteps.orEmpty(),
        distanceToNextManeuver = uiState.progress?.distanceToNextManeuver ?: 0.0,
        onClose = { showTurns = false },
        modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
    )
  }
  if (showAr) {
    ArJunctionView(
        ui = uiState,
        onClose = {
          arManual = false
          arAuto = false
        },
    )
  }
  }
}

/** The maneuver after the current one, for the "Then" row. */
private fun NavigationUiState.nextManeuver(): VisualInstructionContent? =
    remainingSteps?.getOrNull(1)?.visualInstructions?.firstOrNull()?.primaryContent

private fun NavigationUiState.speedLimitKmh(): Int? {
  val limit = currentAnnotation?.speedLimit ?: return null
  return when (limit.unit) {
    MeasurementSpeedUnit.KilometersPerHour -> limit.value.roundToInt()
    MeasurementSpeedUnit.MilesPerHour -> (limit.value * 1.609344).roundToInt()
    else -> null
  }
}

@Composable
private fun ArToggle(
    auto: Boolean,
    onOpen: () -> Unit,
    onToggleAuto: () -> Unit,
    onTurns: () -> Unit,
    modifier: Modifier,
) {
  Column(
      modifier
          .clip(RoundedCornerShape(16.dp))
          .background(Drivr.card)
          .padding(6.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    TextButton(onClick = onTurns) { Text("Turns", color = Color.White, fontSize = 12.sp) }
    TextButton(onClick = onOpen) { Text("AR", color = Color.White, fontWeight = FontWeight.Bold) }
    TextButton(onClick = onToggleAuto) {
      Text(
          if (auto) "Auto\non" else "Auto\noff",
          color = if (auto) Drivr.accent else Drivr.textMuted,
          fontSize = 11.sp,
      )
    }
  }
}

@Composable
internal fun StartPanel(status: RouteStatus, onDemoDrive: () -> Unit, modifier: Modifier) {
  Column(
      modifier =
          modifier
              .navigationBarsPadding()
              .padding(16.dp)
              .fillMaxWidth()
              .clip(RoundedCornerShape(20.dp))
              .background(Drivr.card)
              .padding(20.dp)
  ) {
    Text("Drivr", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        "Search above or long-press the map to drive there, or take the test drive through " +
            "Knooppunt Rijnsweerd (Utrecht) with simulated GPS. While driving, tap Stops to find " +
            "fuel, charging or food along the route.",
        color = Drivr.textMuted,
        fontSize = 14.sp,
    )
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
      Button(
          onClick = onDemoDrive,
          enabled = status !is RouteStatus.Loading,
          colors = ButtonDefaults.buttonColors(containerColor = Drivr.accent),
      ) {
        Text("Start test drive")
      }
      Spacer(Modifier.width(14.dp))
      when (status) {
        RouteStatus.Loading ->
            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        is RouteStatus.Failed ->
            Text(status.message, color = Color(0xFFFF8A80), fontSize = 13.sp, maxLines = 2)
        RouteStatus.Idle -> {}
      }
    }
  }
}

@Composable
private fun RequestLocationPermission(viewModel: DrivrViewModel) {
  val context = LocalContext.current
  val permissions = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      add(Manifest.permission.POST_NOTIFICATIONS)
    }
  }
  val launcher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onLocationPermission(it[Manifest.permission.ACCESS_FINE_LOCATION] == true)
      }
  LaunchedEffect(Unit) {
    val granted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    if (granted) viewModel.onLocationPermission(true) else launcher.launch(permissions.toTypedArray())
  }
}
