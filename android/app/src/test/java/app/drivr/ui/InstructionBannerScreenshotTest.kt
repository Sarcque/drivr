package app.drivr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import uniffi.ferrostar.LaneInfo
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent

/**
 * Renders the banner with lane data exactly as Valhalla returned it for the demo route
 * (Utrecht Centraal to Amersfoort, 16 Nov 2026).
 */
class InstructionBannerScreenshotTest {
  @get:Rule
  val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = false)

  private fun banner(
      text: String,
      type: ManeuverType,
      modifier: ManeuverModifier,
      lanes: List<LaneInfo>,
      secondary: String? = null,
  ) =
      VisualInstruction(
          primaryContent = VisualInstructionContent(text, type, modifier, null, null, emptyList()),
          secondaryContent =
              secondary?.let { VisualInstructionContent(it, null, null, null, null, emptyList()) },
          subContent =
              if (lanes.isEmpty()) null
              else VisualInstructionContent("", null, null, null, lanes, emptyList()),
          triggerDistanceBeforeManeuver = 1000.0,
      )

  private val a28OnRamp =
      banner(
          "Rijksweg 28",
          ManeuverType.TURN,
          ManeuverModifier.RIGHT,
          listOf(
              LaneInfo(false, listOf("straight"), null),
              LaneInfo(false, listOf("straight"), null),
              LaneInfo(false, listOf("straight"), null),
              LaneInfo(true, listOf("right"), "right"),
              LaneInfo(true, listOf("right"), "right"),
          ),
      )

  private val rijnsweerd =
      banner(
          "A28: Groningen, Amersfoort",
          ManeuverType.NEW_NAME,
          ManeuverModifier.STRAIGHT,
          listOf(
              LaneInfo(true, listOf("straight"), "straight"),
              LaneInfo(true, listOf("straight"), "straight"),
              LaneInfo(false, listOf("slight right"), null),
          ),
          secondary = "Utrecht-Science Park",
      )

  private val leusdenExit =
      banner(
          "N226: Leusden-Zuid, Woudenberg",
          ManeuverType.OFF_RAMP,
          ManeuverModifier.SLIGHT_RIGHT,
          listOf(
              LaneInfo(false, listOf("straight"), null),
              LaneInfo(false, listOf("straight"), null),
              LaneInfo(false, listOf("straight"), null),
              LaneInfo(true, listOf("slight right"), "slight right"),
          ),
      )

  private val dodeweg =
      banner(
          "Dodeweg",
          ManeuverType.TURN,
          ManeuverModifier.LEFT,
          listOf(
              LaneInfo(true, listOf("left"), "left"),
              LaneInfo(false, listOf("left", "right"), "left"),
          ),
      )

  @Composable
  private fun Frame(content: @Composable () -> Unit) {
    Column(Modifier.background(Color(0xFF55606B)).padding(16.dp)) { content() }
  }

  @Test
  fun lane_banners_from_real_route() {
    paparazzi.snapshot {
      Frame {
        InstructionBanner(a28OnRamp, 400.0)
        Spacer(Modifier.height(16.dp))
        InstructionBanner(rijnsweerd, 1200.0)
        Spacer(Modifier.height(16.dp))
        InstructionBanner(leusdenExit, 650.0)
        Spacer(Modifier.height(16.dp))
        InstructionBanner(dodeweg, 80.0)
      }
    }
  }

  @Test
  fun distances_are_rounded_for_glancing() {
    assertEquals("80 m", formatDistance(78.0))
    assertEquals("650 m", formatDistance(642.0))
    assertEquals("1.2 km", formatDistance(1234.0))
    assertEquals("25 km", formatDistance(25_102.0))
  }

  @Test
  fun lane_directions_map_to_angles() {
    assertEquals(0f, laneDirectionAngle("straight"))
    assertEquals(40f, laneDirectionAngle("slight right"))
    assertEquals(-90f, laneDirectionAngle("left"))
    assertEquals(-180f, laneDirectionAngle("uturn"))
  }
}
