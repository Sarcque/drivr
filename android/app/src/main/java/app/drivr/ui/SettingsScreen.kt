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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.drivr.R
import app.drivr.i18n.AppLanguage
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
    /** Picked language tag, "" to follow the phone. */
    language: String = "",
    onLanguage: (String) -> Unit = {},
    /** Open with the "no guarantee" popup showing (screenshots). */
    showChecksDisclaimer: Boolean = false,
) {
  var disclaimer by remember { mutableStateOf(showChecksDisclaimer) }
  Box(modifier.fillMaxSize()) {
  SettingsContent(settings, onChange, onClose, musicAccess, onMusicAccess, language, onLanguage) { disclaimer = true }
  if (disclaimer) {
    ChecksDisclaimer(
        onCancel = { disclaimer = false },
        onAccept = {
          disclaimer = false
          onChange { it.copy(alwaysAvoidChecks = true) }
        },
    )
  }
  }
}

/**
 * Before "always avoid checks" goes on: it can only avoid checks that a feed reports, in time.
 * Drawn in the screen (not a system dialog) so it matches the app and shows in screenshots.
 */
@Composable
fun ChecksDisclaimer(onCancel: () -> Unit, onAccept: () -> Unit) {
  Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
    Column(
        Modifier.padding(24.dp).clip(RoundedCornerShape(Drivr.cardRadius)).background(Drivr.card).clickable(enabled = false) {}
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Drivr.hint), contentAlignment = Alignment.Center) {
          Text("!", color = Drivr.card, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.size(12.dp))
        Text(stringResource(R.string.checks_disclaimer_title), color = Drivr.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
      }
      Spacer(Modifier.height(12.dp))
      Text(stringResource(R.string.checks_disclaimer_body), color = Drivr.text, fontSize = 15.sp, lineHeight = 21.sp)
      Spacer(Modifier.height(8.dp))
      Text(stringResource(R.string.checks_disclaimer_sources), color = Drivr.textMuted, fontSize = 13.sp, lineHeight = 18.sp)
      Spacer(Modifier.height(16.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.action_cancel), color = Drivr.text, fontSize = 15.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Drivr.cardBand).clickable(onClick = onCancel).padding(vertical = 11.dp),
        )
        Text(
            stringResource(R.string.checks_disclaimer_accept), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Drivr.accent).clickable(onClick = onAccept).padding(vertical = 11.dp),
        )
      }
    }
  }
}

@Composable
private fun SettingsContent(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    onClose: () -> Unit,
    musicAccess: Boolean,
    onMusicAccess: () -> Unit,
    language: String,
    onLanguage: (String) -> Unit,
    askDisclaimer: () -> Unit,
) {
  Column(Modifier.fillMaxSize().background(Color(0xFF0E1013)).statusBarsPadding().navigationBarsPadding()) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(stringResource(R.string.settings_title), color = Drivr.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
      Text(stringResource(R.string.action_done), color = Drivr.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
          modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClose).padding(12.dp))
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
      Section(stringResource(R.string.settings_language)) { LanguagePicker(language, onLanguage) }
      Section(stringResource(R.string.settings_guidance)) {
        Toggle(stringResource(R.string.settings_voice), stringResource(R.string.settings_voice_sub), settings.voice) { v -> onChange { it.copy(voice = v) } }
        Toggle(stringResource(R.string.settings_ar), stringResource(R.string.settings_ar_sub), settings.arEnabled) { v -> onChange { it.copy(arEnabled = v) } }
        Toggle(stringResource(R.string.settings_ar_auto), stringResource(R.string.settings_ar_auto_sub), settings.arAuto, enabled = settings.arEnabled) { v -> onChange { it.copy(arAuto = v) } }
      }
      Section(stringResource(R.string.settings_checks)) {
        Toggle(stringResource(R.string.settings_border_checks), stringResource(R.string.settings_border_checks_sub), settings.borderChecks) { v -> onChange { it.copy(borderChecks = v) } }
        Toggle(stringResource(R.string.settings_always_avoid_border), stringResource(R.string.settings_always_avoid_border_sub), settings.alwaysAvoidBorderChecks) { v -> onChange { it.copy(alwaysAvoidBorderChecks = v) } }
        Toggle(stringResource(R.string.settings_always_avoid_checks), stringResource(R.string.settings_always_avoid_checks_sub), settings.alwaysAvoidChecks) { v ->
          // Switching on first explains the limits; it only goes on after "Understood".
          if (v) askDisclaimer() else onChange { it.copy(alwaysAvoidChecks = false) }
        }
      }
      Section(stringResource(R.string.settings_alerts)) {
        Toggle(stringResource(R.string.settings_cameras), stringResource(R.string.settings_cameras_sub), settings.speedCameras) { v -> onChange { it.copy(speedCameras = v) } }
        Toggle(stringResource(R.string.settings_traffic_alerts), stringResource(R.string.settings_traffic_alerts_sub), settings.trafficAlerts) { v -> onChange { it.copy(trafficAlerts = v) } }
        Toggle(stringResource(R.string.settings_faster_routes), stringResource(R.string.settings_faster_routes_sub), settings.fasterRoutes) { v -> onChange { it.copy(fasterRoutes = v) } }
      }
      Section(stringResource(R.string.settings_music)) {
        Toggle(stringResource(R.string.settings_music_driving), stringResource(R.string.settings_music_driving_sub),
            settings.musicWhileDriving) { v -> onChange { it.copy(musicWhileDriving = v) } }
        Row(Modifier.fillMaxWidth().clickable(onClick = onMusicAccess).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_music_access), color = Drivr.text, fontSize = 16.sp)
            Text(stringResource(R.string.settings_music_access_sub), color = Drivr.textMuted, fontSize = 13.sp)
          }
          Text(stringResource(if (musicAccess) R.string.settings_on else R.string.action_allow), color = if (musicAccess) Drivr.ok else Drivr.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
      }
      Section(stringResource(R.string.settings_map)) {
        Toggle(stringResource(R.string.settings_traffic_map), stringResource(R.string.settings_traffic_map_sub), settings.trafficOnMap) { v -> onChange { it.copy(trafficOnMap = v) } }
        Toggle(stringResource(R.string.settings_3d), stringResource(R.string.settings_3d_sub), settings.buildings3d) { v -> onChange { it.copy(buildings3d = v) } }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(stringResource(R.string.settings_distances), color = Drivr.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
          Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Drivr.cardBand).padding(3.dp)) {
            Units.entries.forEach { u ->
              val on = settings.units == u
              Text(stringResource(u.labelRes), color = if (on) Color.White else Drivr.textMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                  modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Drivr.accent else Color.Transparent)
                      .clickable { onChange { it.copy(units = u) } }.padding(horizontal = 12.dp, vertical = 7.dp))
            }
          }
        }
      }
      Text(
          stringResource(R.string.settings_credits),
          color = Drivr.inactive, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
    }
  }
}

/** The app language: the phone's, or one of Drivr's translations, each named in its own language. */
@Composable
private fun LanguagePicker(language: String, onLanguage: (String) -> Unit) {
  var open by remember { mutableStateOf(false) }
  val phone = stringResource(R.string.settings_language_system)
  val names = AppLanguage.SUPPORTED.toMap()
  Box {
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(names[language] ?: phone, color = Drivr.text, fontSize = 16.sp)
        Text(stringResource(R.string.settings_language_sub), color = Drivr.textMuted, fontSize = 13.sp)
      }
      Text("▾", color = Drivr.accent, fontSize = 18.sp)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Drivr.card) {
      (listOf("" to phone) + AppLanguage.SUPPORTED).forEach { (tag, name) ->
        DropdownMenuItem(
            text = { Text(name, color = if (tag == language) Drivr.accent else Drivr.text, fontSize = 16.sp) },
            onClick = {
              open = false
              if (tag != language) onLanguage(tag)
            },
        )
      }
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
