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
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import uniffi.ferrostar.VisualInstructionContent

/** The route carpet: one strong, saturated blue that reads on any road, day or night. */
internal val CarpetBlue = Color(0xFF1F6BFF)
private val CarpetEdge = Color(0xFFBFD6FF)

/** Half the carpet's width: a full lane (3.5 m) so it covers the lane the car is in. */
private const val CARPET_HALF_WIDTH_M = 1.75

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
  val ahead = routeAhead(enu, aheadM = 400.0, stepM = 2.0)
  if (ahead.size < 3) return null
  val routeBearing = bearingDeg(ahead.first(), ahead[minOf(5, ahead.lastIndex)])
  return ArScene(
      ahead = ahead,
      maneuverAtM = ui.progress?.distanceToNextManeuver,
      speedMps = loc.speed?.value ?: 0.0,
      routeHeadingDeg = routeBearing,
      fixNanos = nowNanos,
  )
}

/**
 * The scene moved on to [nowNanos]: the car advanced along the route at its last known speed
 * (at most 1.5 s worth, so a lost fix doesn't run away), re-centred on the car.
 */
internal fun ArScene.at(nowNanos: Long): Pair<List<Enu>, Double?> {
  val dt = ((nowNanos - fixNanos) / 1e9).coerceIn(0.0, 1.5)
  val s = speedMps * dt
  if (s < 0.05) return ahead to maneuverAtM
  return reOrigin(sliceAlong(ahead, s)) to maneuverAtM?.minus(s)
}

/**
 * AR view: the live camera with the route laid on the road as a solid blue carpet, chevrons
 * flowing along it and a big arrow at the turn.
 *
 * How it stays on the road (no ARCore, no Play Services):
 * - Heading: gyroscope at frame rate, corrected by the GPS course.
 * - Tilt: gravity sensor.
 * - Mount: the camera image itself. On straight stretches the road's vanishing point (where lane
 *   lines meet) is straight ahead of the car, so its offset from the image centre is how the phone
 *   is turned and tilted on its mount. That is learned within seconds and corrects the overlay.
 * - Between GPS fixes the car is moved along the route at its speed, so nothing jumps.
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
  val engine = remember { ArPoseEngine { fovDeg } }
  RegisterPoseSensors(engine)

  val scene = remember(ui.location, ui.routeGeometry) { buildScene(ui, System.nanoTime()) }
  LaunchedEffect(scene) {
    scene ?: return@LaunchedEffect
    engine.onFix(ui.location?.courseOverGround?.degrees?.toDouble(), scene.speedMps)
    engine.routeStraightAhead = isStraight(scene.ahead)
  }

  // Frame clock: read only inside the draw lambda, so each frame redraws without recomposing.
  var frameNanos by remember { mutableLongStateOf(System.nanoTime()) }
  LaunchedEffect(Unit) { while (true) withFrameNanos { frameNanos = System.nanoTime() } }

  var viewSize by remember { mutableStateOf(IntSize.Zero) }
  var locked by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    while (true) {
      locked = engine.mountState().locked
      kotlinx.coroutines.delay(500)
    }
  }

  Box(modifier.fillMaxSize().background(Color.Black).onSizeChanged { viewSize = it }) {
    if (hasCamera) CameraWithRoadTracking(engine, onFov = { fovDeg = it }, Modifier.fillMaxSize())

    if (scene != null && viewSize.width > 0) {
      Canvas(Modifier.fillMaxSize()) {
        val now = frameNanos
        val projector =
            ArProjector(engine.pose(scene.routeHeadingDeg), size.width, size.height, fovDeg)
        val (line, maneuverAt) = scene.at(now)
        drawArRoute(line, maneuverAt, standing, projector, phaseM = (now / 1e9 * 9.0) % CHEVRON_GAP_M)
      }
    }
    if (standing !is RouteStanding.OnRoute) StandingTint(standing)

    ArChrome(
        maneuver = ui.visualInstruction?.primaryContent,
        distanceToManeuver = ui.progress?.distanceToNextManeuver,
        standing = standing,
        rerouting = ui.isCalculatingNewRoute == true,
        locked = locked,
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
              val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
              val estimator = VanishingPointEstimator()
              var lastMs = 0L
              val analysis =
                  ImageAnalysis.Builder()
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
                    if (focal != null && sensor != null && focal > 0f) {
                      val long = max(sensor.width, sensor.height)
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

/** The whole route overlay for one frame. [line] starts at the car. */
internal fun DrawScope.drawArRoute(
    line: List<Enu>,
    maneuverAtM: Double?,
    standing: RouteStanding,
    p: ArProjector,
    phaseM: Double,
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
  drawCarpet(line, p, CarpetBlue, alphaNear = 0.62f)
  drawChevrons(line, p, phaseM, until = maneuverAtM?.minus(6.0))
  if (maneuverAtM != null && maneuverAtM > 4.0) drawTurnArrow(line, maneuverAtM, p)
}

/**
 * The carpet: a lane-wide band on the road surface from just in front of the bonnet to where the
 * route leaves the view, strong near the car and fading towards the horizon, with bright edges.
 */
internal fun DrawScope.drawCarpet(line: List<Enu>, p: ArProjector, color: Color, alphaNear: Float) {
  // Start 2.5 m ahead: closer than that is under the bonnet.
  val visible = sliceAlong(line, 2.5, 380.0)
  if (visible.size < 2) return
  val (left, right) = ribbonEdges(densify(visible, 2.0), CARPET_HALF_WIDTH_M)
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
  drawPath(
      ribbon,
      Brush.verticalGradient(
          listOf(color.copy(alpha = alphaNear * 0.55f), color.copy(alpha = alphaNear)),
          startY = farY,
          endY = nearY,
      ),
  )
  for (edge in listOf(l, r)) {
    val path = Path().apply {
      moveTo(edge[0].x, edge[0].y)
      for (i in 1..n) lineTo(edge[i].x, edge[i].y)
    }
    drawPath(path, if (color == CarpetBlue) CarpetEdge else color, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
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

/** White chevrons painted flat on the carpet, flowing forward so the direction is obvious. */
private fun DrawScope.drawChevrons(line: List<Enu>, p: ArProjector, phaseM: Double, until: Double?) {
  val end = minOf(until ?: 140.0, 140.0)
  var s = 6.0 + phaseM
  while (s < end) {
    val (c, dir) = pointAndDirectionAt(line, s) ?: break
    val nrm = Enu(-dir.y, dir.x)
    val tip = p.project(c + dir * 1.1, 1.0)
    val lw = p.project(c - dir * 0.6 + nrm * 1.05, 1.0)
    val rw = p.project(c - dir * 0.6 - nrm * 1.05, 1.0)
    if (tip != null && lw != null && rw != null) {
      val width = (p.pixelsPerMetreAt(tip.depth) * 0.42f).coerceIn(1.5f, 26f)
      // Fade the far ones a little so the carpet doesn't turn into stripes at the horizon.
      val alpha = (1f - (s / end).toFloat() * 0.5f).coerceIn(0.35f, 0.95f)
      drawPath(
          Path().apply {
            moveTo(lw.x, lw.y)
            lineTo(tip.x, tip.y)
            lineTo(rw.x, rw.y)
          },
          Color.White.copy(alpha = alpha),
          style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round),
      )
    }
    s += CHEVRON_GAP_M
  }
}

/**
 * The turn: a thick white arrow painted on the road that follows the route through the maneuver
 * (from 10 m before it to 16 m after), plus upright arrows standing in the road after the turn,
 * which stay readable when the road surface near the horizon is only a few pixels tall.
 */
private fun DrawScope.drawTurnArrow(line: List<Enu>, maneuverAtM: Double, p: ArProjector) {
  val from = maxOf(3.0, maneuverAtM - 10.0)
  val to = maneuverAtM + 16.0
  val shaft = sliceAlong(line, from, to - 4.0)
  if (shaft.size >= 2) {
    val pts = densify(shaft, 1.0).mapNotNull { p.project(it, 1.0) }
    if (pts.size >= 2) {
      val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        pts.drop(1).forEach { lineTo(it.x, it.y) }
      }
      val w = (p.pixelsPerMetreAt(pts.last().depth) * 1.1f).coerceIn(6f, 90f)
      drawPath(path, CarpetBlue.copy(alpha = 0.9f), style = Stroke(w * 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
      drawPath(path, Color.White, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
  }
  // Arrow head, flat on the road.
  pointAndDirectionAt(line, to - 4.0)?.let { (c, dir) ->
    val nrm = Enu(-dir.y, dir.x)
    val tip = p.project(c + dir * 4.0, 1.0)
    val l = p.project(c + nrm * 1.6, 1.0)
    val r = p.project(c - nrm * 1.6, 1.0)
    if (tip != null && l != null && r != null) {
      val head = Path().apply {
        moveTo(l.x, l.y)
        lineTo(tip.x, tip.y)
        lineTo(r.x, r.y)
        close()
      }
      drawPath(head, Color.White)
      drawPath(head, CarpetBlue, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
    }
  }
  drawTurnBillboards(line, maneuverAtM, p)
}

private fun DrawScope.drawTurnBillboards(line: List<Enu>, maneuverAtM: Double, p: ArProjector) {
  val eye = Enu(0.0, 0.0, 1.4)
  for (k in 0 until 3) {
    val s = maneuverAtM + 8.0 + k * 9.0
    val (c, dir) = pointAndDirectionAt(line, s) ?: return
    val a = p.project(c + eye) ?: continue
    val b = p.project(c + dir * 2.0 + eye) ?: continue
    var dx = b.x - a.x
    var dy = b.y - a.y
    val len = hypot(dx, dy).takeIf { it > 1e-3f } ?: continue
    dx /= len
    dy /= len
    val sizePx = maxOf(p.pixelsPerMetreAt(a.depth) * 1.8f, 30.dp.toPx())
    val nx = -dy
    val ny = dx
    val tip = Offset(a.x + dx * sizePx * 0.5f, a.y + dy * sizePx * 0.5f)
    val back = Offset(a.x - dx * sizePx * 0.2f, a.y - dy * sizePx * 0.2f)
    val path = Path().apply {
      moveTo(back.x + nx * sizePx * 0.5f, back.y + ny * sizePx * 0.5f)
      lineTo(tip.x, tip.y)
      lineTo(back.x - nx * sizePx * 0.5f, back.y - ny * sizePx * 0.5f)
    }
    val alpha = 1f - k * 0.2f
    drawPath(path, CarpetBlue.copy(alpha = alpha), style = Stroke(sizePx * 0.32f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, Color.White.copy(alpha = alpha), style = Stroke(sizePx * 0.18f, cap = StrokeCap.Round, join = StrokeJoin.Round))
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
    locked: Boolean,
    cameraOk: Boolean,
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
      Row(
          Modifier.clip(RoundedCornerShape(50)).background(Drivr.card.copy(alpha = 0.85f)).padding(horizontal = 14.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (locked) Drivr.ok else Drivr.hint))
        Spacer(Modifier.width(8.dp))
        Text(
            when {
              !cameraOk -> stringResource(R.string.ar_no_camera)
              locked -> stringResource(R.string.ar_locked)
              else -> stringResource(R.string.ar_aligning)
            },
            color = Color.White,
            fontSize = 14.sp,
        )
      }
      Spacer(Modifier.weight(1f))
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
