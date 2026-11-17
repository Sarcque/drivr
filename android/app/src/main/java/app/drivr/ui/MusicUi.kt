package app.drivr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.drivr.music.MusicState
import app.drivr.music.NowPlaying

/** The one music button: a round button with a note, the same size as the gear next to it. */
@Composable
fun MusicButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(modifier.size(52.dp).clip(CircleShape).background(Drivr.card).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
    NoteGlyph(Drivr.text)
  }
}

/**
 * Compact now-playing card for whichever app is playing (Spotify, YouTube Music, ...). Shows a
 * friendly prompt instead when Drivr may not see players yet, and a hint when nothing plays.
 */
@Composable
fun MusicCard(
    state: MusicState,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenPlayer: () -> Unit,
    onAllow: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Box(modifier.fillMaxWidth().clip(RoundedCornerShape(Drivr.cardRadius)).background(Drivr.card)) {
    val playing = state.nowPlaying
    when {
      !state.hasAccess -> AccessPrompt(onAllow, onClose)
      playing == null -> Message("Nothing is playing", "Start Spotify or another music app, then come back here.", onClose)
      else -> Player(playing, onPlayPause, onPrevious, onNext, onOpenPlayer, onClose)
    }
  }
}

@Composable
private fun Player(np: NowPlaying, onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit, onClose: () -> Unit) {
  Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
    Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
      Cover(np)
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(np.title, color = Drivr.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(np.artist, np.appLabel).joinToString(" · "), color = Drivr.textMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    Spacer(Modifier.width(4.dp))
    if (np.canSkip) ControlButton(onPrevious, 44) { SkipGlyph(Drivr.text, forward = false) }
    ControlButton(onPlayPause, 52, Drivr.accent) { if (np.playing) PauseGlyph(Color.White) else PlayGlyph(Color.White) }
    if (np.canSkip) ControlButton(onNext, 44) { SkipGlyph(Drivr.text, forward = true) }
    ControlButton(onClose, 36) { CloseGlyph(Drivr.textMuted) }
  }
}

@Composable
private fun Cover(np: NowPlaying) {
  val art = np.art
  val bmp = remember(art) { art?.asImageBitmap() }
  Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Drivr.cardBand), contentAlignment = Alignment.Center) {
    if (bmp != null) Image(bmp, null, Modifier.size(56.dp), contentScale = ContentScale.Crop) else NoteGlyph(Drivr.textMuted)
  }
}

@Composable
private fun AccessPrompt(onAllow: () -> Unit, onClose: () -> Unit) {
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(verticalAlignment = Alignment.Top) {
      Box(Modifier.size(44.dp).clip(CircleShape).background(Drivr.cardBand), contentAlignment = Alignment.Center) { NoteGlyph(Drivr.text) }
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text("Control your music from Drivr", color = Drivr.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Works with Spotify, YouTube Music and other players, no login needed. Android calls this " +
                "\"notification access\"; Drivr only uses it to see what is playing.",
            color = Drivr.textMuted, fontSize = 13.sp)
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Pill("Not now", primary = false, onClick = onClose, modifier = Modifier.weight(1f))
      Pill("Allow", primary = true, onClick = onAllow, modifier = Modifier.weight(1f))
    }
  }
}

@Composable
private fun Message(title: String, body: String, onClose: () -> Unit) {
  Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(title, color = Drivr.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
      Text(body, color = Drivr.textMuted, fontSize = 13.sp)
    }
    ControlButton(onClose, 36) { CloseGlyph(Drivr.textMuted) }
  }
}

@Composable
private fun Pill(text: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier) {
  Box(
      modifier.clip(RoundedCornerShape(14.dp)).background(if (primary) Drivr.accent else Drivr.cardBand).clickable(onClick = onClick).padding(vertical = 12.dp),
      contentAlignment = Alignment.Center,
  ) {
    Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun ControlButton(onClick: () -> Unit, sizeDp: Int, background: Color = Color.Transparent, content: @Composable () -> Unit) {
  Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(background).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

// ---- Glyphs (drawn, like the rest of Drivr's icons) ---------------------------------------------

@Composable
fun NoteGlyph(color: Color, modifier: Modifier = Modifier.size(22.dp)) {
  Canvas(modifier) {
    val u = size.width / 24f
    val stroke = 2.4f * u
    drawLine(color, Offset(9f * u, 18f * u), Offset(9f * u, 4.5f * u), stroke, StrokeCap.Round)
    drawLine(color, Offset(19f * u, 16f * u), Offset(19f * u, 2.5f * u), stroke, StrokeCap.Round)
    drawLine(color, Offset(9f * u, 4.5f * u), Offset(19f * u, 2.5f * u), stroke * 1.3f, StrokeCap.Round)
    drawCircle(color, 3.3f * u, Offset(5.9f * u, 18.5f * u))
    drawCircle(color, 3.3f * u, Offset(15.9f * u, 16.5f * u))
  }
}

@Composable
private fun PlayGlyph(color: Color) {
  Canvas(Modifier.size(20.dp)) {
    val w = size.width
    drawPath(Path().apply { moveTo(w * 0.28f, w * 0.16f); lineTo(w * 0.86f, w * 0.5f); lineTo(w * 0.28f, w * 0.84f); close() }, color)
  }
}

@Composable
private fun PauseGlyph(color: Color) {
  Canvas(Modifier.size(20.dp)) {
    val w = size.width
    listOf(0.22f, 0.58f).forEach { x ->
      drawRoundRect(color, Offset(w * x, w * 0.16f), Size(w * 0.2f, w * 0.68f), CornerRadius(w * 0.05f))
    }
  }
}

@Composable
private fun SkipGlyph(color: Color, forward: Boolean) {
  Canvas(Modifier.size(20.dp)) {
    val w = size.width
    fun x(f: Float) = if (forward) w * f else w * (1f - f)
    drawPath(Path().apply { moveTo(x(0.14f), w * 0.2f); lineTo(x(0.7f), w * 0.5f); lineTo(x(0.14f), w * 0.8f); close() }, color)
    drawLine(color, Offset(x(0.8f), w * 0.2f), Offset(x(0.8f), w * 0.8f), w * 0.12f, StrokeCap.Round)
  }
}

@Composable
private fun CloseGlyph(color: Color) {
  Canvas(Modifier.size(14.dp)) {
    val w = size.width
    drawLine(color, Offset(0f, 0f), Offset(w, w), w * 0.16f, StrokeCap.Round)
    drawLine(color, Offset(w, 0f), Offset(0f, w), w * 0.16f, StrokeCap.Round)
  }
}
