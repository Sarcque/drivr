package app.drivr.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.drivr.R
import app.drivr.i18n.L10n
import app.drivr.i18n.TestStrings
import app.drivr.i18n.XmlStrings
import app.drivr.settings.Settings
import app.drivr.voice.ModelState
import app.drivr.voice.VoiceReply
import app.drivr.voice.VoiceUi
import com.android.resources.Density
import java.time.LocalTime
import org.junit.After
import org.junit.Rule
import org.junit.Test
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent

/**
 * "Hey Drivr" voice commands at the size of Joris's phone: the setting (off by default, with what
 * it uses and why), the download, the green mic while it listens, and the cards after "Hey Drivr"
 * in several languages. The answers are the app's own strings for those commands.
 */
class VoiceScreenshotTest {
  init {
    TestStrings.install()
  }

  private val device = DeviceConfig.PIXEL_6.copy(screenWidth = 1220, screenHeight = 2712, xdpi = 480, ydpi = 480, density = Density.XXHIGH)

  @get:Rule
  val paparazzi = Paparazzi(deviceConfig = device, showSystemUi = false, maxPercentDifference = 0.1)

  @After
  fun englishAgain() = TestStrings.install()

  private fun language(folder: String, tag: String) {
    TestStrings.install(XmlStrings(folder))
    paparazzi.unsafeUpdateConfig(deviceConfig = device.copy(locale = tag))
  }

  @Composable
  private fun Map(content: @Composable BoxScope.() -> Unit) {
    val bmp = remember { BitmapFactory.decodeStream(javaClass.getResourceAsStream("/places/bg_drive.png")).asImageBitmap() }
    Box(Modifier.fillMaxSize()) {
      Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
      content()
    }
  }

  private val safe = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 36.dp)

  private fun turn(street: String) =
      VisualInstruction(
          primaryContent = VisualInstructionContent(street, ManeuverType.TURN, ManeuverModifier.RIGHT, null, null, emptyList()),
          secondaryContent = null,
          subContent = null,
          triggerDistanceBeforeManeuver = 400.0,
      )

  /** The driving screen with the green mic button and [voice] under the banner. */
  @Composable
  private fun Driving(voice: VoiceUi, street: String = "Catharijnesingel") {
    Map {
      StackedOverlay(
          safe,
          top = {
            InstructionBanner(turn(street), 180.0)
            VoiceCard(voice, {})
          },
          buttons = { m ->
            MapButtonColumn(
                listOf(
                    MapAction(L10n.s(R.string.voice_mic_desc), MapIcon.Mic) {},
                    MapAction("Search", MapIcon.Search) {},
                    MapAction("Overview", MapIcon.Overview) {},
                    MapAction(L10n.s(R.string.map_mute), MapIcon.Sound) {},
                ),
                listOf("Settings" to {}),
                m,
            )
          },
          bottom = {
            TripCards(speedLimitKmh = 50, speedKmh = 47, distanceRemainingM = 27_400.0, durationRemainingS = 2040.0,
                onEnd = null, now = LocalTime.of(9, 58))
          },
      )
    }
  }

  /** Waiting for "Hey Drivr": only the green mic button shows; nothing else on the screen changes. */
  @Test
  fun driving_waiting_for_wake() {
    paparazzi.snapshot { Driving(VoiceUi.Waiting) }
  }

  @Test
  fun driving_listening() {
    paparazzi.snapshot { Driving(VoiceUi.Awake("avoid", Long.MAX_VALUE)) }
  }

  @Test
  fun driving_answer_nl() {
    language("values-nl", "nl")
    paparazzi.snapshot {
      Driving(VoiceUi.Answer("vermijd tol", VoiceReply(L10n.s(R.string.voice_r_avoid_tolls_reroute)), Long.MAX_VALUE), street = "Catharijnesingel")
    }
  }

  @Test
  fun driving_answer_de_eta() {
    language("values-de", "de")
    paparazzi.snapshot {
      Driving(VoiceUi.Answer("wann bin ich da", VoiceReply(L10n.s(R.string.voice_r_eta, "10:32", "34 min")), Long.MAX_VALUE))
    }
  }

  @Test
  fun driving_answer_es_no_home() {
    language("values-es", "es")
    paparazzi.snapshot {
      Driving(VoiceUi.Answer("llévame a casa", VoiceReply(L10n.s(R.string.voice_r_no_home), ok = false), Long.MAX_VALUE))
    }
  }

  @Test
  fun driving_not_understood_fr() {
    language("values-fr", "fr")
    paparazzi.snapshot { Driving(VoiceUi.Help("bananes", Long.MAX_VALUE)) }
  }

  @Test
  fun settings_voice_off() {
    paparazzi.snapshot { ScrolledSettings(Settings(), VoiceSettingsState()) }
  }

  @Test
  fun settings_voice_ready() {
    paparazzi.snapshot {
      ScrolledSettings(Settings(voiceCommands = true), VoiceSettingsState(model = ModelState.Ready, micAllowed = true, examplesOpen = true))
    }
  }

  @Test
  fun settings_voice_downloading_nl() {
    language("values-nl", "nl")
    paparazzi.snapshot {
      ScrolledSettings(Settings(voiceCommands = true), VoiceSettingsState(model = ModelState.Downloading(0.45f), modelMb = 39, micAllowed = true))
    }
  }

  @Test
  fun settings_voice_no_mic() {
    paparazzi.snapshot {
      ScrolledSettings(Settings(voiceCommands = true), VoiceSettingsState(model = ModelState.Missing, micAllowed = false))
    }
  }

  /** The settings screen scrolled down to the voice section (Paparazzi can't scroll). */
  @Composable
  private fun ScrolledSettings(settings: Settings, voice: VoiceSettingsState, offset: Int = 300) {
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0E1013))) {
      Box(
          Modifier.wrapContentHeight(align = Alignment.Top, unbounded = true)
              .requiredHeight(2600.dp)
              .offset(y = (-offset).dp)
      ) {
        SettingsScreen(settings, {}, {}, voice = voice)
      }
    }
  }
}
