package app.drivr.ar

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.drivr.R
import app.drivr.nav.RouteStanding
import app.drivr.ui.Drivr
import app.drivr.ui.RouteStandingCard
import app.drivr.ui.Standing
import app.drivr.ui.formatDistance
import com.stadiamaps.ferrostar.composeui.views.components.maneuver.ManeuverImage
import com.stadiamaps.ferrostar.core.NavigationUiState
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.math.max
import uniffi.ferrostar.VisualInstructionContent

/** The route carpet: one strong, saturated blue that reads on any road, day or night. */
internal val CarpetBlue = Color(0xFF1F6BFF)
private val CarpetEdge = Color(0xFFBFD6FF)

/**
 * Half the carpet's width: 2.8 m in all, a bit narrower than a lane, so the lane lines on either
 * side stay visible and a small sideways error doesn't put it over the next lane.
 */
internal const val CARPET_HALF_WIDTH_M = 1.4

/** Where the route is, relative to the car, at the moment of the last GPS fix. */
internal class ArScene(
    /** Route ahead in local metres, 2 m steps, starting at the car (origin). */
    val ahead: List<Enu>,
    /** Metres along [ahead] to the next maneuver. */
    val maneuverAtM: Double?,
    val speedMps: Double,
    /** Compass direction of the route at the car; heading fallback before the sensors settle. */
    val routeHeadingDeg: Double,
    /** When the fix arrived (System.nanoTime), for moving the car on between fixes. */
    val fixNanos: Long,
)

private fun buildScene(ui: NavigationUiState, nowNanos: Long): ArScene? {
  val loc = ui.location ?: return null
  val geometry = ui.routeGeometry ?: return null
  val origin = LatLng(loc.coordinates.lat, loc.coordinates.lng)
  val enu = geometry.map { LatLng(it.lat, it.lng).toEnu(origin) }
  // Rounded corners: a car drives an arc through a junction, not the sharp corner of the OSM way.
  val ahead =
      routeAhead(filletCorners(enu), aheadM = 400.0, stepM = 2.0)
  if (ahead.size < 3) return null
  val routeBearing = bearingDeg(ahead.first(), ahead[minOf(5, ahead.lastIndex)])
  return ArScene(
      ahead = ahead,
      maneuverAtM = ui.progress?.distanceToNextManeuver,
      speedMps = loc.speed?.value ?: 0.0,
      routeHeadingDeg = routeBearing,
      // The fix is already a little old when it gets here (GPS chip, snapping): count from when
      // it was taken, or the car is drawn half a second behind where it is.
      fixNanos = nowNanos - fixAgeNanos(loc.timestamp),
  )
}

/** Age of a fix by its own clock, 0-1.5 s (a phone clock a bit off can't make it silly). */
internal fun fixAgeNanos(taken: java.time.Instant?): Long {
  taken ?: return 0L
  val ms = System.currentTimeMillis() - taken.toEpochMilli()
  return ms.coerceIn(0L, 1500L) * 1_000_000L
}

/**
 * The scene moved on to [nowNanos]: the car advanced along the route at its last known speed
 * (at most 2.5 s worth, so a lost fix doesn't run away), re-centred on the car.
 */
internal fun ArScene.at(nowNanos: Long): Pair<List<Enu>, Double?> {
  val dt = ((nowNanos - fixNanos) / 1e9).coerceIn(0.0, 2.5)
  val s = speedMps * dt
  if (s < 0.05) return ahead to maneuverAtM
  return reOrigin(sliceAlong(ahead, s)) to maneuverAtM?.minus(s)
}

/**
 * AR view: the live camera with the route laid on the road as a solid blue carpet, chevrons
 * flowing along it and a big arrow at the turn.
 *
 * How it stays on the road (no ARCore, no Play Services):
 * - Heading: gyroscope at frame rate, anchored to the route's own direction at the car (the car
 *   drives along the road it is on); GPS course only before there is a route.
 * - Tilt: gravity sensor, slowly averaged and frozen while braking, accelerating or cornering.
 * - Mount: the camera image itself. On straight stretches the road's vanishing point (where lane
 *   lines meet) is straight ahead of the car, so its offset from the image centre is how the phone
 *   is turned and tilted on its mount. That is learned within seconds and corrects the overlay.
 * - Between GPS fixes the car is moved along the route at its speed, so nothing jumps.
 *
 * - The driver can drag the blue lane onto their lane; that is kept for next time.
 * - When the pose can't be right (camera aimed at the dashboard, weak GPS, the route not in
 *   front of the camera), nothing is drawn rather than something wrong.
 *
 * Going the wrong way turns the whole view red with a U-turn sign; leaving the route turns it
 * amber with an arrow towards the route.
 */
@Composable
fun ArJunctionView(
    ui: NavigationUiState,
    standing: RouteStanding,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var hasCamera by remember {
    mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    )
  }
  val launcher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
        if (!it) onClose()
      }
  LaunchedEffect(Unit) { if (!hasCamera) launcher.launch(Manifest.permission.CAMERA) }

  var fovDeg by remember { mutableStateOf(68.0) }
  val engine = remember { ArPoseEngine({ fovDeg }) }
  RegisterPoseSensors(engine)
  // The driver's own alignment survives closing AR and restarting the app: a mount stays put.
  val prefs = remember { context.getSharedPreferences("drivr_ar", android.content.Context.MODE_PRIVATE) }
  LaunchedEffect(engine) {
    if (prefs.contains("yaw")) engine.setManual(prefs.getFloat("yaw", 0f).toDouble(), prefs.getFloat("pitch", 0f).toDouble())
  }

  val scene = remember(ui.location, ui.routeGeometry) { buildScene(ui, System.nanoTime()) }
  LaunchedEffect(scene) {
    scene ?: return@LaunchedEffect
    engine.onFix(ui.location?.courseOverGround?.degrees?.toDouble(), scene.speedMps)
    engine.routeStraightAhead = isStraight(scene.ahead)
  }
  val gpsAccuracy = ui.location?.horizontalAccuracy

  // Frame clock: read only inside the draw lambda, so each frame redraws without recomposing.
  var frameNanos by remember { mutableLongStateOf(System.nanoTime()) }
  LaunchedEffect(Unit) { while (true) withFrameNanos { frameNanos = System.nanoTime() } }

  var viewSize by remember { mutableStateOf(IntSize.Zero) }
  var track by remember { mutableStateOf(ArTrack.FINE_TUNING) }
  LaunchedEffect(gpsAccuracy) {
    while (true) {
      track = engine.track(gpsAccuracy)
      kotlinx.coroutines.delay(400)
    }
  }
  var dragging by remember { mutableStateOf(false) }

  Box(modifier.fillMaxSize().background(Color.Black).onSizeChanged { viewSize = it }) {
    if (hasCamera) CameraWithRoadTracking(engine, onFov = { fovDeg = it }, Modifier.fillMaxSize())

    if (scene != null && viewSize.width > 0) {
      // Drag to slide the blue lane onto your lane (sideways = phone's turn on the mount, up and
      // down = its tilt); double-tap to hand it back to automatic.
      val focalPx = (max(viewSize.width, viewSize.height) / 2.0) / kotlin.math.tan(Math.toRadians(fovDeg) / 2.0)
      Canvas(
          Modifier.fillMaxSize()
              .pointerInput(engine) {
                detectDragGestures(
                    onDragStart = {
                      dragging = true
                      val (y, pb) = engine.offsets()
                      engine.setManual(y, pb)
                    },
                    onDragEnd = {
                      dragging = false
                      engine.manualOffsets()?.let { (y, pb) ->
                        prefs.edit().putFloat("yaw", y.toFloat()).putFloat("pitch", pb.toFloat()).apply()
                      }
                    },
                    onDragCancel = { dragging = false },
                ) { change, d ->
                  change.consume()
                  val (y, pb) = engine.offsets()
                  engine.setManual(
                      y - Math.toDegrees(atan(d.x / focalPx)),
                      pb - Math.toDegrees(atan(d.y / focalPx)),
                  )
                }
              }
              .pointerInput(engine) {
                detectTapGestures(
                    onDoubleTap = {
                      engine.clearManual()
                      prefs.edit().remove("yaw").remove("pitch").apply()
                    })
              }) {
        val now = frameNanos
        // For the moment the camera picture on screen was taken, like the pose.
        val (raw, maneuverAt) = scene.at(now - (engine.cameraLatencyS * 1e9).toLong())
        val routeHeading = routeHeadingAtCar(raw, scene.routeHeadingDeg)
        engine.routeLine = raw
        // Onto the lane the camera sees.
        val line = shiftSideways(raw, routeHeading, engine.laneShiftM())
        val projector = ArProjector(engine.pose(routeHeading), size.width, size.height, fovDeg)
        val t = if (dragging) ArTrack.MANUAL else track
        drawArRoute(line, maneuverAt, standing, projector, phaseM = (now / 1e9 * 9.0) % CHEVRON_GAP_M, track = t)
        if (dragging) drawAlignGuide(projector, line)
      }
    }
    if (standing !is RouteStanding.OnRoute) StandingTint(standing)

    ArChrome(
        maneuver = ui.visualInstruction?.primaryContent,
        distanceToManeuver = ui.progress?.distanceToNextManeuver,
        standing = standing,
        rerouting = ui.isCalculatingNewRoute == true,
        track = track,
        dragging = dragging,
        cameraOk = hasCamera,
        onClose = onClose,
    )
  }
}

/** Gravity, gyroscope -> [engine], while in composition. */
@Composable
private fun RegisterPoseSensors(engine: ArPoseEngine) {
  val tilt by rememberDeviceTilt()
  LaunchedEffect(tilt) { engine.onTilt(tilt) }
  val context = LocalContext.current
  DisposableEffect(engine) {
    val sm = context.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
    val gyro = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)
    val gravitySensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)
    val g = floatArrayOf(0f, 0f, 9.81f)
    var lastNs = 0L
    val listener =
        object : android.hardware.SensorEventListener {
          override fun onSensorChanged(e: android.hardware.SensorEvent) {
            when (e.sensor.type) {
              android.hardware.Sensor.TYPE_GRAVITY -> e.values.copyInto(g, 0, 0, 3)
              android.hardware.Sensor.TYPE_GYROSCOPE -> {
                if (lastNs != 0L) {
                  val rate = headingRateDegPerS(g[0], g[1], g[2], e.values[0], e.values[1], e.values[2])
                  engine.onGyro(rate, (e.timestamp - lastNs) / 1e9)
                  engine.onTiltRate(tiltRateDegPerS(g[0], g[1], g[2], e.values[0], e.values[1], e.values[2]))
                }
                lastNs = e.timestamp
              }
            }
          }

          override fun onAccuracyChanged(s: android.hardware.Sensor?, accuracy: Int) {}
        }
    gravitySensor?.let { sm.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME) }
    gyro?.let { sm.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME) }
    onDispose { sm.unregisterListener(listener) }
  }
}

/**
 * Camera preview plus a small analysis stream (about 5 frames a second, 640x480) in which we find
 * the road's vanishing point and feed it to [engine].
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
private fun CameraWithRoadTracking(engine: ArPoseEngine, onFov: (Double) -> Unit, modifier: Modifier) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val executor = remember { Executors.newSingleThreadExecutor() }
  DisposableEffect(Unit) { onDispose { executor.shutdown() } }
  AndroidView(
      modifier = modifier,
      factory = { ctx ->
        val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener(
            {
              val provider = providerFuture.get()
              // Preview and analysis both 4:3 (the full sensor): the pose maths assumes both see the
              // same field of view along the long side.
              val fourThree =
                  androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                      .setAspectRatioStrategy(
                          androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                      .build()
              val preview =
                  Preview.Builder().setResolutionSelector(fourThree).build().also { it.surfaceProvider = view.surfaceProvider }
              val estimator = VanishingPointEstimator()
              val laneDetector = LaneDetector()
              var lastMs = 0L
              val analysis =
                  ImageAnalysis.Builder()
                      .setResolutionSelector(
                          androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                              .setAspectRatioStrategy(
                                  androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                              .setResolutionStrategy(
                                  androidx.camera.core.resolutionselector.ResolutionStrategy(
                                      android.util.Size(640, 480),
                                      androidx.camera.core.resolutionselector.ResolutionStrategy
                                          .FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                              .build())
                      .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                      .build()
              analysis.setAnalyzer(executor) { image ->
                image.use {
                  val now = System.currentTimeMillis()
                  if (now - lastMs < 180) return@use
                  lastMs = now
                  val plane = it.planes[0]
                  val buf = plane.buffer
                  val bytes = ByteArray(buf.remaining()).also { b -> buf.get(b) }
                  val img = uprightLuma(bytes, it.width, it.height, plane.rowStride, it.imageInfo.rotationDegrees)
                  estimator.estimate(img)?.let { vp ->
                    engine.onVanishingPoint(vp, img.width.toFloat() / img.height)
                  }
                  // Lane lines, laid out along the route with the pose we draw with.
                  val pose = engine.peekPose()
                  val heading = engine.routeHeading()
                  if (pose != null && heading != null) {
                    engine.onLane(laneDetector.detect(img, pose, engine.fovDeg(), heading, engine.routeLine, engine.laneWidthM()))
                  }
                }
              }
              provider.unbindAll()
              val camera =
                  provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
              // Real field of view from the lens and sensor, instead of a typical value.
              runCatching {
                    val info = Camera2CameraInfo.from(camera.cameraInfo)
                    val focal =
                        info.getCameraCharacteristic(
                                android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            ?.firstOrNull()
                    val sensor =
                        info.getCameraCharacteristic(
                            android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    // Only the active part of the sensor makes the picture.
                    val pixels =
                        info.getCameraCharacteristic(
                            android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    val active =
                        info.getCameraCharacteristic(
                            android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    val activeFraction =
                        if (pixels != null && active != null && pixels.width > 0)
                            (active.width().toFloat() / pixels.width).coerceIn(0.5f, 1f)
                        else 1f
                    if (focal != null && sensor != null && focal > 0f) {
                      val long = max(sensor.width, sensor.height) * activeFraction
                      Math.toDegrees(2.0 * atan(long / (2.0 * focal)))
                    } else null
                  }
                  .getOrNull()
                  ?.takeIf { it in 40.0..110.0 }
                  ?.let(onFov)
            },
            ContextCompat.getMainExecutor(context),
        )
        view
      },
      onRelease = { ProcessCameraProvider.getInstance(context).get().unbindAll() },
  )
}

// ---- Drawing ---------------------------------------------------------------------------------

internal const val CHEVRON_GAP_M = 9.0

/**
 * Closer than this the road is hidden by the bonnet and dashboard for a phone on the windscreen:
 * painting there puts blue over the dashboard instead of the road.
 */
internal const val CARPET_START_M = 7.0

/** The carpet fades in over this stretch after [CARPET_START_M], so it never ends in a hard edge. */
private const val CARPET_FADE_M = 7.0

/** The whole route overlay for one frame. [line] starts at the car. */
internal fun DrawScope.drawArRoute(
    line: List<Enu>,
    maneuverAtM: Double?,
    standing: RouteStanding,
    p: ArProjector,
    phaseM: Double,
    track: ArTrack = ArTrack.LOCKED,
) {
  when (standing) {
    // Wrong way: the route is behind; painting it ahead would say "carry on". The tint and the
    // U-turn card do the talking.
    is RouteStanding.WrongWay -> return
    // Off route: the old route ahead of the snapped position may be on another road entirely.
    // The card's arrow shows where the route is; a new one is on its way.
    is RouteStanding.OffRoute -> return
    RouteStanding.OnRoute -> {}
  }
  if (!track.draws) return
  if (!routeIsInView(line, p)) return
  // While the mount isn't learned, a degree or two of yaw is a lane's width at 60 m: keep the
  // carpet to the part we can trust.
  val reach = if (track == ArTrack.FINE_TUNING) 80.0 else 150.0
  // Past the turn only a short stub into the new road; the rest would lie along the horizon.
  val carpetTo = minOf(reach, maneuverAtM?.plus(14.0) ?: reach)
  drawCarpet(line, p, CarpetBlue, alphaNear = 0.6f, fromM = CARPET_START_M, toM = carpetTo)
  drawChevrons(line, p, phaseM, until = minOf(maneuverAtM?.minus(8.0) ?: 70.0, 70.0, reach))
  if (maneuverAtM != null && maneuverAtM > CARPET_START_M && maneuverAtM < 300.0) {
    if (maneuverAtM < 70.0) drawTurnArrow(line, maneuverAtM, p)
    drawTurnSign(line, maneuverAtM, p)
  }
}

/**
 * Sanity check before drawing anything: the route 12-20 m ahead must land in the lower half of the
 * view and roughly in front. If it doesn't, the pose is wrong (or the phone isn't looking down the
 * road) and a carpet would only mislead, so nothing is drawn.
 */
internal fun DrawScope.routeIsInView(line: List<Enu>, p: ArProjector): Boolean {
  val probe = pointAndDirectionAt(line, 15.0)?.first ?: line.lastOrNull() ?: return false
  val s = p.project(probe, 1.0) ?: return false
  return s.x in -0.25f * size.width..1.25f * size.width && s.y in 0.2f * size.height..1.4f * size.height
}

/**
 * The carpet: a band on the road surface from just beyond the dashboard to [toM], fading in at
 * the near end and out towards the horizon, with bright edges.
 */
internal fun DrawScope.drawCarpet(
    line: List<Enu>,
    p: ArProjector,
    color: Color,
    alphaNear: Float,
    fromM: Double = CARPET_START_M,
    toM: Double = 150.0,
) {
  val visible = sliceAlong(line, fromM, toM)
  if (visible.size < 2) return
  val (left, right) = ribbonEdges(densify(visible, 1.5), CARPET_HALF_WIDTH_M)
  val l = ArrayList<ScreenPoint>()
  val r = ArrayList<ScreenPoint>()
  for (i in left.indices) {
    val a = p.project(left[i], nearPlaneM = 1.0) ?: break
    val b = p.project(right[i], nearPlaneM = 1.0) ?: break
    l += a
    r += b
  }
  if (l.size < 2) return
  val n = l.lastIndex
  val ribbon =
      Path().apply {
        moveTo(l[0].x, l[0].y)
        for (i in 1..n) lineTo(l[i].x, l[i].y)
        for (i in n downTo 0) lineTo(r[i].x, r[i].y)
        close()
      }
  val nearY = max(l[0].y, r[0].y)
  val farY = minOf(l.minOf { it.y }, r.minOf { it.y })
  // Where the fade-in ends, on screen.
  val fadeY = pointAndDirectionAt(line, fromM + CARPET_FADE_M)?.first?.let { p.project(it, 1.0)?.y } ?: nearY
  val span = (nearY - farY).coerceAtLeast(1f)
  val fadeStop = ((fadeY - farY) / span).coerceIn(0.05f, 0.98f)
  drawPath(
      ribbon,
      Brush.verticalGradient(
          0f to color.copy(alpha = alphaNear * 0.4f),
          fadeStop to color.copy(alpha = alphaNear),
          1f to color.copy(alpha = 0f),
          startY = farY,
          endY = nearY,
      ),
  )
  for (edge in listOf(l, r)) {
    val path = Path().apply {
      moveTo(edge[0].x, edge[0].y)
      for (i in 1..n) lineTo(edge[i].x, edge[i].y)
    }
    drawPath(
        path,
        Brush.verticalGradient(
            0f to CarpetEdge.copy(alpha = 0.5f),
            fadeStop to CarpetEdge,
            1f to CarpetEdge.copy(alpha = 0f),
            startY = farY,
            endY = nearY,
        ),
        style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round),
    )
  }
}

/** Points every [stepM] or closer, so curves stay round once projected. */
private fun densify(line: List<Enu>, stepM: Double): List<Enu> {
  val out = ArrayList<Enu>(line.size * 2)
  for (i in 0 until line.size - 1) {
    val a = line[i]
    val b = line[i + 1]
    val d = hypot(b.x - a.x, b.y - a.y)
    val k = max(1, (d / stepM).toInt())
    for (j in 0 until k) out += a + (b - a) * (j.toDouble() / k)
  }
  out += line.last()
  return out
}

/**
 * White chevrons painted flat on the carpet, flowing forward so the direction is obvious. Only
 * where they are big enough to read: far away they'd squash into a pile of white dashes.
 */
private fun DrawScope.drawChevrons(line: List<Enu>, p: ArProjector, phaseM: Double, until: Double) {
  var s = CARPET_START_M + 5.0 + phaseM
  var lastY = Float.MAX_VALUE
  val minGapPx = 26.dp.toPx()
  while (s < until) {
    val (c, dir) = pointAndDirectionAt(line, s) ?: break
    val nrm = Enu(-dir.y, dir.x)
    val tip = p.project(c + dir * 1.0, 1.0)
    val lw = p.project(c - dir * 0.6 + nrm * 0.85, 1.0)
    val rw = p.project(c - dir * 0.6 - nrm * 0.85, 1.0)
    if (tip == null || lw == null || rw == null) break
    if (lastY - tip.y < minGapPx) break
    lastY = tip.y
    val width = (p.pixelsPerMetreAt(tip.depth) * 0.34f).coerceIn(2.5f, 22f)
    // Fade in after the carpet's own fade, and towards the far end.
    val nearFade = ((s - CARPET_START_M - 3.0) / 6.0).coerceIn(0.0, 1.0).toFloat()
    val farFade = (1f - ((s - CARPET_START_M) / (until - CARPET_START_M)).toFloat() * 0.5f).coerceIn(0.4f, 1f)
    drawPath(
        Path().apply {
          moveTo(lw.x, lw.y)
          lineTo(tip.x, tip.y)
          lineTo(rw.x, rw.y)
        },
        Color.White.copy(alpha = 0.9f * nearFade * farFade),
        style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    s += CHEVRON_GAP_M
  }
}

/** Signed turn at the maneuver, ° (positive right), from the route itself. */
internal fun turnAngleDeg(line: List<Enu>, maneuverAtM: Double): Double {
  val before = pointAndDirectionAt(line, maxOf(0.0, maneuverAtM - 8.0)) ?: return 0.0
  val after = pointAndDirectionAt(line, maneuverAtM + 12.0) ?: pointAndDirectionAt(line, lengthM(line) - 0.5) ?: return 0.0
  val b0 = Math.toDegrees(atan2(before.second.x, before.second.y))
  val b1 = Math.toDegrees(atan2(after.second.x, after.second.y))
  return angleDiffDeg(b1, b0)
}

/**
 * Close to the turn: a thick white arrow painted on the carpet through the maneuver (from 8 m
 * before it into the new road).
 */
private fun DrawScope.drawTurnArrow(line: List<Enu>, maneuverAtM: Double, p: ArProjector) {
  val from = maxOf(CARPET_START_M + 3.0, maneuverAtM - 8.0)
  val to = maneuverAtM + 10.0
  val shaft = sliceAlong(line, from, to - 3.0)
  if (shaft.size < 2) return
  val pts = densify(shaft, 0.8).mapNotNull { p.project(it, 1.0) }
  if (pts.size < 2) return
  val path = Path().apply {
    moveTo(pts[0].x, pts[0].y)
    pts.drop(1).forEach { lineTo(it.x, it.y) }
  }
  val w = (p.pixelsPerMetreAt(pts.last().depth) * 0.7f).coerceIn(4f, 60f)
  drawPath(path, Color.White.copy(alpha = 0.95f), style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
  pointAndDirectionAt(line, to - 3.0)?.let { (c, dir) ->
    val nrm = Enu(-dir.y, dir.x)
    val tip = p.project(c + dir * 3.0, 1.0)
    val l = p.project(c + nrm * 1.2, 1.0)
    val r = p.project(c - nrm * 1.2, 1.0)
    if (tip != null && l != null && r != null) {
      drawPath(Path().apply { moveTo(l.x, l.y); lineTo(tip.x, tip.y); lineTo(r.x, r.y); close() }, Color.White)
    }
  }
}

/**
 * The turn as a sign standing at the corner: a blue tile with the turn arrow, on a thin post down
 * to the road. Flat paint at 100 m is a few pixels tall and reads as a line on the horizon; a
 * sign stays readable from far away and still shows exactly which corner it is.
 */
private fun DrawScope.drawTurnSign(line: List<Enu>, maneuverAtM: Double, p: ArProjector) {
  val corner = pointAndDirectionAt(line, maneuverAtM)?.first ?: return
  val angle = turnAngleDeg(line, maneuverAtM)
  if (abs(angle) < 12.0) return
  val foot = p.project(corner, 1.0) ?: return
  val top = p.project(corner + Enu(0.0, 0.0, 3.2), 1.0) ?: return
  val side = (p.pixelsPerMetreAt(foot.depth) * 2.2f).coerceIn(48.dp.toPx(), 104.dp.toPx())
  // Tile centre: above the post, nudged so it stays on screen.
  val half = side / 2f
  val cx = top.x.coerceIn(half + 8f, size.width - half - 8f)
  val cy = (top.y - half).coerceIn(half + 8f, size.height - half - 8f)
  // Fade in from 300 m, full from 200 m.
  val alpha = ((300.0 - maneuverAtM) / 100.0).coerceIn(0.0, 1.0).toFloat()
  drawLine(Color.White.copy(alpha = 0.85f * alpha), Offset(cx, cy + half), Offset(foot.x, foot.y), 2.5.dp.toPx(), cap = StrokeCap.Round)
  drawCircle(Color.White.copy(alpha = alpha), radius = 3.5.dp.toPx(), center = Offset(foot.x, foot.y))
  val corner2 = androidx.compose.ui.geometry.CornerRadius(side * 0.22f)
  drawRoundRect(CarpetBlue.copy(alpha = alpha), Offset(cx - half, cy - half), androidx.compose.ui.geometry.Size(side, side), corner2)
  drawRoundRect(Color.White.copy(alpha = alpha), Offset(cx - half, cy - half), androidx.compose.ui.geometry.Size(side, side), corner2, style = Stroke(2.dp.toPx()))
  drawTurnGlyph(Offset(cx, cy), side * 0.62f, angle, Color.White.copy(alpha = alpha))
}

/** A turn arrow: up from the bottom, then bending to [angleDeg] (positive right). */
internal fun DrawScope.drawTurnGlyph(c: Offset, s: Float, angleDeg: Double, color: Color) {
  val a = Math.toRadians(angleDeg.coerceIn(-150.0, 150.0))
  val bend = Offset(c.x - (sin(a) * s * 0.18).toFloat(), c.y + s * 0.05f)
  val start = Offset(bend.x, c.y + s * 0.5f)
  val dx = sin(a).toFloat()
  val dy = -cos(a).toFloat()
  val end = Offset(bend.x + dx * s * 0.42f, bend.y + dy * s * 0.42f)
  val w = s * 0.16f
  drawPath(
      Path().apply { moveTo(start.x, start.y); lineTo(bend.x, bend.y); lineTo(end.x, end.y) },
      color,
      style = Stroke(w, cap = StrokeCap.Butt, join = StrokeJoin.Round),
  )
  val nx = -dy
  val ny = dx
  val hs = s * 0.24f
  drawPath(
      Path().apply {
        moveTo(end.x + dx * hs * 0.9f, end.y + dy * hs * 0.9f)
        lineTo(end.x + nx * hs, end.y + ny * hs)
        lineTo(end.x - nx * hs, end.y - ny * hs)
        close()
      },
      color,
  )
}

/** While the driver drags to line up: the horizon and the lane the overlay thinks it is in. */
internal fun DrawScope.drawAlignGuide(p: ArProjector, line: List<Enu>) {
  val dir = pointAndDirectionAt(line, 10.0)?.second ?: return
  val h0 = p.project(Enu(dir.x * 5000.0 + dir.y * 3000.0, dir.y * 5000.0 - dir.x * 3000.0, 1.25), 1.0)
  val h1 = p.project(Enu(dir.x * 5000.0 - dir.y * 3000.0, dir.y * 5000.0 + dir.x * 3000.0, 1.25), 1.0)
  if (h0 != null && h1 != null) {
    drawLine(Color.White.copy(alpha = 0.7f), Offset(h0.x, h0.y), Offset(h1.x, h1.y), 1.5.dp.toPx(),
        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(18f, 14f)))
  }
  for (side in listOf(CARPET_HALF_WIDTH_M, -CARPET_HALF_WIDTH_M)) {
    val n = Enu(-dir.y, dir.x)
    val a = p.project(dir * CARPET_START_M + n * side, 1.0) ?: continue
    val b = p.project(dir * 200.0 + n * side, 1.0) ?: continue
    drawLine(Color.White, Offset(a.x, a.y), Offset(b.x, b.y), 2.dp.toPx())
  }
}

/** Red (wrong way) or amber (off route) wash over the camera, strongest at the edges. */
@Composable
internal fun StandingTint(standing: RouteStanding) {
  val c = if (standing is RouteStanding.WrongWay) Standing.wrongWay else Standing.offRoute
  val strength = if (standing is RouteStanding.WrongWay) 0.6f else 0.35f
  // The middle stays clear: the driver has to see the road to turn around.
  Canvas(Modifier.fillMaxSize()) {
    drawRect(
        Brush.radialGradient(
            0.35f to Color.Transparent,
            1f to c.copy(alpha = strength),
            center = center,
            radius = maxOf(size.width, size.height) * 0.62f,
        ))
  }
}

// ---- Chrome: the few things over the camera ------------------------------------------------

/** Top: the next turn (or the wrong-way / off-route card). Bottom: alignment status and Map. */
@Composable
internal fun ArChrome(
    maneuver: VisualInstructionContent?,
    distanceToManeuver: Double?,
    standing: RouteStanding,
    rerouting: Boolean,
    track: ArTrack,
    cameraOk: Boolean,
    dragging: Boolean = false,
    onClose: () -> Unit,
) {
  Box(Modifier.fillMaxSize()) {
    Column(Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth()) {
      if (standing !is RouteStanding.OnRoute) {
        RouteStandingCard(standing, rerouting)
      } else if (maneuver != null) {
        ArTurnCard(maneuver, distanceToManeuver)
      }
    }
    if (standing is RouteStanding.WrongWay) {
      // Unmissable: a big U-turn sign in the middle of the view as well as the card.
      Box(
          Modifier.align(Alignment.Center).size(132.dp).clip(CircleShape).background(Standing.wrongWay),
          contentAlignment = Alignment.Center,
      ) {
        app.drivr.ui.UTurnIcon(Color.White, 92.dp)
      }
    }
    Row(
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(Modifier.weight(1f)) {
      Row(
          Modifier.clip(RoundedCornerShape(50)).background(Drivr.card.copy(alpha = 0.85f)).padding(horizontal = 14.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        val good = track == ArTrack.LOCKED || track == ArTrack.MANUAL || track == ArTrack.ON_LANE
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (good && cameraOk) Drivr.ok else Drivr.hint))
        Spacer(Modifier.width(8.dp))
        Text(
            when {
              !cameraOk -> stringResource(R.string.ar_no_camera)
              dragging -> stringResource(R.string.ar_dragging)
              track == ArTrack.AIM_AT_ROAD -> stringResource(R.string.ar_aim_at_road)
              track == ArTrack.WEAK_GPS -> stringResource(R.string.ar_weak_gps)
              track == ArTrack.MANUAL -> stringResource(R.string.ar_manual)
              track == ArTrack.ON_LANE -> stringResource(R.string.ar_on_lane)
              track == ArTrack.LOCKED -> stringResource(R.string.ar_locked)
              else -> stringResource(R.string.ar_fine_tuning)
            },
            color = Color.White,
            fontSize = 14.sp,
            maxLines = 2,
        )
      }
      }
      Spacer(Modifier.width(12.dp))
      Text(
          stringResource(R.string.ar_map),
          color = Color.White,
          fontSize = 17.sp,
          fontWeight = FontWeight.Bold,
          modifier =
              Modifier.clip(RoundedCornerShape(50))
                  .background(Drivr.accent)
                  .clickable(onClick = onClose)
                  .padding(horizontal = 22.dp, vertical = 12.dp),
      )
    }
  }
}

/** One big, simple card: arrow, distance, where to. */
@Composable
internal fun ArTurnCard(maneuver: VisualInstructionContent, distanceToManeuver: Double?) {
  Row(
      Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(Drivr.cardRadius))
          .background(Drivr.card.copy(alpha = 0.92f))
          .padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        Modifier.size(60.dp).clip(RoundedCornerShape(14.dp)).background(CarpetBlue).padding(6.dp),
    ) {
      ManeuverImage(maneuver, tint = Color.White)
    }
    Spacer(Modifier.width(14.dp))
    Column {
      Text(
          formatDistance(distanceToManeuver ?: 0.0),
          color = Color.White,
          fontSize = 34.sp,
          fontWeight = FontWeight.Black,
          lineHeight = 36.sp,
      )
      Text(
          maneuver.text,
          color = Drivr.textMuted,
          fontSize = 17.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
