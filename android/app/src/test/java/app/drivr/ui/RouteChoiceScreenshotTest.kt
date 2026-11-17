package app.drivr.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.drivr.traffic.ScoredRoute
import com.android.resources.Density
import java.time.LocalTime
import org.junit.Rule
import org.junit.Test
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent

/**
 * Route choice and the faster-route offer, at the size of Joris's phone. The route-choice map is
 * Drivr's style rendered with MapLibre GL: Valhalla's two real routes 's-Hertogenbosch → Utrecht
 * (blue A2, grey A59/A27) with NDW's real jams of Tue 17 Nov 2026, 09:40. The numbers on the card
 * are what RouteChoice computes from that data (see RouteChoiceTest).
 */
class RouteChoiceScreenshotTest {
  @get:Rule
  val paparazzi =
      Paparazzi(
          deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1220, screenHeight = 2712, xdpi = 480, ydpi = 480, density = Density.XXHIGH),
          showSystemUi = false,
          maxPercentDifference = 0.1,
      )

  @Composable
  private fun Screen(bg: String, banner: VisualInstruction, distanceM: Double, card: @Composable () -> Unit) {
    val bmp = remember { BitmapFactory.decodeStream(javaClass.getResourceAsStream("/places/$bg")).asImageBitmap() }
    Box(Modifier.fillMaxSize()) {
      Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
      StackedOverlay(
          Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 36.dp),
          top = { InstructionBanner(banner, distanceM) },
          buttons = { m ->
            MapButtonColumn(
                listOf(MapAction("Search", MapIcon.Search) {}, MapAction("Follow", MapIcon.Recenter) {}),
                listOf("Turn list" to {}, "Settings" to {}, "End trip" to {}),
                m,
            )
          },
          bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
              card()
              TripCards(speedLimitKmh = 50, speedKmh = null, distanceRemainingM = 57_200.0,
                  durationRemainingS = 3087.0 + 4697.0, trafficDelayS = 4697.0, onEnd = null, now = LocalTime.of(9, 40))
            }
          },
      )
    }
  }

  private val toA2 =
      VisualInstruction(
          primaryContent = VisualInstructionContent("A2: Amsterdam, Utrecht", ManeuverType.ON_RAMP, ManeuverModifier.SLIGHT_RIGHT, null, null, emptyList()),
          secondaryContent = null,
          subContent = null,
          triggerDistanceBeforeManeuver = 1000.0,
      )

  @Test
  fun route_choice_rush_hour() {
    val a2 = ScoredRoute(3086.5, 57_241.0, 4697.0, 0, "A2")
    val a27 = ScoredRoute(4293.8, 90_262.0, 3512.0, 0, "A27")
    paparazzi.snapshot {
      Screen("bg_route_choice.png", toA2, 2400.0) { RouteChoiceCard(listOf(a2, a27), selected = 0, onSelect = {}, onGo = {}) }
    }
  }

  @Test
  fun faster_route_offer() {
    val turn =
        VisualInstruction(
            primaryContent = VisualInstructionContent("A2: Utrecht", ManeuverType.CONTINUE, ManeuverModifier.STRAIGHT, null, null, emptyList()),
            secondaryContent = null,
            subContent = null,
            triggerDistanceBeforeManeuver = 1000.0,
        )
    paparazzi.snapshot {
      Screen("bg_drive.png", turn, 6200.0) {
        FasterRouteCard(savesSeconds = 11 * 60.0, via = "N322", currentDelaySeconds = 27 * 60.0, onSwitch = {}, onKeep = {})
      }
    }
  }
}
