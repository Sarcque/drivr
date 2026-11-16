package app.drivr.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.drivr.RouteStatus
import app.drivr.settings.Settings
import app.drivr.traffic.LatLon
import app.drivr.traffic.TrafficAhead
import app.drivr.traffic.TrafficEvent
import app.drivr.traffic.TrafficKind
import com.android.resources.Density
import java.time.LocalTime
import org.junit.Rule
import org.junit.Test
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent

/**
 * The driving screen at the size of Joris's phone (1220 x 2712 px). The map background is Drivr's
 * style rendered with MapLibre GL at the driving camera (60° pitch) on the demo route, with the
 * route line drawn; the overlays are the app's real composables.
 */
class DrivingScreenshotTest {
  @get:Rule
  val paparazzi =
      Paparazzi(
          deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1220, screenHeight = 2712, xdpi = 480, ydpi = 480, density = Density.XXHIGH),
          showSystemUi = false,
          maxPercentDifference = 0.1,
      )

  private val turn =
      VisualInstruction(
          primaryContent = VisualInstructionContent("Catharijnesingel", ManeuverType.TURN, ManeuverModifier.RIGHT, null, null, emptyList()),
          secondaryContent = null,
          subContent = null,
          triggerDistanceBeforeManeuver = 400.0,
      )

  @Composable
  private fun Map(content: @Composable BoxScope.() -> Unit) {
    val bmp = remember { BitmapFactory.decodeStream(javaClass.getResourceAsStream("/places/bg_drive.png")).asImageBitmap() }
    Box(Modifier.fillMaxSize()) {
      Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
      content()
    }
  }

  @Composable
  private fun BoxScope.Driving(menuOpen: Boolean, withAlert: Boolean) {
    InstructionBanner(turn, 50.0, modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 36.dp))
    DrivingControls({}, {}, null, {}, Modifier.align(Alignment.CenterStart).padding(start = 12.dp), initiallyOpen = menuOpen)
    Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (withAlert) {
        val lane = TrafficEvent("l", TrafficKind.LANE_CLOSED, listOf(LatLon(52.1, 5.2)))
        DrivingAlert(emptyList(), null, listOf(TrafficAhead(lane, 23_000.0)))
      }
      TripCards(speedLimitKmh = 30, speedKmh = null, distanceRemainingM = 57_000.0, durationRemainingS = 2700.0,
          onEnd = null, now = LocalTime.of(15, 12))
    }
  }

  @Test
  fun driving() {
    paparazzi.snapshot { Map { Driving(menuOpen = false, withAlert = true) } }
  }

  @Test
  fun driving_menu_open() {
    paparazzi.snapshot { Map { Driving(menuOpen = true, withAlert = false) } }
  }

  @Test
  fun settings() {
    paparazzi.snapshot { SettingsScreen(Settings(), {}, {}) }
  }

  @Test
  fun browse() {
    paparazzi.snapshot {
      Map {
        Column(Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 36.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            PlacesSearchBar("", {}, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            SettingsButton({})
          }
          Spacer(Modifier.padding(5.dp))
          CategoryChips(null, {})
        }
        StartPanel(RouteStatus.Idle, {}, Modifier.align(Alignment.BottomCenter))
      }
    }
  }
}
