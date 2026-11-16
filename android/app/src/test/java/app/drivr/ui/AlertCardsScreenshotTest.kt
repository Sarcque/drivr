package app.drivr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.drivr.cameras.Enforcement
import app.drivr.cameras.EnforcementAhead
import app.drivr.traffic.LatLon
import app.drivr.traffic.TrafficAhead
import app.drivr.traffic.TrafficEvent
import app.drivr.traffic.TrafficKind
import org.junit.Rule
import org.junit.Test

class AlertCardsScreenshotTest {
  @get:Rule
  val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = false)

  private val a = LatLon(52.0, 5.0)
  private val b = LatLon(52.03, 5.0)

  @Test
  fun alert_cards() {
    val jam = TrafficEvent("jam", TrafficKind.JAM, listOf(a, b), queueLengthM = 3300.0, delaySeconds = 420.0)
    val camera = Enforcement.Camera("n1", a, 100, null)
    val redLight = Enforcement.Camera("n2", a, 50, null, kind = "red light")
    val section = Enforcement.Section("r1", a, b, 100)
    paparazzi.snapshot {
      Column(Modifier.background(Drivr.sky).padding(16.dp)) {
        JamCountdownCard(TrafficAhead(jam, 0.0, endDistanceM = 1200.0, lengthM = 3300.0))
        Spacer(Modifier.height(12.dp))
        EnforcementCard(EnforcementAhead(camera, 850.0), averageKmh = null)
        Spacer(Modifier.height(12.dp))
        EnforcementCard(EnforcementAhead(redLight, 240.0), averageKmh = null)
        Spacer(Modifier.height(12.dp))
        EnforcementCard(EnforcementAhead(section, 1400.0, 4900.0, 3500.0), averageKmh = null)
        Spacer(Modifier.height(12.dp))
        EnforcementCard(EnforcementAhead(section, 0.0, 2100.0, 3500.0), averageKmh = 97)
        Spacer(Modifier.height(12.dp))
        EnforcementCard(EnforcementAhead(section, 0.0, 900.0, 3500.0), averageKmh = 106)
      }
    }
  }
}
