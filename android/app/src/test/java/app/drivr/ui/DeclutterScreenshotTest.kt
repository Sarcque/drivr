package app.drivr.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.drivr.RouteStatus
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
 * Joris's screen from the A4 trip: turn left in 150 m, a jam 3.4 km ahead, arrival card with the
 * delay. The car is drawn where the driving camera puts it (110 dp above the bottom cards).
 */
class DeclutterScreenshotTest {
  init {
    app.drivr.i18n.TestStrings.install()
  }

  @get:Rule
  val paparazzi =
      Paparazzi(
          deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1220, screenHeight = 2712, xdpi = 480, ydpi = 480, density = Density.XXHIGH),
          showSystemUi = false,
          maxPercentDifference = 0.1,
      )

  private val left =
      VisualInstruction(
          primaryContent = VisualInstructionContent("Turn left.", ManeuverType.TURN, ManeuverModifier.LEFT, null, null, emptyList()),
          secondaryContent = null,
          subContent = null,
          triggerDistanceBeforeManeuver = 200.0,
      )
  private val thenRight = VisualInstructionContent("Graadt van Roggenweg", ManeuverType.TURN, ManeuverModifier.RIGHT, null, null, emptyList())
  private val jam =
      TrafficAhead(
          TrafficEvent("jam-a2", TrafficKind.JAM, listOf(LatLon(52.0, 5.0), LatLon(52.01, 5.0)), delaySeconds = 960.0, queueLengthM = 1700.0),
          3400.0)

  @Test
  fun driving_jam_ahead() {
    paparazzi.snapshot {
      val density = androidx.compose.ui.platform.LocalDensity.current
      var bottomH by remember { mutableStateOf(0.dp) }
      val bmp = remember { BitmapFactory.decodeStream(javaClass.getResourceAsStream("/places/bg_drive.png")).asImageBitmap() }
      Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFE6E6E1))) {
        // Where the driving camera puts the car: centred in a 220 dp band above the bottom cards.
        // The pre-rendered map is shifted so its location dot (px 608, 2287) lands there.
        val carFromBottom = 36.dp + bottomH + 110.dp
        val shift = with(density) { (2712 - 2287).toDp() } - carFromBottom
        Image(bmp, null, Modifier.fillMaxSize().offset(y = shift), contentScale = ContentScale.Crop)
        Image(
            remember { ChevronPainter() }, null,
            Modifier.align(Alignment.BottomCenter).padding(bottom = carFromBottom - 32.dp).size(64.dp))
        StackedOverlay(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 36.dp),
            top = {
              InstructionBanner(left, 150.0, then = thenRight)
              DrivingAlert(emptyList(), null, listOf(jam), onDismiss = {})
              RouteStatusCard(RouteStatus.Idle, {}, {})
            },
            buttons = { m ->
              MapButtonColumn(
                  listOf(MapAction("Search", MapIcon.Search) {}, MapAction("Overview", MapIcon.Overview) {}),
                  listOf("Turn list" to {}, "Settings" to {}, "End trip" to {}),
                  m,
              )
            },
            bottom = {
              TripCards(speedLimitKmh = null, speedKmh = null, distanceRemainingM = 104_000.0,
                  durationRemainingS = 5940.0, trafficDelayS = 960.0, onEnd = null, now = LocalTime.of(9, 45))
            },
            onBottomHeight = { bottomH = it },
        )
      }
    }
  }
}

/** Which alert shows, and what shows once one is swiped away. */
class DrivingAlertOrderTest {
  private val jamNear = TrafficAhead(TrafficEvent("a", TrafficKind.JAM, listOf(LatLon(52.0, 5.0), LatLon(52.01, 5.0))), 3400.0)
  private val jamFar = TrafficAhead(TrafficEvent("b", TrafficKind.ACCIDENT, listOf(LatLon(52.1, 5.0))), 12_000.0)
  private val inJam = TrafficAhead(jamNear.event, 0.0, endM = 1200.0, lengthM = 1700.0)

  @Test
  fun swiped_alert_gives_way_to_the_next() {
    val keys = drivingAlertCandidates(emptyList(), listOf(jamNear, jamFar)).map { it.first }
    org.junit.Assert.assertEquals(listOf("traffic:a", "traffic:b"), keys)
    val left = drivingAlertCandidates(emptyList(), listOf(jamNear, jamFar)).first { it.first !in listOf("traffic:a") }
    org.junit.Assert.assertEquals("traffic:b", left.first)
  }

  @Test
  fun entering_a_swiped_jam_shows_its_countdown() {
    val keys = drivingAlertCandidates(emptyList(), listOf(inJam)).map { it.first }
    org.junit.Assert.assertEquals(listOf("jam-in:a"), keys)
  }
}
