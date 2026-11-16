package app.drivr.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Drivr's visual language, shared with the web prototype: muted and flat, solid dark cards, one
 * accent colour. No glass, no glow, no gradients on UI chrome.
 */
object Drivr {
  val card = Color(0xFF15181D)
  val cardBand = Color(0xFF22262D)
  val cardRadius = 18.dp
  val accent = Color(0xFF3478F6)
  val text = Color(0xFFFFFFFF)
  val textMuted = Color(0xFFA7AFB9)
  val inactive = Color(0xFF5E6670)
  val hint = Color(0xFFF5C04A)
  val ok = Color(0xFF5BD39A)

  // Map / AR palette from the prototype.
  val asphalt = Color(0xFF5A6069)
  val ground = Color(0xFFA9BB98)
  val sky = Color(0xFFDCE4EC)
}

private val refPattern = Regex("^[AENS]\\d+[a-zA-Z]?$")

/** A route number in the Dutch/European style: A red, E green, N yellow, S (city ring) blue. */
@Composable
fun RoadShield(ref: String, modifier: Modifier = Modifier) {
  val (bg, fg) =
      when (ref.first().uppercaseChar()) {
        'A' -> Color(0xFFC8102E) to Color.White
        'E' -> Color(0xFF00843D) to Color.White
        'N' -> Color(0xFFF6C900) to Color.Black
        else -> Color(0xFF1C4E9C) to Color.White
      }
  Text(
      ref,
      color = fg,
      fontWeight = FontWeight.Bold,
      fontSize = 15.sp,
      modifier =
          modifier
              .clip(RoundedCornerShape(5.dp))
              .background(bg)
              .border(1.5.dp, fg, RoundedCornerShape(5.dp))
              .padding(horizontal = 5.dp, vertical = 0.dp),
  )
}

/**
 * Splits a Valhalla banner text like "A27/E30: Breda, Rotterdam" or "RING A10, S111: Utrecht"
 * into route numbers (for shields) and the destination text.
 */
data class SignText(val refs: List<String>, val label: String)

fun parseSignText(text: String): SignText {
  val colon = text.indexOf(':')
  if (colon <= 0) return SignText(emptyList(), text)
  val head = text.substring(0, colon)
  val tokens = head.split(',', '/', ';').flatMap { it.trim().split(' ') }.filter { it.isNotBlank() }
  val refs = tokens.filter { refPattern.matches(it) }
  if (refs.isEmpty()) return SignText(emptyList(), text)
  val rest = tokens.filterNot { refPattern.matches(it) }
  val dest = text.substring(colon + 1).trim().split(",").map { it.trim() }.filter { it.isNotEmpty() }
  return SignText(refs, (rest + dest).joinToString(" · "))
}
