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
import app.drivr.cameras.CameraAhead
import app.drivr.cameras.CameraKind
import app.drivr.cameras.SpeedCamera
import app.drivr.i18n.TestStrings
import app.drivr.i18n.XmlStrings
import app.drivr.places.LatLng
import app.drivr.settings.Settings
import app.drivr.traffic.LatLon
import app.drivr.traffic.TrafficAhead
import app.drivr.traffic.TrafficEvent
import app.drivr.traffic.TrafficKind
import com.android.resources.Density
import java.time.LocalTime
import org.junit.After
import org.junit.Rule
import org.junit.Test
import uniffi.ferrostar.LaneInfo
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent

/**
 * The driving overlays and settings in each translation, so a too-long word or a missing string
 * shows up as a screenshot diff. The sign text ("Exit 11: Baarn") comes from the routing server,
 * which answers in the app language too, so it is left as it is here.
 */
class LanguagesScreenshotTest {
  init {
    TestStrings.install()
  }

  private val device =
      DeviceConfig.PIXEL_6.copy(screenWidth = 1220, screenHeight = 2712, xdpi = 480, ydpi = 480, density = Density.XXHIGH)

  @get:Rule val paparazzi = Paparazzi(deviceConfig = device, showSystemUi = false, maxPercentDifference = 0.1)

  @After
  fun englishAgain() = TestStrings.install()

  private fun inLanguage(lang: String) {
    TestStrings.install(XmlStrings("values-$lang"))
    paparazzi.unsafeUpdateConfig(deviceConfig = device.copy(locale = lang))
  }

  private val s = LaneInfo(false, listOf("straight"), null)
  private val exit = LaneInfo(true, listOf("slight right"), "slight right")
  private val baarn =
      VisualInstruction(
          primaryContent = VisualInstructionContent("Exit 11: Baarn, Eembrugge", ManeuverType.OFF_RAMP, ManeuverModifier.SLIGHT_RIGHT, null, null, listOf("11")),
          secondaryContent = null,
          subContent = VisualInstructionContent("", null, null, null, listOf(s, s, s, s, exit), emptyList()),
          triggerDistanceBeforeManeuver = 2000.0,
      )

  private fun driving(lang: String) {
    inLanguage(lang)
    paparazzi.snapshot {
      val bmp = remember { BitmapFactory.decodeStream(javaClass.getResourceAsStream("/places/bg_drive.png")).asImageBitmap() }
      Box(Modifier.fillMaxSize()) {
        Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          InstructionBanner(baarn, 1500.0)
          // Both alert kinds, for the text; the app shows one at a time under the banner.
          val jam = TrafficEvent("j", TrafficKind.JAM, listOf(LatLon(52.1, 5.2)), delaySeconds = 420.0, queueLengthM = 2800.0)
          TrafficAheadCard(TrafficAhead(jam, 4200.0))
          val cam = SpeedCamera("c", CameraKind.SECTION, LatLng(52.1, 5.2), maxSpeedKmh = 100)
          CameraAheadCard(CameraAhead(cam, 1800.0, 6400.0), null)
          Box(Modifier.weight(1f))
          TripCards(speedLimitKmh = 100, speedKmh = 96, distanceRemainingM = 57_400.0, durationRemainingS = 4500.0,
              onEnd = null, trafficDelayS = 420.0, now = LocalTime.of(15, 12))
        }
      }
    }
  }

  @Test fun driving_nl() = driving("nl")

  @Test fun driving_de() = driving("de")

  @Test fun driving_fr() = driving("fr")

  @Test fun driving_es() = driving("es")

  @Test
  fun settings_nl() {
    inLanguage("nl")
    paparazzi.snapshot { SettingsScreen(Settings(), {}, {}, language = "nl") }
  }
}
