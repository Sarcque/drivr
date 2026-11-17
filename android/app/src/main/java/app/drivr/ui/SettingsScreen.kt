package app.drivr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.drivr.settings.Settings
import app.drivr.settings.Units

/** Settings: the switches that used to sit on the map, plus map and alert options. */
@Composable
fun SettingsScreen(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    musicAccess: Boolean = false,
    onMusicAccess: () -> Unit = {},
) {
  Column(modifier.fillMaxSize().background(Color(0xFF0E1013)).statusBarsPadding().navigationBarsPadding()) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("Settings", color = Drivr.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
      Text("Done", color = Drivr.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
          modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClose).padding(12.dp))
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
      Section("Guidance") {
        Toggle("Voice guidance", "Spoken turn instructions", settings.voice) { v -> onChange { it.copy(voice = v) } }
        Toggle("AR junction view", "Camera view with the route drawn on the road", settings.arEnabled) { v -> onChange { it.copy(arEnabled = v) } }
        Toggle("Open AR before turns", "Shows AR by itself about 300 m before each turn", settings.arAuto, enabled = settings.arEnabled) { v -> onChange { it.copy(arAuto = v) } }
      }
      Section("Alerts") {
        Toggle("Speed cameras", "Fixed cameras and average-speed checks from OpenStreetMap", settings.speedCameras) { v -> onChange { it.copy(speedCameras = v) } }
        Toggle("Traffic alerts", "Jams, accidents and closures ahead (NDW, Netherlands)", settings.trafficAlerts) { v -> onChange { it.copy(trafficAlerts = v) } }
        Toggle("Suggest faster routes", "When a jam is ahead, offer a quicker way. You choose; it never switches by itself", settings.fasterRoutes) { v -> onChange { it.copy(fasterRoutes = v) } }
      }
      Section("Music") {
        Toggle("Music controls while driving", "Adds Music to the driving menu; the card stays hidden until you open it",
            settings.musicWhileDriving) { v -> onChange { it.copy(musicWhileDriving = v) } }
        Row(Modifier.fillMaxWidth().clickable(onClick = onMusicAccess).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text("Access to your music player", color = Drivr.text, fontSize = 16.sp)
            Text("Spotify, YouTube Music and others. No login needed", color = Drivr.textMuted, fontSize = 13.sp)
          }
          Text(if (musicAccess) "On" else "Allow", color = if (musicAccess) Drivr.ok else Drivr.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
      }
      Section("Map") {
        Toggle("Traffic on the map", "Colour jams and show incidents", settings.trafficOnMap) { v -> onChange { it.copy(trafficOnMap = v) } }
        Toggle("3D buildings", "Turn off for a flatter, calmer map", settings.buildings3d) { v -> onChange { it.copy(buildings3d = v) } }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("Distances", color = Drivr.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
          Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Drivr.cardBand).padding(3.dp)) {
            Units.entries.forEach { u ->
              val on = settings.units == u
              Text(u.label, color = if (on) Color.White else Drivr.textMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                  modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Drivr.accent else Color.Transparent)
                      .clickable { onChange { it.copy(units = u) } }.padding(horizontal = 12.dp, vertical = 7.dp))
            }
          }
        }
      }
      Text(
          "Map data © OpenStreetMap contributors · Tiles OpenFreeMap · Routing Valhalla (FOSSGIS) · Traffic NDW",
          color = Drivr.inactive, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
    }
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Column {
    Text(title.uppercase(), color = Drivr.textMuted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Drivr.card)) { content() }
  }
}

@Composable
private fun Toggle(title: String, subtitle: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
  Row(
      Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!value) }.padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, color = if (enabled) Drivr.text else Drivr.inactive, fontSize = 16.sp)
      Text(subtitle, color = if (enabled) Drivr.textMuted else Drivr.inactive, fontSize = 13.sp)
    }
    Spacer(Modifier.size(12.dp))
    Switch(
        checked = value && enabled,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(checkedTrackColor = Drivr.accent, checkedThumbColor = Color.White),
    )
  }
}

/** Gear button for the browse screen, next to the search bar. */
@Composable
fun SettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(modifier.size(52.dp).clip(CircleShape).background(Drivr.card).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
    GearGlyph(Drivr.text)
  }
}

@Composable
fun GearGlyph(color: Color) {
  androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
    val c = center
    val r = size.minDimension / 2
    for (i in 0 until 8) {
      val a = Math.toRadians(i * 45.0)
      drawLine(color, c + androidx.compose.ui.geometry.Offset((kotlin.math.cos(a) * r * 0.55).toFloat(), (kotlin.math.sin(a) * r * 0.55).toFloat()),
          c + androidx.compose.ui.geometry.Offset((kotlin.math.cos(a) * r * 0.95).toFloat(), (kotlin.math.sin(a) * r * 0.95).toFloat()),
          strokeWidth = r * 0.28f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
    drawCircle(color, r * 0.6f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.22f))
  }
}
