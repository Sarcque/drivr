package app.drivr.ar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.VisualInstructionContent

/**
 * The AR overlay geometry, rendered over a flat test road instead of a camera feed (tests have no
 * camera). The road and the overlay go through the same projector, so this checks they line up.
 */
class ArOverlayScreenshotTest {
  @get:Rule
  val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = false)

  // Driving north in the right lane of a 2+2 road; exit right 90 m ahead.
  private val route =
      listOf(Enu(0.0, -20.0), Enu(0.0, 90.0), Enu(8.0, 110.0), Enu(40.0, 135.0), Enu(140.0, 150.0))

  private val maneuver =
      VisualInstructionContent(
          "A27: Hilversum, Amersfoort",
          ManeuverType.OFF_RAMP,
          ManeuverModifier.SLIGHT_RIGHT,
          null,
          null,
          emptyList(),
      )

  @Composable
  private fun Scene(pose: CameraPose, carY: Double = 0.0) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
      if (size.width == 0) return@Box
      val p = ArProjector(pose, size.width.toFloat(), size.height.toFloat())
      val shift = Enu(0.0, -carY)
      TestRoad(p, shift)
      val ahead = routeAhead(route.map { it + shift }, 320.0, 2.0)
      val toTurn = 90.0 - carY
      ArOverlay(maneuver, toTurn, ArScene(pose.headingDeg, ahead, (toTurn / 2).toInt()), p)
    }
  }

  @Composable
  private fun TestRoad(p: ArProjector, shift: Enu) {
    Canvas(Modifier.fillMaxSize()) {
      val horizonY = p.project(Enu(0.0, 5000.0, 1.2))?.y ?: (size.height * 0.4f)
      drawRect(
          Brush.verticalGradient(listOf(Color(0xFF9FB8CF), Color(0xFFDCE6EE)), endY = horizonY),
      )
      drawRect(
          app.drivr.ui.Drivr.ground,
          topLeft = Offset(0f, horizonY),
          size = androidx.compose.ui.geometry.Size(size.width, size.height - horizonY),
      )
      fun quad(a: Enu, b: Enu, c: Enu, d: Enu, color: Color) {
        val pts = listOf(a, b, c, d).map { p.project(it + shift, nearPlaneM = 0.5) ?: return }
        drawPath(
            Path().apply {
              moveTo(pts[0].x, pts[0].y)
              pts.drop(1).forEach { lineTo(it.x, it.y) }
              close()
            },
            color,
        )
      }
      // Carriageway: x from -7 to +1.75 (two lanes each way), and the exit lane bending right.
      quad(Enu(-7.5, 1.0 - shift.y), Enu(2.0, 1.0 - shift.y), Enu(2.0, 600.0), Enu(-7.5, 600.0), app.drivr.ui.Drivr.asphalt)
      quad(Enu(1.5, 80.0), Enu(5.0, 80.0), Enu(44.0, 140.0), Enu(38.0, 142.0), app.drivr.ui.Drivr.asphalt)
      fun line(x: Double, from: Double, to: Double, dashed: Boolean) {
        val a = p.project(Enu(x, maxOf(from, 1.0 - shift.y)) + shift, 0.5) ?: return
        val b = p.project(Enu(x, to) + shift, 0.5) ?: return
        drawLine(
            Color(0xFFEDEDED),
            Offset(a.x, a.y),
            Offset(b.x, b.y),
            5f,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(30f, 40f)) else null,
        )
      }
      line(-1.75, 1.0, 600.0, dashed = true)
      line(1.75, 1.0, 80.0, dashed = false)
      line(-3.6, 1.0, 600.0, dashed = false)
      line(-5.45, 1.0, 600.0, dashed = true)
      line(-7.3, 1.0, 600.0, dashed = false)
    }
  }

  @Test
  fun approaching_exit_dashboard_mount() {
    paparazzi.snapshot { Scene(CameraPose(headingDeg = 0.0, pitchDownDeg = 10.0, rollDeg = 0.0)) }
  }

  @Test
  fun at_the_exit_slightly_tilted_mount() {
    paparazzi.snapshot { Scene(CameraPose(headingDeg = 0.0, pitchDownDeg = 10.0, rollDeg = 2.0), carY = 60.0) }
  }
}
