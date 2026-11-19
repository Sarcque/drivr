package app.drivr.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.drivr.R
import app.drivr.voice.VoiceUi

/** A microphone, drawn (no icon font): body, stand and base. */
@Composable
fun MicGlyph(color: Color, modifier: Modifier = Modifier.size(22.dp)) {
  Canvas(modifier) {
    val w = size.width
    val h = size.height
    val sw = 2.dp.toPx()
    drawRoundRect(color, Offset(w * 0.34f, h * 0.06f), Size(w * 0.32f, h * 0.52f), CornerRadius(w * 0.16f))
    drawArc(color, 0f, 180f, false, Offset(w * 0.2f, h * 0.22f), Size(w * 0.6f, h * 0.5f), style = Stroke(sw, cap = StrokeCap.Round))
    drawLine(color, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h * 0.9f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.34f, h * 0.92f), Offset(w * 0.66f, h * 0.92f), sw, StrokeCap.Round)
  }
}

/**
 * The card under the turn banner after "Hey Drivr": listening (with what it hears so far), the
 * answer, or what you can say. Tap to close. Nothing shows while it only waits for the wake
 * phrase; the green mic button in the button column says it is listening.
 */
@Composable
fun VoiceCard(ui: VoiceUi, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
  if (ui is VoiceUi.Off || ui is VoiceUi.Waiting) return
  Column(
      modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(Drivr.cardRadius))
          .background(Drivr.card)
          .clickable(onClick = onDismiss)
          .padding(horizontal = 16.dp, vertical = 14.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      val active = ui is VoiceUi.Awake
      MicBadge(active)
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        when (ui) {
          is VoiceUi.Awake -> {
            Text(stringResource(R.string.voice_listening), color = Drivr.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (ui.heard.isBlank()) stringResource(R.string.voice_say_command) else "“${ui.heard}”",
                color = Drivr.textMuted, fontSize = 15.sp)
          }
          is VoiceUi.Answer -> {
            if (ui.heard.isNotBlank()) Text("“${ui.heard}”", color = Drivr.textMuted, fontSize = 14.sp)
            Text(ui.reply.text, color = if (ui.reply.ok) Drivr.text else Drivr.hint, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp)
          }
          is VoiceUi.Help -> {
            if (ui.heard.isNotBlank()) {
              Text("“${ui.heard}”", color = Drivr.textMuted, fontSize = 14.sp)
              Text(stringResource(R.string.voice_not_understood), color = Drivr.hint, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            } else {
              Text(stringResource(R.string.voice_help_title), color = Drivr.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
          }
          else -> {}
        }
      }
    }
    if (ui is VoiceUi.Help) {
      Spacer(Modifier.size(8.dp))
      VoiceExamples(Modifier.padding(start = 48.dp))
    }
  }
}

/** Examples in the app language. Unit tests check that each one is understood. */
val VOICE_EXAMPLES =
    listOf(
        R.string.voice_ex_tolls, R.string.voice_ex_eta, R.string.voice_ex_fuel, R.string.voice_ex_home,
        R.string.voice_ex_traffic, R.string.voice_ex_turn, R.string.voice_ex_mute, R.string.voice_ex_stop,
    )

@Composable
fun VoiceExamples(modifier: Modifier = Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    VOICE_EXAMPLES.forEach { Text("“${stringResource(it)}”", color = Drivr.text, fontSize = 15.sp) }
  }
}

/** Round mic; pulsing blue rings while it waits for the command. */
@Composable
private fun MicBadge(active: Boolean) {
  val pulse =
      if (active) {
        val t = rememberInfiniteTransition(label = "mic")
        val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "pulse")
        p
      } else 0f
  Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
    if (active) {
      Canvas(Modifier.size(36.dp)) {
        drawCircle(Drivr.accent.copy(alpha = 0.45f * (1f - pulse)), radius = size.minDimension / 2 * (0.7f + 0.3f * pulse))
      }
    }
    Box(Modifier.size(30.dp).clip(CircleShape).background(if (active) Drivr.accent else Drivr.cardBand), contentAlignment = Alignment.Center) {
      MicGlyph(Color.White, Modifier.size(18.dp))
    }
  }
}
