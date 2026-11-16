package app.drivr.ar

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.offset
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.drivr.ui.Drivr
import app.drivr.ui.InstructionBanner
import app.drivr.ui.formatDistance
import com.stadiamaps.ferrostar.composeui.views.components.maneuver.ManeuverImage
import com.stadiamaps.ferrostar.core.NavigationUiState
import kotlin.math.roundToInt
import uniffi.ferrostar.VisualInstructionContent

private val RouteBlue = Drivr.accent
private val SignCard = Drivr.card

/** What the AR layer draws, in local metres around the car. Rebuilt on each location update. */
internal class ArScene(
    val heading: Double,
    val ahead: List<Enu>,
    /** Index in [ahead] of the next maneuver, if it is within range. */
    val maneuverIndex: Int?,
)

private fun buildScene(ui: NavigationUiState): ArScene? {
  val here = ui.location?.coordinates ?: return null
  val geometry = ui.routeGeometry ?: return null
  val origin = LatLng(here.lat, here.lng)
  val enu = geometry.map { LatLng(it.lat, it.lng).toEnu(origin) }
  val ahead = routeAhead(enu, aheadM = 320.0, stepM = 2.0)
  if (ahead.size < 3) return null
  val routeBearing = bearingDeg(ahead.first(), ahead[minOf(5, ahead.lastIndex)])
  val heading = ui.location?.courseOverGround?.degrees?.toDouble() ?: routeBearing
  val toManeuver = ui.progress?.distanceToNextManeuver
  val maneuverIndex =
      toManeuver?.let { d -> (d / 2.0).roundToInt().takeIf { it in 1..ahead.lastIndex } }
  return ArScene(heading, ahead, maneuverIndex)
}

/**
 * The AR junction view: live camera with the route painted onto the road, chevrons showing the
 * direction, and the direction sign hanging over the turn. Pose = GPS course (heading) + gravity
 * (tilt), so no ARCore and no Google Play Services.
 */
@Composable
fun ArJunctionView(ui: NavigationUiState, onClose: () -> Unit, modifier: Modifier = Modifier) {
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

  val tilt by rememberDeviceTilt()
  val scene = remember(ui.location, ui.routeGeometry) { buildScene(ui) }
  var viewSize by remember { mutableStateOf(IntSize.Zero) }

  Box(modifier.fillMaxSize().background(Color.Black).onSizeChanged { viewSize = it }) {
    if (hasCamera) CameraPreview(Modifier.fillMaxSize())

    val projector =
        if (scene != null && viewSize.width > 0)
            ArProjector(
                CameraPose(scene.heading, tilt.pitchDownDeg, tilt.rollDeg),
                viewSize.width.toFloat(),
                viewSize.height.toFloat(),
            )
        else null

    if (scene != null && projector != null) {
      ArOverlay(ui.visualInstruction?.primaryContent, ui.progress?.distanceToNextManeuver, scene, projector)
    }

    Column(Modifier.statusBarsPadding().padding(12.dp)) {
      ui.visualInstruction?.let {
        InstructionBanner(it, ui.progress?.distanceToNextManeuver)
      }
    }

    Row(
        Modifier.align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(16.dp)
            .clip(RoundedCornerShape(50))
            .background(Drivr.card)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("AR · phone on the dashboard, camera facing the road", color = Color.White, fontSize = 13.sp)
      Spacer(Modifier.width(8.dp))
      TextButton(onClick = onClose) { Text("Map", color = RouteBlue) }
    }
  }
}

@Composable
private fun CameraPreview(modifier: Modifier) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  AndroidView(
      modifier = modifier,
      factory = { ctx ->
        val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener(
            {
              val provider = providerFuture.get()
              val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
              provider.unbindAll()
              provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            },
            ContextCompat.getMainExecutor(context),
        )
        view
      },
      onRelease = {
        ProcessCameraProvider.getInstance(context).get().unbindAll()
      },
  )
}

private fun DrawScope.drawRoute(scene: ArScene, p: ArProjector) {
  // Road ribbon, about a lane wide, fading out with distance.
  val (left, right) = ribbonEdges(scene.ahead, halfWidthM = 1.25)
  // The first metres under the bonnet are outside the camera's view: start at the first visible
  // point and keep the contiguous visible stretch after it.
  // Start a few metres ahead: the bottom of the frame is bonnet and dashboard, not road.
  val firstAhead = 3 // points are 2 m apart
  val start =
      (firstAhead until left.size).firstOrNull {
        p.project(left[it]) != null && p.project(right[it]) != null
      } ?: return
  val l = left.drop(start).map { p.project(it) }
  val r = right.drop(start).map { p.project(it) }
  val n = l.indices.takeWhile { l[it] != null && r[it] != null }.lastOrNull()
  if (n == null || n < 1) return
  val ribbon =
      Path().apply {
        moveTo(l[0]!!.x, l[0]!!.y)
        for (i in 1..n) lineTo(l[i]!!.x, l[i]!!.y)
        for (i in n downTo 0) lineTo(r[i]!!.x, r[i]!!.y)
        close()
      }
  val nearY = maxOf(l[0]!!.y, r[0]!!.y)
  val farY = minOf(l[n]!!.y, r[n]!!.y)
  drawPath(
      ribbon,
      Brush.verticalGradient(
          listOf(RouteBlue.copy(alpha = 0.75f), RouteBlue.copy(alpha = 0.25f)),
          startY = farY,
          endY = nearY,
      ),
  )
  // Edge lines.
  for (edge in listOf(l, r)) {
    val path = Path().apply {
      moveTo(edge[0]!!.x, edge[0]!!.y)
      for (i in 1..n) lineTo(edge[i]!!.x, edge[i]!!.y)
    }
    drawPath(path, Color.White.copy(alpha = 0.85f), style = Stroke(2.dp.toPx()))
  }


  // Upright arrows standing in the road just past the turn, at eye level. The road surface near the
  // horizon is only a few pixels tall from a dashboard, so these carry the "go this way" message.
  scene.maneuverIndex?.let { m -> drawTurnBillboards(scene.ahead, m, p) }
}

/** Route ribbon, upright turn arrows and the hanging sign, for a given scene and camera. */
@Composable
internal fun ArOverlay(
    maneuver: VisualInstructionContent?,
    distanceToManeuver: Double?,
    scene: ArScene,
    projector: ArProjector,
) {
  Canvas(Modifier.fillMaxSize()) { drawRoute(scene, projector) }
  if (maneuver != null) ManeuverSign(maneuver, distanceToManeuver, scene, projector)
}

private fun DrawScope.drawTurnBillboards(line: List<Enu>, maneuverIndex: Int, p: ArProjector) {
  val eye = 1.3
  for (k in 0 until 4) {
    val i = maneuverIndex + 3 + k * 4
    if (i + 1 > line.lastIndex) return
    val at = line[i] + Enu(0.0, 0.0, eye)
    val next = line[i + 1] + Enu(0.0, 0.0, eye)
    val a = p.project(at) ?: continue
    val b = p.project(next) ?: continue
    var dx = b.x - a.x
    var dy = b.y - a.y
    val len = kotlin.math.hypot(dx, dy).takeIf { it > 1e-3f } ?: continue
    dx /= len
    dy /= len
    // Size: about 1.4 m tall in the world, but never smaller than readable.
    val sizePx = maxOf(p.pixelsPerMetreAt(a.depth) * 1.4f, 22.dp.toPx())
    val nx = -dy
    val ny = dx
    val tip = Offset(a.x + dx * sizePx * 0.5f, a.y + dy * sizePx * 0.5f)
    val back = Offset(a.x - dx * sizePx * 0.2f, a.y - dy * sizePx * 0.2f)
    val path =
        Path().apply {
          moveTo(back.x + nx * sizePx * 0.5f, back.y + ny * sizePx * 0.5f)
          lineTo(tip.x, tip.y)
          lineTo(back.x - nx * sizePx * 0.5f, back.y - ny * sizePx * 0.5f)
        }
    val alpha = 1f - k * 0.18f
    drawPath(path, Color.Black.copy(alpha = 0.35f * alpha), style = Stroke(sizePx * 0.26f))
    drawPath(path, RouteBlue.copy(alpha = alpha), style = Stroke(sizePx * 0.18f))
  }
}

@Composable
private fun ManeuverSign(
    maneuver: VisualInstructionContent,
    distanceToManeuver: Double?,
    scene: ArScene,
    p: ArProjector,
) {
  val idx = scene.maneuverIndex ?: return
  val ground = scene.ahead[idx]
  val top = p.project(ground + Enu(0.0, 0.0, 4.0)) ?: return
  val foot = p.project(ground) ?: return
  val density = LocalDensity.current
  // Sign is ~3.5 m wide in the world; clamp so it stays readable far away and not huge up close.
  val widthPx = (p.pixelsPerMetreAt(top.depth) * 3.5f).coerceIn(with(density) { 140.dp.toPx() }, with(density) { 300.dp.toPx() })
  val scale = widthPx / with(density) { 220.dp.toPx() }

  Canvas(Modifier.fillMaxSize()) {
    drawLine(Color.White.copy(alpha = 0.6f), Offset(top.x, top.y), Offset(foot.x, foot.y), 2.dp.toPx())
    drawCircle(RouteBlue, 7.dp.toPx(), Offset(foot.x, foot.y))
  }
  BoxWithConstraints(Modifier.fillMaxSize()) {
    Column(
        Modifier.offset { IntOffset((top.x).roundToInt(), (top.y).roundToInt()) }
            .graphicsLayer {
              scaleX = scale
              scaleY = scale
              translationX = -size.width / 2f
              translationY = -size.height
              transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            }
            .widthIn(max = 220.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SignCard)
            .padding(10.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp)) { ManeuverImage(maneuver, tint = Color.White) }
        Spacer(Modifier.width(8.dp))
        Text(
            formatDistance(distanceToManeuver ?: 0.0),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
      }
      Text(maneuver.text, color = Color.White, fontSize = 15.sp, maxLines = 2)
    }
  }
}
