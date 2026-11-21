package app.drivr.ar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.drivr.nav.RouteStanding
import org.junit.Rule
import org.junit.Test
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.VisualInstructionContent

/**
 * The situations from a real drive (phone on the windscreen, city streets), run through the pose
 * engine exactly as the app feeds it, and drawn over a synthetic street with a dashboard across
 * the bottom of the view. The dashboard hides the first metres of road, like in the car.
 *
 * Phone: portrait, camera 1.25 m above the road, looking 3° down. The street runs at a bearing of
 * 75° (roughly east), two lanes, the car in the right one.
 */
class ArFieldScenarioTest {
  init {
    app.drivr.i18n.TestStrings.install()
  }

  @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = false)

  private val streetBearing = 75.0

  /** A street-aligned frame: [along] metres ahead, [side] metres to the right of the car. */
  private fun street(along: Double, side: Double): Enu {
    val b = Math.toRadians(streetBearing)
    val fx = kotlin.math.sin(b)
    val fy = kotlin.math.cos(b)
    return Enu(fx * along + fy * side, fy * along - fx * side)
  }

  /** Straight for [turnAt] m, then a right turn into a side street. */
  private fun route(turnAt: Double) =
      listOf(street(-30.0, 0.0), street(turnAt, 0.0), street(turnAt + 4.0, 3.0), street(turnAt + 6.0, 10.0), street(turnAt + 6.0, 200.0))

  private val maneuver =
      VisualInstructionContent("Johan de Wittstraat", ManeuverType.TURN, ManeuverModifier.RIGHT, null, null, emptyList())

  @Composable
  private fun Field(engine: ArPoseEngine, turnAt: Double, chrome: Boolean = false, guide: Boolean = false) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
      if (size.width == 0) return@Box
      val ahead = routeAhead(route(turnAt), 400.0, 2.0)
      val routeHeading = bearingDeg(ahead.first(), ahead[5])
      // What the camera really sees: the true pose (car follows the street).
      val truth = ArProjector(CameraPose(streetBearing, 3.0, 0.0, 1.25), size.width.toFloat(), size.height.toFloat())
      // What the overlay uses: whatever the engine believes.
      val believed = ArProjector(engine.pose(routeHeading), size.width.toFloat(), size.height.toFloat())
      Canvas(Modifier.fillMaxSize()) {
        drawStreet(truth, turnAt)
        drawArRoute(ahead, turnAt, RouteStanding.OnRoute, believed, phaseM = 0.0, track = engine.track(5.0))
        if (guide) drawAlignGuide(believed, ahead)
      }
      if (chrome)
          ArChrome(
              maneuver, turnAt, RouteStanding.OnRoute, rerouting = false, track = engine.track(5.0),
              cameraOk = true, dragging = guide, onClose = {})
    }
  }

  private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStreet(p: ArProjector, turnAt: Double) {
    val horizonY = p.project(street(5000.0, 0.0).copy(z = 1.25))?.y ?: (size.height * 0.45f)
    drawRect(Brush.verticalGradient(listOf(Color(0xFF8FB3D9), Color(0xFFE8EEF3)), endY = horizonY))
    drawRect(Color(0xFF6E7A62), topLeft = Offset(0f, horizonY), size = Size(size.width, size.height - horizonY))
    fun quad(vararg q: Enu, color: Color) {
      val pts = q.map { p.project(it, 0.5) ?: return }
      drawPath(Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }, color)
    }
    val asphalt = Color(0xFF4A4D52)
    // Main street: from 2 m left of the left lane to the right kerb, 3.2 m lanes.
    quad(street(0.6, -5.0), street(0.6, 1.8), street(900.0, 1.8), street(900.0, -5.0), color = asphalt)
    // Pavement on the right, broken by the side street.
    quad(street(0.6, 1.8), street(0.6, 4.5), street(turnAt - 2.0, 4.5), street(turnAt - 2.0, 1.8), color = Color(0xFF9A9A96))
    quad(street(turnAt + 12.0, 1.8), street(turnAt + 12.0, 4.5), street(900.0, 4.5), street(900.0, 1.8), color = Color(0xFF9A9A96))
    // Side street to the right.
    quad(street(turnAt - 2.0, 1.8), street(turnAt + 12.0, 1.8), street(turnAt + 12.0, 300.0), street(turnAt - 2.0, 300.0), color = asphalt)
    fun line(side: Double, from: Double, to: Double, dashed: Boolean) {
      val a = p.project(street(from, side), 0.5) ?: return
      val b = p.project(street(to, side), 0.5) ?: return
      drawLine(Color(0xFFEDEDED), Offset(a.x, a.y), Offset(b.x, b.y), 4f,
          pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(24f, 36f)) else null)
    }
    line(-1.6, 0.6, 900.0, dashed = true)
    line(1.6, 0.6, turnAt - 2.0, dashed = false)
    line(1.6, turnAt + 12.0, 900.0, dashed = false)
    line(-4.8, 0.6, 900.0, dashed = false)
    // Buildings either side, so the horizon reads like a street.
    for (k in 0 until 8) {
      val a = 30.0 + k * 40.0
      val h = 9.0 + (k % 3) * 3.0
      quad(street(a, -12.0), street(a + 30.0, -12.0), street(a + 30.0, -12.0).copy(z = h), street(a, -12.0).copy(z = h), color = Color(0xFF7B6E66).copy(alpha = 0.9f))
      if (a + 30 < turnAt - 4 || a > turnAt + 14)
          quad(street(a, 11.0), street(a + 30.0, 11.0), street(a + 30.0, 11.0).copy(z = h + 2), street(a, 11.0).copy(z = h + 2), color = Color(0xFF8A7F73).copy(alpha = 0.9f))
    }
    // The dashboard: hides the bottom of the view (the road up to about 7 m ahead).
    val dashTop = p.project(street(7.0, 0.0))?.y ?: (size.height * 0.7f)
    drawRect(Color(0xFF1E1F22), topLeft = Offset(0f, dashTop), size = Size(size.width, size.height - dashTop))
    drawRect(Color(0xFF2C2E33), topLeft = Offset(0f, dashTop), size = Size(size.width, 14f))
  }

  /**
   * Standing in a queue (the photos at 11:28): no speed, and the GPS course of a standing car is
   * whatever the receiver last made of the noise, here 0° (north) while the street runs at 75°.
   */
  @Test
  fun standing_in_a_queue_gps_course_is_noise() {
    val engine = ArPoseEngine()
    engine.onTilt(Tilt(3.0, 0.0))
    engine.onFix(0.0, 0.2)
    paparazzi.snapshot { Field(engine, turnAt = 160.0) }
  }

  /** Driving at 30 km/h, a right turn 100 m ahead (the photo at 11:27). */
  @Test
  fun right_turn_in_100_m() {
    val engine = ArPoseEngine()
    engine.onTilt(Tilt(3.0, 0.0))
    engine.onFix(streetBearing + 2.0, 8.0)
    paparazzi.snapshot { Field(engine, turnAt = 100.0) }
  }

  /** Same street, 35 m before the turn: the arrow has to bend into the side street. */
  @Test
  fun right_turn_in_35_m() {
    val engine = ArPoseEngine()
    engine.onTilt(Tilt(3.0, 0.0))
    engine.onFix(streetBearing, 7.0)
    paparazzi.snapshot { Field(engine, turnAt = 35.0) }
  }

  /** The full AR screen at 100 m, as the driver sees it. */
  @Test
  fun right_turn_in_100_m_with_chrome() {
    val engine = ArPoseEngine()
    engine.onTilt(Tilt(3.0, 0.0))
    engine.onFix(streetBearing, 8.0)
    paparazzi.snapshot { Field(engine, turnAt = 100.0, chrome = true) }
  }

  /** Phone turned 6° to the left on its mount, nothing learned yet: the driver drags it right. */
  @Test
  fun dragging_to_line_up() {
    val engine = ArPoseEngine()
    engine.onTilt(Tilt(3.0, 0.0))
    engine.onFix(streetBearing, 8.0)
    engine.setManual(-1.5, 0.5)
    paparazzi.snapshot { Field(engine, turnAt = 100.0, chrome = true, guide = true) }
  }

  /** Phone aimed at the dashboard (camera 40° down): nothing drawn, and the chip says why. */
  @Test
  fun camera_aimed_at_the_dashboard() {
    val engine = ArPoseEngine()
    engine.onTilt(Tilt(40.0, 0.0))
    engine.onFix(streetBearing, 8.0)
    paparazzi.snapshot { Field(engine, turnAt = 100.0, chrome = true) }
  }
}
