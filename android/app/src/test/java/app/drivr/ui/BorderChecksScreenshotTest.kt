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
import app.drivr.settings.Settings
import app.drivr.traffic.BorderChecks
import app.drivr.traffic.BorderInfo
import app.drivr.traffic.BorderReport
import app.drivr.traffic.BorderSource
import app.drivr.traffic.ScoredRoute
import com.android.resources.Density
import java.time.Instant
import java.time.LocalTime
import java.util.TimeZone
import org.junit.Rule
import org.junit.Test
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent

/**
 * Route choice with a border check, Arnhem → Oberhausen. Maps: Drivr's style rendered with
 * MapLibre GL, with Valhalla's real routes (A12/A3 over Elten, the way around the checkpoints
 * through Babberich and Elten village, and the A73 alternative over Gennep), see arnhem_oberhausen_border.json.
 * Times and distances are Valhalla's. The 25-minute queue in the second screen is a constructed
 * Autobahn GmbH report (no border check was reported live on 19 Nov 2026).
 */
class BorderChecksScreenshotTest {
  init {
    app.drivr.i18n.TestStrings.install(app.drivr.i18n.XmlStrings("values-nl"))
    TimeZone.setDefault(TimeZone.getTimeZone("Europe/Amsterdam"))
  }

  private val device =
      DeviceConfig.PIXEL_6.copy(screenWidth = 1220, screenHeight = 2712, xdpi = 480, ydpi = 480, density = Density.XXHIGH, locale = "nl")

  @get:Rule
  val paparazzi = Paparazzi(deviceConfig = device, showSystemUi = false, maxPercentDifference = 0.1)

  @org.junit.After
  fun englishAgain() = app.drivr.i18n.TestStrings.install()

  private val elten = BorderChecks.NL_DE.first { it.id == "elten-a3" }
  private val fetched = Instant.parse("2026-11-19T07:40:00Z")
  private val queue = BorderReport("elten-a3", BorderSource.AUTOBAHN_GMBH, 25 * 60.0, Instant.parse("2026-11-19T07:12:00Z"), fetched, "Stau wegen Grenzkontrollen", true)

  private val viaElten = ScoredRoute(4489.0, 99_000.0, 0.0, 0, "A3", BorderInfo(elten))
  private val around = ScoredRoute(5064.0, 105_500.0, 0.0, 0, "N812", BorderInfo(null, avoids = elten))
  private val gennep = BorderChecks.NL_DE.first { it.id == "gennep-a57" }
  /** Valhalla's own alternative: A73 and over the border at Gennep, another checkpoint. */
  private val viaA57 = ScoredRoute(5544.0, 133_700.0, 0.0, 0, "A73", BorderInfo(gennep))

  /** No live report: the A3 stays fastest, the way around is offered with its extra time. */
  @Test
  fun border_route_choice_nl() {
    paparazzi.snapshot {
      Screen("bg_border_choice.png", toA12, 1800.0, 4489.0) {
        RouteChoiceCard(listOf(viaElten, around, viaA57), selected = 0, onSelect = {}, onGo = {})
      }
    }
  }

  /** A 25-minute queue at Elten reported: the way through the border villages is now faster and is driven. */
  @Test
  fun border_queue_route_around_nl() {
    val queued = viaElten.copy(border = BorderInfo(elten, queue), borderDelaySeconds = 25 * 60.0)
    paparazzi.snapshot {
      Screen("bg_border_around.png", toBabberich, 650.0, 5064.0) {
        RouteChoiceCard(listOf(around, viaA57, queued), selected = 0, onSelect = {}, onGo = {})
      }
    }
  }

  /** Offline: the check is still named, with "no live info" instead of a delay. */
  @Test
  fun border_offline_nl() {
    paparazzi.snapshot {
      Screen("bg_border_choice.png", toA12, 1800.0, 4489.0) {
        RouteChoiceCard(
            listOf(viaElten.copy(border = BorderInfo(elten, liveChecked = false)), around.copy(border = BorderInfo(null, avoids = elten, liveChecked = false))),
            selected = 0, onSelect = {}, onGo = {})
      }
    }
  }

  @Test
  fun settings_checks_nl() {
    paparazzi.snapshot { SettingsScreen(Settings(alwaysAvoidBorderChecks = true), {}, {}, language = "nl") }
  }

  @Test
  fun settings_checks_disclaimer_nl() {
    paparazzi.snapshot { SettingsScreen(Settings(), {}, {}, language = "nl", showChecksDisclaimer = true) }
  }

  @Test
  fun border_route_choice_en() {
    app.drivr.i18n.TestStrings.install()
    paparazzi.unsafeUpdateConfig(deviceConfig = device.copy(locale = "en"))
    val queued = viaElten.copy(border = BorderInfo(elten, queue), borderDelaySeconds = 25 * 60.0)
    paparazzi.snapshot {
      Screen("bg_border_around.png", toBabberich, 650.0, 5064.0) {
        RouteChoiceCard(listOf(around, viaA57, queued), selected = 0, onSelect = {}, onGo = {})
      }
    }
  }

  @Composable
  private fun Screen(bg: String, banner: VisualInstruction, distanceM: Double, durationS: Double, card: @Composable () -> Unit) {
    val bmp = remember { BitmapFactory.decodeStream(javaClass.getResourceAsStream("/places/$bg")).asImageBitmap() }
    Box(Modifier.fillMaxSize()) {
      Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
      StackedOverlay(
          Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 36.dp),
          top = { InstructionBanner(banner, distanceM) },
          buttons = { m ->
            MapButtonColumn(
                listOf(MapAction("Zoeken", MapIcon.Search) {}, MapAction("Volgen", MapIcon.Recenter) {}),
                listOf("Afslagen" to {}, "Instellingen" to {}, "Rit stoppen" to {}),
                m,
            )
          },
          bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
              card()
              TripCards(speedLimitKmh = 100, speedKmh = null, distanceRemainingM = 99_000.0,
                  durationRemainingS = durationS, trafficDelayS = 0.0, onEnd = null, now = LocalTime.of(8, 40))
            }
          },
      )
    }
  }

  private val toA12 =
      VisualInstruction(
          primaryContent = VisualInstructionContent("A12: Oberhausen, Emmerich", ManeuverType.ON_RAMP, ManeuverModifier.SLIGHT_RIGHT, null, null, emptyList()),
          secondaryContent = null, subContent = null, triggerDistanceBeforeManeuver = 1000.0,
      )

  private val toBabberich =
      VisualInstruction(
          primaryContent = VisualInstructionContent("Beekseweg: Babberich, Elten", ManeuverType.OFF_RAMP, ManeuverModifier.SLIGHT_RIGHT, null, null, emptyList()),
          secondaryContent = null, subContent = null, triggerDistanceBeforeManeuver = 1000.0,
      )
}
