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
import com.stadiamaps.ferrostar.composeui.config.VisualNavigationViewConfig
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
fun rememberDrivrStyle(buildings3d: Boolean = true): BaseStyle {
  val context = LocalContext.current
  return remember(buildings3d) {
    val json = context.assets.open("drivr-style.json").bufferedReader().use { it.readText() }
    BaseStyle.Json(if (buildings3d) json else withoutLayer(json, "building-3d"))
  }
}

/** The style JSON without one layer (e.g. 3D buildings). */
internal fun withoutLayer(styleJson: String, id: String): String {
  val style = org.json.JSONObject(styleJson)
  val layers = style.getJSONArray("layers")
  val kept = org.json.JSONArray()
  for (i in 0 until layers.length()) {
    val l = layers.getJSONObject(i)
    if (l.optString("id") != id) kept.put(l)
  }
  style.put("layers", kept)
  return style.toString()
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
  val cameraList by viewModel.cameraList.collectAsState()
  val camerasAhead by viewModel.camerasAhead.collectAsState()
  androidx.activity.compose.BackHandler(enabled = places.open || places.selected != null) {
    if (places.selected != null) viewModel.selectPlace(null) else viewModel.closePlaces()
  }

  val settings by viewModel.settings.collectAsState()
  var showSettings by rememberSaveable { mutableStateOf(false) }
  androidx.activity.compose.BackHandler(enabled = showSettings) { showSettings = false }

  // Music: one button next to the gear while browsing; while driving only via the "..." menu,
  // and only when enabled in Settings.
  val music = app.drivr.AppModule.music
  val musicState by music.state.collectAsState()
  var showMusic by rememberSaveable { mutableStateOf(false) }
  val musicAllowedNow = !uiState.isNavigating() || settings.musicWhileDriving
  val context = LocalContext.current
  val musicCard: @Composable (Modifier) -> Unit = { m ->
    MusicCard(
        state = musicState,
        onPlayPause = music::playPause,
        onPrevious = { music.previous() },
        onNext = { music.next() },
        onOpenPlayer = music::openPlayer,
        onAllow = { context.startActivity(music.accessSettingsIntent()) },
        onClose = { showMusic = false },
        modifier = m,
    )
  }

  // AR junction view: off unless enabled in Settings. Opens by hand, or ~300 m before each
  // maneuver when "Open AR before turns" is on.
  var arManual by rememberSaveable { mutableStateOf(false) }
  // Closing AR while it opened by itself keeps it closed for the rest of this trip.
  var arSnoozed by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(uiState.isNavigating()) { if (!uiState.isNavigating()) arSnoozed = false }
  val nearManeuver = (uiState.progress?.distanceToNextManeuver ?: Double.MAX_VALUE) < 300.0
  val showAr = settings.arEnabled && uiState.isNavigating() && (arManual || (settings.arAuto && !arSnoozed && nearManeuver))

  Box(Modifier.fillMaxSize()) {
  DynamicallyOrientingNavigationView(
      modifier = Modifier.fillMaxSize(),
      baseStyle = rememberDrivrStyle(buildings3d = settings.buildings3d),
      // Mute and zoom live in Settings / gestures; only Ferrostar's recenter button stays.
      config = VisualNavigationViewConfig(showMute = false, showZoom = false),
      routeOverlayBuilder = RouteOverlayBuilder(navigationPath = { DrivrRouteLine(it) }),
      navigationMapState = mapState,
      navigationCameraOptions = drivingCameraOptions(),
      viewModel = viewModel,
      showDefaultPuck = false,
      mapContent = { state ->
        if (settings.trafficOnMap) TrafficLayers(trafficEvents)
        if (settings.speedCameras) CamerasLayer(cameraList)
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
                    if (showMusic && musicAllowedNow && !places.open) musicCard(Modifier)
                    DrivingAlert(
                        camerasAhead.first, camerasAhead.second,
                        if (settings.trafficAlerts) trafficAhead else emptyList())
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
    DrivingControls(
        onSearch = viewModel::openPlaces,
        onTurns = { showTurns = !showTurns },
        onAr = if (settings.arEnabled) ({ arManual = true }) else null,
        onSettings = { showSettings = true },
        modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
        onMusic = if (settings.musicWhileDriving) ({ showMusic = !showMusic }) else null,
    )
  }

  // Search bar and categories while browsing; the places sheet or a place's details when open.
  if (!uiState.isNavigating()) {
    Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        PlacesSearchBar(places.query, viewModel::searchText, onFocus = viewModel::openPlaces, modifier = Modifier.weight(1f))
        if (!places.open) {
          Spacer(Modifier.width(8.dp))
          MusicButton(onClick = { showMusic = !showMusic })
          Spacer(Modifier.width(8.dp))
          SettingsButton(onClick = { showSettings = true })
        }
      }
      if (!places.open) {
        Spacer(Modifier.height(10.dp))
        CategoryChips(null, viewModel::searchCategory)
        if (showMusic) {
          Spacer(Modifier.height(10.dp))
          musicCard(Modifier)
        }
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
          onRetry = viewModel::retryPlaces,
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
  if (showSettings) {
    SettingsScreen(
        settings, viewModel::updateSettings, onClose = { showSettings = false },
        musicAccess = musicState.hasAccess,
        onMusicAccess = { context.startActivity(music.accessSettingsIntent()) },
    )
  }
  if (showAr) {
    ArJunctionView(
        ui = uiState,
        onClose = {
          arManual = false
          arSnoozed = true
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

/** Browsing: one compact card. Search sits at the top; this only offers the test drive. */
@Composable
internal fun StartPanel(status: RouteStatus, onDemoDrive: () -> Unit, modifier: Modifier) {
  Row(
      modifier =
          modifier
              .navigationBarsPadding()
              .padding(12.dp)
              .fillMaxWidth()
              .clip(RoundedCornerShape(20.dp))
              .background(Drivr.card)
              .padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      when (status) {
        is RouteStatus.Failed -> Text(status.message, color = Color(0xFFFF8A80), fontSize = 14.sp, maxLines = 2)
        else -> {
          Text("Long-press the map to go there", color = Drivr.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
          Text("or try a simulated drive", color = Drivr.textMuted, fontSize = 13.sp)
        }
      }
    }
    if (status is RouteStatus.Loading) {
      CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(22.dp), color = Color.White, strokeWidth = 2.dp)
    } else {
      Button(onClick = onDemoDrive, colors = ButtonDefaults.buttonColors(containerColor = Drivr.accent)) {
        Text("Test drive")
      }
    }
  }
}

/**
 * At most one alert above the arrival card, the most urgent: inside an average-speed section or
 * a camera close by, then being inside a jam, then the next jam within 30 km.
 */
@Composable
fun DrivingAlert(
    cameras: List<app.drivr.cameras.CameraAhead>,
    averageKmh: Double?,
    traffic: List<app.drivr.traffic.TrafficAhead>,
) {
  val cam = cameras.firstOrNull { it.inside } ?: cameras.firstOrNull { it.distanceM < 1000 }
  val jam = traffic.firstOrNull { it.inside }
  when {
    cam != null -> CameraAheadCard(cam, averageKmh)
    jam != null -> JamCountdownCard(jam)
    else -> traffic.firstOrNull { it.distanceM < 30_000 }?.let { TrafficAheadCard(it) }
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
