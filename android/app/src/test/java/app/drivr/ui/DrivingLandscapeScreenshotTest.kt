package app.drivr.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import java.time.LocalTime
import org.junit.Rule
import org.junit.Test
import uniffi.ferrostar.LaneInfo
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent

/** Phone on a landscape mount: cards in a column on the left, buttons on the right edge. */
class DrivingLandscapeScreenshotTest {
  init {
    app.drivr.i18n.TestStrings.install()
  }

  @get:Rule
  val paparazzi =
      Paparazzi(
          deviceConfig =
              DeviceConfig.PIXEL_6.copy(
                  screenWidth = 2712, screenHeight = 1220, xdpi = 480, ydpi = 480, density = Density.XXHIGH,
                  orientation = ScreenOrientation.LANDSCAPE),
          showSystemUi = false,
          maxPercentDifference = 0.1,
      )

  @Test
  fun driving_landscape() {
    val s = LaneInfo(false, listOf("straight"), null)
    val r = LaneInfo(true, listOf("slight right"), "slight right")
    val keepRight =
        VisualInstruction(
            primaryContent = VisualInstructionContent("A28: Amersfoort", ManeuverType.FORK, ManeuverModifier.SLIGHT_RIGHT, null, null, emptyList()),
            secondaryContent = null,
            subContent = VisualInstructionContent("", null, null, null, listOf(s, s, r, r), emptyList()),
            triggerDistanceBeforeManeuver = 2000.0,
        )
    paparazzi.snapshot {
      val bmp = remember { BitmapFactory.decodeStream(javaClass.getResourceAsStream("/places/bg_drive.png")).asImageBitmap() }
      Box(Modifier.fillMaxSize()) {
        Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        SideOverlay(
            Modifier.fillMaxSize().padding(12.dp),
            top = { InstructionBanner(keepRight, 900.0) },
            buttons = { m ->
              MapButtonColumn(
                  listOf(MapAction("Search", MapIcon.Search) {}, MapAction("Overview", MapIcon.Overview) {}),
                  listOf("Turn list" to {}, "Settings" to {}),
                  m,
              )
            },
            bottom = {
              Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TripCards(speedLimitKmh = 100, speedKmh = 97, distanceRemainingM = 21_000.0, durationRemainingS = 900.0,
                    onEnd = null, now = LocalTime.of(15, 12))
              }
            },
        )
      }
    }
  }
}
