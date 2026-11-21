package app.drivr.ar

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
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
 * The simulated drive ([ArDriveSimulationTest]) as the driver would see it: the rendered camera
 * picture with the overlay exactly where the pipeline put it at that moment. Nothing here is
 * placed by hand: background and overlay come from the same simulation.
 */
class ArDriveScreenshotTest {
  init {
    app.drivr.i18n.TestStrings.install()
  }

  @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = false)

  private val maneuver =
      VisualInstructionContent("Stationsweg", ManeuverType.TURN, ManeuverModifier.RIGHT, null, null, emptyList())

  private fun snap(weather: DriveWorld.Weather, t: Double): ArDriveSimulationTest.Snapshot {
    var out: ArDriveSimulationTest.Snapshot? = null
    ArDriveSimulationTest().drive(weather, seed = 7, snapshotAt = listOf(t)) { _, s -> out = s }
    return out!!
  }

  @Composable
  private fun Shot(s: ArDriveSimulationTest.Snapshot, weather: DriveWorld.Weather, legacy: Boolean = false) {
    // The camera picture at half the screen's resolution (it is upscaled like a preview).
    val w = 540
    val h = 1200
    val rgb = IntArray(w * h)
    s.world.render(s.camPos, s.truthPose, w, h, 68.0, weather, 99, s.carHeadingDeg, rgb)
    val bmp = Bitmap.createBitmap(rgb, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    Box(Modifier.fillMaxSize()) {
      Canvas(Modifier.fillMaxSize()) {
        drawImage(bmp, srcOffset = IntOffset.Zero, srcSize = IntSize(w, h), dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        val p = ArProjector(if (legacy) s.legacyPose else s.believed, size.width, size.height, 68.0)
        drawArRoute(if (legacy) s.legacyLine else s.line, s.maneuverAtM, RouteStanding.OnRoute, p, phaseM = 0.0, track = if (legacy) ArTrack.LOCKED else s.track)
      }
      ArChrome(maneuver, s.maneuverAtM, RouteStanding.OnRoute, rerouting = false, track = if (legacy) ArTrack.FINE_TUNING else s.track, cameraOk = true, onClose = {})
    }
  }

  private val rain = DriveWorld.Weather(rain = true)
  private val sun = DriveWorld.Weather(sun = true, carAhead = true)
  private val clear = DriveWorld.Weather()

  @Test fun queue_standing_still() = snap(clear, 3.0).let { paparazzi.snapshot { Shot(it, clear) } }

  @Test fun queue_standing_still_before() = snap(clear, 3.0).let { paparazzi.snapshot { Shot(it, clear, legacy = true) } }

  @Test fun straight_in_the_rain() = snap(rain, 16.0).let { paparazzi.snapshot { Shot(it, rain) } }

  @Test fun curve_low_sun_car_ahead() = snap(sun, 25.0).let { paparazzi.snapshot { Shot(it, sun) } }

  @Test fun turn_ahead() = snap(clear, 33.0).let { paparazzi.snapshot { Shot(it, clear) } }

  @Test fun turn_close() = snap(clear, 39.0).let { paparazzi.snapshot { Shot(it, clear) } }
}
