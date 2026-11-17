package app.drivr.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import app.drivr.music.MusicState
import app.drivr.music.NowPlaying
import com.android.resources.Density
import java.time.LocalTime
import org.junit.Rule
import org.junit.Test
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent

/** The music card on the browse and driving screens, at the size of Joris's phone. */
class MusicScreenshotTest {
  @get:Rule
  val paparazzi =
      Paparazzi(
          deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1220, screenHeight = 2712, xdpi = 480, ydpi = 480, density = Density.XXHIGH),
          showSystemUi = false,
          maxPercentDifference = 0.1,
      )

  private fun cover(): Bitmap {
    val b = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
    val p = Paint().apply { shader = LinearGradient(0f, 0f, 200f, 200f, 0xFFE0517A.toInt(), 0xFF3A2C8C.toInt(), Shader.TileMode.CLAMP) }
    Canvas(b).drawRect(0f, 0f, 200f, 200f, p)
    return b
  }

  private val playing
    get() = MusicState(true, NowPlaying("Midnight City", "M83", cover(), playing = true, appLabel = "Spotify", packageName = "com.spotify.music"))

  @Composable
  private fun Map(content: @Composable BoxScope.() -> Unit) {
    val bmp = remember { BitmapFactory.decodeStream(javaClass.getResourceAsStream("/places/bg_drive.png")).asImageBitmap() }
    Box(Modifier.fillMaxSize()) {
      Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
      content()
    }
  }

  @Composable
  private fun Card(state: MusicState, modifier: Modifier = Modifier) = MusicCard(state, {}, {}, {}, {}, {}, {}, modifier)

  @Composable
  private fun BoxScope.Browse(state: MusicState) {
    Column(Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 36.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        PlacesSearchBar("", {}, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        MusicButton({})
        Spacer(Modifier.width(8.dp))
        SettingsButton({})
      }
      Spacer(Modifier.height(10.dp))
      CategoryChips(null, {})
      Spacer(Modifier.height(10.dp))
      Card(state)
    }
  }

  @Test
  fun browse_now_playing() {
    paparazzi.snapshot { Map { Browse(playing) } }
  }

  @Test
  fun browse_access_prompt() {
    paparazzi.snapshot { Map { Browse(MusicState(hasAccess = false)) } }
  }

  @Test
  fun browse_nothing_playing() {
    paparazzi.snapshot { Map { Browse(MusicState(hasAccess = true)) } }
  }

  @Test
  fun driving_with_music() {
    val turn =
        VisualInstruction(
            primaryContent = VisualInstructionContent("Catharijnesingel", ManeuverType.TURN, ManeuverModifier.RIGHT, null, null, emptyList()),
            secondaryContent = null, subContent = null, triggerDistanceBeforeManeuver = 400.0)
    paparazzi.snapshot {
      Map {
        InstructionBanner(turn, 50.0, modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 36.dp))
        DrivingControls({}, {}, null, {}, Modifier.align(Alignment.CenterStart).padding(start = 12.dp), initiallyOpen = true, onMusic = {})
        Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Card(playing)
          TripCards(speedLimitKmh = 30, speedKmh = null, distanceRemainingM = 57_000.0, durationRemainingS = 2700.0,
              onEnd = null, now = LocalTime.of(15, 12))
        }
      }
    }
  }
}
