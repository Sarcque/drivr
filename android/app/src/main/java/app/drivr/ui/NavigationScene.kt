package app.drivr.ui

import androidx.compose.ui.res.stringResource
import app.drivr.R
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import android.content.res.Configuration
import com.stadiamaps.ferrostar.core.boundingBox
import com.stadiamaps.ferrostar.maplibreui.NavigationMapView
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraMode
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
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
 * Driving view camera: low and steep, like looking over the bonnet. The car sits ~110 dp above
 * the bottom cards, so the road ahead fills the space between the banner and the cards. The
 * padding comes from the measured overlay, so the car is never hidden behind a card.
 */
fun drivingCameraOptions(
    screenHeight: Dp,
    bottomCards: Dp,
    startPanel: Dp = 0.dp,
    topCards: Dp = 0.dp,
): NavigationCameraOptions {
  val bottom = bottomCards.coerceIn(0.dp, screenHeight * 0.6f)
  val top = (screenHeight - bottom - 220.dp).coerceAtLeast(0.dp)
  return NavigationCameraOptions(
      browsingZoom = 15.5,
      navigationZoom = 17.6,
      navigationTilt = 60.0,
      browsingPadding = PaddingValues(start = startPanel, top = topCards, bottom = bottom),
      navigationPadding = PaddingValues(start = startPanel, top = top, bottom = bottom),
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
  val routeStanding by viewModel.routeStanding.collectAsState()
  val mapState = rememberNavigationMapState()
  val trafficEvents by viewModel.trafficEvents.collectAsState()
  val trafficAhead by viewModel.trafficAhead.collectAsState()
  var showTurns by rememberSaveable { mutableStateOf(false) }
  val places by viewModel.places.collectAsState()
  val cameraList by viewModel.cameraList.collectAsState()
  val camerasAhead by viewModel.camerasAhead.collectAsState()
  val routeChoice by viewModel.routeChoice.collectAsState()
  val fasterRoute by viewModel.fasterRoute.collectAsState()
  // Grey lines under the route: the other options at the start, or the faster way on offer.
  val altRoutes =
      routeChoice?.let { c -> c.options.filterIndexed { i, _ -> i != c.selected }.map { it.route.geometry } }
          ?: fasterRoute?.let { listOf(it.option.route.geometry) }
          ?: emptyList()
  androidx.activity.compose.BackHandler(enabled = places.open || places.selected != null) {
    if (places.selected != null) viewModel.selectPlace(null) else viewModel.closePlaces()
  }
  val plan by viewModel.plan.collectAsState()
  val saved by viewModel.savedPlaces.collectAsState()
  androidx.activity.compose.BackHandler(enabled = plan.open) {
    if (plan.editing != null || plan.pickingOnMap != null) viewModel.cancelPlanEdit() else viewModel.closePlanner()
  }
  // Ending a trip asks first (from the menu or a tap on the arrival card).
  var confirmEnd by rememberSaveable { mutableStateOf(false) }

  val settings by viewModel.settings.collectAsState()
  var showSettings by rememberSaveable { mutableStateOf(false) }

  // "Hey Drivr": microphone only while Drivr is on screen, permission asked when switched on.
  val voice by viewModel.voice.collectAsState()
  val voiceModel by viewModel.voiceModel.collectAsState()
  val voiceProblem by viewModel.voiceProblem.collectAsState()
  val context = LocalContext.current
  fun micGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
  var micAllowed by remember { mutableStateOf(micGranted()) }
  val micLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micAllowed = granted
        viewModel.onMicPermission(granted)
      }
  fun askMic() {
    val activity = context.findActivity()
    // Denied for good: only the app's system settings can allow it now.
    if (activity != null && !micGranted() && askedMicBefore(context) &&
        !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)) {
      context.startActivity(
          android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)))
    } else {
      markAskedMic(context)
      micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }
  androidx.lifecycle.compose.LifecycleStartEffect(viewModel) {
    micAllowed = micGranted()
    viewModel.onMicPermission(micAllowed)
    viewModel.onAppOnScreen(true)
    onStopOrDispose { viewModel.onAppOnScreen(false) }
  }
  val shareLabel = stringResource(R.string.menu_share_eta)
  fun shareEta() {
    viewModel.shareEtaText()?.let { text ->
      val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
      context.startActivity(android.content.Intent.createChooser(send, shareLabel))
    }
  }
  androidx.activity.compose.BackHandler(enabled = showSettings) { showSettings = false }
  androidx.activity.compose.BackHandler(enabled = showTurns) { showTurns = false }

  LaunchedEffect(viewModel) {
    viewModel.voiceEvents.collect {
      when (it) {
        app.drivr.VoiceEvent.ShareEta -> shareEta()
        app.drivr.VoiceEvent.AskEndTrip -> confirmEnd = true
        app.drivr.VoiceEvent.CloseEndTrip -> confirmEnd = false
      }
    }
  }

  // Music: one button next to the gear while browsing; while driving only via the "..." menu,
  // and only when enabled in Settings.
  val music = app.drivr.AppModule.music
  val musicState by music.state.collectAsState()
  var showMusic by rememberSaveable { mutableStateOf(false) }
  val musicAllowedNow = !uiState.isNavigating() || settings.musicWhileDriving
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
  val navigating = uiState.isNavigating()
  LaunchedEffect(navigating) { if (!navigating) arSnoozed = false }
  val nearManeuver = (uiState.progress?.distanceToNextManeuver ?: Double.MAX_VALUE) < 300.0
  val showAr = settings.arEnabled && navigating && (arManual || (settings.arAuto && !arSnoozed && nearManeuver))

  // Alerts swiped away this trip (by key, see drivingAlertCandidates).
  var dismissedAlerts by rememberSaveable { mutableStateOf(listOf<String>()) }
  LaunchedEffect(navigating) { if (!navigating) dismissedAlerts = emptyList() }

  // Measured overlay sizes, so the camera keeps the car and route clear of the cards.
  val density = LocalDensity.current
  var topCardsH by remember { mutableStateOf(0.dp) }
  var bottomCardsH by remember { mutableStateOf(0.dp) }
  var sidePanelW by remember { mutableStateOf(0.dp) }
  val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  val inOverview = mapState.cameraMode == NavigationCameraMode.OVERVIEW

  fun showPlanOverview() {
    val box = plan.options.flatMap { it.route.geometry }.boundingBox() ?: return
    mapState.showRouteOverview(
        box,
        PaddingValues(start = sidePanelW + 40.dp, end = 80.dp, top = topCardsH + 40.dp, bottom = bottomCardsH + 40.dp),
    )
  }

  fun showOverview() {
    val route = uiState.routeGeometry ?: return
    // With routes to choose from, fit all of them.
    val box = (route + routeChoice?.options.orEmpty().flatMap { it.route.geometry }).boundingBox() ?: return
    mapState.showRouteOverview(
        box,
        PaddingValues(
            start = sidePanelW + 40.dp, end = 80.dp,
            top = (if (landscape) 0.dp else topCardsH) + 40.dp,
            bottom = (if (landscape) 0.dp else bottomCardsH) + 40.dp),
    )
  }

  // Panned the map while driving? Follow the car again after 10 s without touching it, so the
  // car never stays off screen. Not while a places list or the turn list is open.
  val freeCamera = navigating && mapState.cameraMode == NavigationCameraMode.FREE && !places.open && places.selected == null && !showTurns
  LaunchedEffect(freeCamera) {
    if (!freeCamera) return@LaunchedEffect
    androidx.compose.runtime.snapshotFlow { mapState.cameraState.position }.collectLatest {
      kotlinx.coroutines.delay(10_000)
      mapState.recenter(isNavigating = true)
    }
  }

  // Planner routes found: fit them between the fields and the route list.
  LaunchedEffect(viewModel) {
    viewModel.planRoutesReady.collect {
      kotlinx.coroutines.delay(300)
      showPlanOverview()
    }
  }

  // A new trip: show the whole route for a few seconds, then follow the car.
  LaunchedEffect(viewModel) {
    viewModel.routeStarted.collect {
      kotlinx.coroutines.delay(400)
      showOverview()
      kotlinx.coroutines.delay(4000)
      // Keep the whole picture while the route choice is open.
      viewModel.routeChoice.first { it == null }
      if (mapState.cameraMode == NavigationCameraMode.OVERVIEW) mapState.recenter(isNavigating = true)
    }
  }

  val voiceCardShown = voice !is app.drivr.voice.VoiceUi.Off && voice !is app.drivr.voice.VoiceUi.Waiting

  BoxWithConstraints(Modifier.fillMaxSize().background(Drivr.card)) {
    val screenH = maxHeight
    // The map (camera and car) follows the smoothed, every-frame position; everything else keeps
    // reading the real fixes. Only this block recomposes per frame.
    WithSmoothedLocation(uiState) { mapUi ->
    NavigationMapView(
        baseStyle = rememberDrivrStyle(buildings3d = settings.buildings3d),
        navigationMapState = mapState,
        uiState = mapUi,
        // No MapLibre ornaments: they sat under our cards. Attribution is in Settings and on the
        // start card.
        mapOptions = MapOptions(ornamentOptions = OrnamentOptions.AllDisabled),
        routeOverlayBuilder =
            RouteOverlayBuilder(
                navigationPath = {
                  AlternativeRoutesLayer(altRoutes)
                  DrivrRouteLine(uiState)
                }),
        navigationCameraOptions =
            if (landscape) drivingCameraOptions(screenH, bottomCards = 24.dp, startPanel = sidePanelW)
            else drivingCameraOptions(screenH, bottomCards = bottomCardsH, topCards = topCardsH),
        showDefaultPuck = false,
        onMapLongClick = { position, _ ->
          // Plans a route to the point (or sets the start/destination being picked); never starts
          // driving by itself.
          if (!uiState.isNavigating()) viewModel.onMapLongPress(position)
          NavigationMapClickResult.Consume
        },
        content = { state ->
          if (!navigating) {
            val opts = plan.options
            PlanRoutesLayer(
                selected = opts.getOrNull(plan.selected)?.route?.geometry,
                others = opts.filterIndexed { i, _ -> i != plan.selected }.map { it.route.geometry },
            )
            PlanPinsLayer(from = (plan.from as? app.drivr.RouteStart.At)?.place, to = plan.to?.takeIf { plan.open })
          }
          if (settings.trafficOnMap) TrafficLayers(trafficEvents)
          if (settings.speedCameras) CamerasLayer(cameraList)
          PlacesLayer(
              results = places.selected?.let { listOf(it) } ?: places.results,
              selectedId = places.selected?.place?.id,
          )
          ChevronPuck(state)
        },
    )
    }

    if (showAr) {
      ArJunctionView(
          ui = uiState,
          standing = routeStanding,
          onClose = {
            arManual = false
            arSnoozed = true
          },
      )
      return@BoxWithConstraints
    }

    // ---- Slots: everything on top of the map lives in these, so nothing can overlap. ----------
    val banner: @Composable (Modifier) -> Unit = { m ->
      // Wrong way / off route replaces the turn banner: it is the one thing that matters then.
      if (routeStanding !is app.drivr.nav.RouteStanding.OnRoute) {
        RouteStandingCard(routeStanding, uiState.isCalculatingNewRoute == true, m)
      } else uiState.visualInstruction?.let {
        InstructionBanner(
            instruction = it,
            distanceToManeuverMeters = uiState.progress?.distanceToNextManeuver,
            modifier = m,
            then = uiState.nextManeuver(),
            stepLanes = uiState.currentStepLanes(),
        )
      }
    }
    // Camera / traffic alert: under the banner (not over the car), swipe to dismiss. Hidden
    // while a route choice or faster-route offer is on screen, so there is one thing at a time.
    val alert: @Composable () -> Unit = {
      if (uiState.progress != null && routeChoice == null && fasterRoute == null && !voiceCardShown) {
        DrivingAlert(
            camerasAhead.first, camerasAhead.second,
            if (settings.trafficAlerts) trafficAhead else emptyList(),
            dismissed = dismissedAlerts,
            onDismiss = { dismissedAlerts = dismissedAlerts + it },
        )
      }
    }
    val status: @Composable (Modifier) -> Unit = { m ->
      VoiceCard(voice, viewModel::dismissVoice)
      RouteStatusCard(routeStatus, viewModel::retryRoute, viewModel::dismissRouteStatus, m)
    }
    val tripCards: @Composable (Modifier) -> Unit = { m ->
      uiState.progress?.let { progress ->
        // Valhalla's ETA has no live traffic; add NDW's reported delays on the route.
        val delayS = trafficAhead.sumOf { it.event.delaySeconds ?: 0.0 }
        Column(m, verticalArrangement = Arrangement.spacedBy(10.dp)) {
          if (showMusic && musicAllowedNow) musicCard(Modifier)
          val choice = routeChoice
          val faster = fasterRoute
          when {
            // One card at a time above the arrival card: the route choice, a faster-route
            // offer, or the usual camera/traffic alert.
            choice != null ->
                RouteChoiceCard(
                    options = choice.options.map { it.score },
                    selected = choice.selected,
                    onSelect = viewModel::chooseRoute,
                    onGo = viewModel::confirmRouteChoice,
                )
            faster != null ->
                FasterRouteCard(
                    savesSeconds = faster.savesSeconds,
                    via = faster.option.score.via,
                    currentDelaySeconds = faster.currentDelaySeconds,
                    onSwitch = viewModel::switchToFasterRoute,
                    onKeep = viewModel::keepCurrentRoute,
                )
            else -> {}
          }
          TripCards(
              speedLimitKmh = uiState.speedLimitKmh(),
              speedKmh = uiState.location?.speed?.value?.let { (it * 3.6).roundToInt() },
              distanceRemainingM = progress.distanceRemaining,
              durationRemainingS = progress.durationRemaining + delayS,
              trafficDelayS = delayS,
              onEnd = { confirmEnd = true },
          )
        }
      }
    }
    val placesPanel: @Composable (Modifier) -> Unit = { m ->
      val selected = places.selected
      if (selected != null) {
        PlaceDetailCard(
            result = selected,
            navigating = navigating,
            exactDetourS = places.exactDetourS,
            onAddStop = { viewModel.addStop(selected.place) },
            onDrive = { viewModel.driveTo(selected.place) },
            onRoute = { viewModel.openPlanner(to = selected.place) },
            onBack = { viewModel.selectPlace(null) },
            priceCountry = places.priceCountry,
            modifier = m,
        )
      } else {
        PlacesSheet(
            state = places,
            onSelectCategory = viewModel::searchCategory,
            onSelect = viewModel::selectPlace,
            onClose = viewModel::closePlaces,
            priceCountry = places.priceCountry,
            modifier = m,
            onRetry = viewModel::retryPlaces,
            maxListHeight = if (landscape) 150.dp else 330.dp,
        )
      }
    }
    val buttons: @Composable (Modifier) -> Unit = { m ->
      val primary = buildList {
        // Listening for "Hey Drivr": always the first button, so it can't fold into the menu.
        if (voice != app.drivr.voice.VoiceUi.Off) add(MapAction(stringResource(R.string.voice_mic_desc), MapIcon.Mic, viewModel::tapVoice))
        if (navigating) add(MapAction(stringResource(R.string.map_search_route), MapIcon.Search, viewModel::openPlaces))
        if (!mapState.isTrackingUser) add(MapAction(stringResource(R.string.map_follow), MapIcon.Recenter) { mapState.recenter(navigating) })
        else if (navigating) add(MapAction(stringResource(R.string.map_overview), MapIcon.Overview, ::showOverview))
        // Voice on/off in one tap while driving (same switch as Settings › Voice guidance).
        if (navigating) {
          add(
              if (settings.voice) MapAction(stringResource(R.string.map_mute), MapIcon.Sound, viewModel::toggleMute)
              else MapAction(stringResource(R.string.map_unmute), MapIcon.Muted, viewModel::toggleMute))
        }
      }
      val menu = buildList<Pair<String, () -> Unit>> {
        if (navigating) {
          add(stringResource(R.string.menu_turns) to { showTurns = true })
          add(shareLabel to { shareEta() })
          if (settings.arEnabled) add(stringResource(R.string.menu_ar) to { arManual = true })
          if (settings.musicWhileDriving) add(stringResource(R.string.menu_music) to { showMusic = !showMusic })
          add(stringResource(R.string.menu_settings) to { showSettings = true })
          add(stringResource(R.string.menu_end) to { confirmEnd = true })
        }
      }
      if (!places.open && !(plan.open && plan.editing != null)) MapButtonColumn(primary, menu, m)
    }
    val safe = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)
    if (navigating && landscape) {
      SideOverlay(
          safe,
          top = {
            if (!places.open) {
              banner(Modifier)
              alert()
            }
            status(Modifier)
          },
          buttons = buttons,
          bottom = { if (places.open) placesPanel(Modifier) else tripCards(Modifier) },
          onPanelWidth = { sidePanelW = it + 12.dp },
      )
    } else if (navigating) {
      StackedOverlay(
          safe,
          top = {
            banner(Modifier)
            alert()
            status(Modifier)
          },
          buttons = buttons,
          bottom = { if (places.open) placesPanel(Modifier) else tripCards(Modifier) },
          onTopHeight = { topCardsH = it },
          onBottomHeight = { bottomCardsH = it },
      )
    } else if (plan.open) {
      // Route planner: from / to at the top, suggestions or the routes at the bottom.
      StackedOverlay(
          safe,
          top = {
            val editing = plan.editing
            val picking = plan.pickingOnMap
            when {
              picking != null -> MapPickHint(picking, onCancel = viewModel::cancelPlanEdit)
              editing != null -> PlanSearchField(editing, plan.query, viewModel::planSearch, onBack = viewModel::cancelPlanEdit)
              else -> PlanFieldsCard(plan, viewModel::editPlanField, viewModel::swapPlan, viewModel::closePlanner)
            }
            status(Modifier)
          },
          buttons = buttons,
          bottom = {
            val editing = plan.editing
            if (editing != null) {
              PlanSuggestions(
                  plan, saved,
                  onHere = viewModel::choosePlanHere,
                  onPickOnMap = { viewModel.pickPlanOnMap(editing) },
                  onPlace = viewModel::choosePlanPlace,
                  maxListHeight = if (landscape) 150.dp else 420.dp,
              )
            } else if (plan.pickingOnMap == null) {
              PlanRoutesCard(plan, viewModel::selectPlanRoute, viewModel::startPlannedTrip)
            }
          },
          onTopHeight = { topCardsH = it },
          onBottomHeight = { bottomCardsH = it },
      )
    } else {
      StackedOverlay(
          safe,
          top = {
            Row(verticalAlignment = Alignment.CenterVertically) {
              PlacesSearchBar(places.query, viewModel::searchText, onFocus = viewModel::openPlaces, modifier = Modifier.weight(1f))
              if (!places.open) {
                Spacer(Modifier.width(8.dp))
                MusicButton(onClick = { showMusic = !showMusic })
                Spacer(Modifier.width(8.dp))
                SettingsButton(onClick = { showSettings = true })
              }
            }
            if (!places.open) CategoryChips(null, viewModel::searchCategory)
            if (!places.open && showMusic) musicCard(Modifier)
            status(Modifier)
          },
          buttons = buttons,
          bottom = {
            if (places.open) placesPanel(Modifier)
            else
                QuickPanel(
                    saved,
                    onPlan = { viewModel.openPlanner() },
                    onPlace = { viewModel.openPlanner(to = it) },
                    onSetHome = { viewModel.editSavedPlace(app.drivr.PlanField.HOME) },
                    onSetWork = { viewModel.editSavedPlace(app.drivr.PlanField.WORK) },
                    loading = routeStatus is RouteStatus.Loading,
                    onTestDrive = if (settings.showTestDrive) viewModel::startDemoDrive else null,
                )
          },
          onTopHeight = { topCardsH = it },
          onBottomHeight = { bottomCardsH = it },
      )
    }

    if (showTurns && navigating) {
      // Modal: dim the map, so it reads as one layer on top rather than another floating card.
      Box(
          Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { showTurns = false }
              .windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
          contentAlignment = Alignment.Center,
      ) {
        TurnList(
            steps = uiState.remainingSteps.orEmpty(),
            distanceToNextManeuver = uiState.progress?.distanceToNextManeuver ?: 0.0,
            onClose = { showTurns = false },
        )
      }
    }
    if (showSettings) {
      SettingsScreen(
          settings,
          { change ->
            val before = settings.voiceCommands
            viewModel.updateSettings(change)
            // Switched on: ask for the microphone now (not before), then fetch the speech model.
            if (!before && viewModel.settings.value.voiceCommands) {
              if (micGranted()) viewModel.onMicPermission(true) else askMic()
            }
          },
          onClose = { showSettings = false },
          musicAccess = musicState.hasAccess,
          onMusicAccess = { context.startActivity(music.accessSettingsIntent()) },
          language = remember { app.drivr.i18n.AppLanguage.chosen(context) },
          onLanguage = { tag -> context.findActivity()?.let { app.drivr.i18n.AppLanguage.set(it, tag) } },
          saved = saved,
          onEditSaved = { field ->
            showSettings = false
            viewModel.editSavedPlace(field)
          },
          onClearSaved = viewModel::clearSavedPlace,
          onClearRecents = viewModel::clearRecents,
          voice =
              VoiceSettingsState(
                  model = voiceModel,
                  modelMb = app.drivr.voice.SpeechModel.forLanguage(app.drivr.i18n.L10n.locale.language).sizeMb,
                  micAllowed = micAllowed,
                  problem = voiceProblem,
                  downloadedMb = remember(voiceModel, settings.voiceCommands) { viewModel.speechModelsMb() },
                  onAskMic = ::askMic,
                  onDownload = viewModel::downloadSpeechModel,
                  onDelete = viewModel::deleteSpeechModels,
              ),
      )
    }
    if (confirmEnd && navigating) {
      ConfirmEndTrip(
          destination = viewModel.destinationLabel,
          onKeep = {
            confirmEnd = false
            viewModel.onEndTripClosed()
          },
          onEnd = {
            confirmEnd = false
            viewModel.onEndTripClosed()
            viewModel.stopNavigation()
          },
      )
    }
  }
}

/** Lanes for the current maneuver from any of the step's banners (often only the last has them). */
private fun NavigationUiState.currentStepLanes(): List<uniffi.ferrostar.LaneInfo>? =
    remainingSteps?.firstOrNull()?.visualInstructions
        ?.mapNotNull { (it.subContent?.laneInfo ?: it.primaryContent.laneInfo)?.takeIf { l -> l.isNotEmpty() } }
        ?.lastOrNull()

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

/**
 * At most one alert, under the turn banner, the most urgent: inside an average-speed section or
 * a camera close by, then being inside a jam, then the next jam within 30 km. Each can be swiped
 * away; a swiped alert stays away for this trip ([dismissed] holds its key) and the next one in
 * line shows instead. Entering a jam you swiped away earlier shows its countdown once more.
 */
@Composable
fun DrivingAlert(
    cameras: List<app.drivr.cameras.CameraAhead>,
    averageKmh: Double?,
    traffic: List<app.drivr.traffic.TrafficAhead>,
    dismissed: Collection<String> = emptyList(),
    onDismiss: ((String) -> Unit)? = null,
) {
  val pick = drivingAlertCandidates(cameras, traffic).firstOrNull { it.first !in dismissed } ?: return
  val (key, show) = pick
  val card: @Composable () -> Unit = {
    when (show) {
      is app.drivr.cameras.CameraAhead -> CameraAheadCard(show, averageKmh)
      is app.drivr.traffic.TrafficAhead -> if (show.inside) JamCountdownCard(show) else TrafficAheadCard(show)
    }
  }
  if (onDismiss == null) card()
  else SwipeAway(key, onDismiss = { onDismiss(key) }, dismissLabel = stringResource(R.string.alert_dismiss)) { card() }
}

/** Alerts in order of urgency, each with the key it is dismissed by. */
internal fun drivingAlertCandidates(
    cameras: List<app.drivr.cameras.CameraAhead>,
    traffic: List<app.drivr.traffic.TrafficAhead>,
): List<Pair<String, Any>> = buildList {
  cameras.filter { it.inside }.forEach { add("cam-in:${it.camera.id}" to it) }
  cameras.filter { !it.inside && it.distanceM < 1000 }.forEach { add("cam:${it.camera.id}" to it) }
  traffic.filter { it.inside && it.endM != null }.forEach { add("jam-in:${it.event.id}" to it) }
  traffic.filter { !it.inside && it.distanceM < 30_000 }.forEach { add("traffic:${it.event.id}" to it) }
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

private tailrec fun android.content.Context.findActivity(): android.app.Activity? =
    when (this) {
      is android.app.Activity -> this
      is android.content.ContextWrapper -> baseContext.findActivity()
      else -> null
    }

private fun askedMicBefore(context: android.content.Context) =
    context.getSharedPreferences("drivr_settings", android.content.Context.MODE_PRIVATE).getBoolean("askedMic", false)

private fun markAskedMic(context: android.content.Context) =
    context.getSharedPreferences("drivr_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("askedMic", true).apply()
