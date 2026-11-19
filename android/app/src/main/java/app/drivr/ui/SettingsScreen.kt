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
    saved: app.drivr.places.SavedPlaces = app.drivr.places.SavedPlaces(),
    /** Change home or work (opens the place search). */
    onEditSaved: (app.drivr.PlanField) -> Unit = {},
    onClearSaved: (app.drivr.PlanField) -> Unit = {},
    onClearRecents: () -> Unit = {},
    voice: VoiceSettingsState = VoiceSettingsState(),
) {
  var disclaimer by remember { mutableStateOf(showChecksDisclaimer) }
  Box(modifier.fillMaxSize()) {
  SettingsContent(settings, onChange, onClose, musicAccess, onMusicAccess, language, onLanguage, saved, onEditSaved, onClearSaved, onClearRecents, voice) { disclaimer = true }
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
    saved: app.drivr.places.SavedPlaces,
    onEditSaved: (app.drivr.PlanField) -> Unit,
    onClearSaved: (app.drivr.PlanField) -> Unit,
    onClearRecents: () -> Unit,
    voice: VoiceSettingsState,
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
      Section(stringResource(R.string.settings_voice_commands_section)) { VoiceCommandsSettings(settings, onChange, voice) }
      Section(stringResource(R.string.settings_route)) {
        Toggle(stringResource(R.string.settings_avoid_tolls), stringResource(R.string.settings_avoid_tolls_sub), settings.avoidTolls) { v -> onChange { it.copy(avoidTolls = v) } }
        Toggle(stringResource(R.string.settings_avoid_motorways), stringResource(R.string.settings_avoid_motorways_sub), settings.avoidMotorways) { v -> onChange { it.copy(avoidMotorways = v) } }
        Toggle(stringResource(R.string.settings_avoid_ferries), stringResource(R.string.settings_avoid_ferries_sub), settings.avoidFerries) { v -> onChange { it.copy(avoidFerries = v) } }
      }
      Section(stringResource(R.string.settings_saved_places)) {
        SavedRow(stringResource(R.string.plan_home), saved.home, { onEditSaved(app.drivr.PlanField.HOME) }, { onClearSaved(app.drivr.PlanField.HOME) })
        SavedRow(stringResource(R.string.plan_work), saved.work, { onEditSaved(app.drivr.PlanField.WORK) }, { onClearSaved(app.drivr.PlanField.WORK) })
        Row(Modifier.fillMaxWidth().clickable(enabled = saved.recent.isNotEmpty(), onClick = onClearRecents).padding(horizontal = 16.dp, vertical = 14.dp)) {
          Text(stringResource(R.string.settings_clear_recents), color = if (saved.recent.isNotEmpty()) Drivr.accent else Drivr.inactive, fontSize = 16.sp)
        }
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
      Section(stringResource(R.string.settings_developer)) {
        Toggle(stringResource(R.string.settings_test_drive), stringResource(R.string.settings_test_drive_sub), settings.showTestDrive) { v -> onChange { it.copy(showTestDrive = v) } }
      }
      Text(
          stringResource(R.string.settings_credits),
          color = Drivr.inactive, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
    }
  }
}

/** Everything the voice-commands section needs to know and do, from the view model. */
data class VoiceSettingsState(
    val model: app.drivr.voice.ModelState = app.drivr.voice.ModelState.Missing,
    /** Size of this language's speech model, for the explanation. */
    val modelMb: Int = 40,
    val micAllowed: Boolean = false,
    /** "unsupported" when the speech engine can't run here. */
    val problem: String? = null,
    /** Space used by downloaded models (0 when none). */
    val downloadedMb: Int = 0,
    /** Open with "What you can say" expanded (screenshots). */
    val examplesOpen: Boolean = false,
    val onAskMic: () -> Unit = {},
    val onDownload: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

/** "Hey Drivr": the switch, where it stands (permission, download), examples, and freeing the space. */
@Composable
private fun VoiceCommandsSettings(settings: Settings, onChange: ((Settings) -> Settings) -> Unit, voice: VoiceSettingsState) {
  Toggle(stringResource(R.string.settings_voice_commands), stringResource(R.string.settings_voice_commands_sub, voice.modelMb), settings.voiceCommands) { v ->
    onChange { it.copy(voiceCommands = v) }
  }
  if (settings.voiceCommands) {
    val m = voice.model
    val (text, color, action) =
        when {
          voice.problem == "unsupported" -> Triple(stringResource(R.string.voice_status_unsupported), Drivr.hint, null)
          !voice.micAllowed -> Triple(stringResource(R.string.voice_status_no_mic), Drivr.accent, voice.onAskMic)
          m is app.drivr.voice.ModelState.Downloading ->
              Triple(
                  m.fraction?.let { stringResource(R.string.voice_status_downloading, (it * 100).toInt()) }
                      ?: stringResource(R.string.voice_status_downloading_unknown),
                  Drivr.textMuted, null)
          m is app.drivr.voice.ModelState.Failed -> Triple(stringResource(R.string.voice_status_failed), Drivr.hint, voice.onDownload)
          m == app.drivr.voice.ModelState.Missing -> Triple(stringResource(R.string.voice_status_missing, voice.modelMb), Drivr.accent, voice.onDownload)
          else -> Triple(stringResource(R.string.voice_status_ready), Drivr.ok, null)
        }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = action != null) { action?.invoke() }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      MicGlyph(color, Modifier.size(18.dp))
      Spacer(Modifier.size(10.dp))
      Text(text, color = color, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
    val dl = voice.model as? app.drivr.voice.ModelState.Downloading
    if (dl != null) {
      androidx.compose.material3.LinearProgressIndicator(
          progress = { dl.fraction ?: 0f },
          modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
          color = Drivr.accent,
          trackColor = Drivr.cardBand,
      )
    }
    var open by remember { mutableStateOf(voice.examplesOpen) }
    Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(stringResource(R.string.settings_voice_examples), color = Drivr.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
      Text(if (open) "▴" else "▾", color = Drivr.accent, fontSize = 18.sp)
    }
    if (open) VoiceExamples(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
  } else if (voice.downloadedMb > 0) {
    Row(Modifier.fillMaxWidth().clickable(onClick = voice.onDelete).padding(horizontal = 16.dp, vertical = 14.dp)) {
      Text(stringResource(R.string.settings_voice_delete, voice.downloadedMb), color = Drivr.accent, fontSize = 16.sp)
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

/** Home or work: where it is, tap to change, ✕ to forget it. */
@Composable
private fun SavedRow(title: String, place: app.drivr.places.Place?, onEdit: () -> Unit, onClear: () -> Unit) {
  Row(Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
      Text(title, color = Drivr.text, fontSize = 16.sp)
      Text(
          place?.let { listOfNotNull(it.name, it.address).distinct().joinToString(", ") } ?: stringResource(R.string.settings_not_set),
          color = Drivr.textMuted, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
    if (place != null) {
      Text("✕", color = Drivr.textMuted, fontSize = 16.sp,
          modifier = Modifier.clip(CircleShape).clickable(onClickLabel = stringResource(R.string.settings_forget), onClick = onClear).padding(12.dp))
    } else {
      Text(stringResource(R.string.settings_set), color = Drivr.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(12.dp))
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
