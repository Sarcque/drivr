package app.drivr.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turn angle in degrees for an OSM / Valhalla lane direction ("left", "slight right", ...).
 * 0 is straight ahead, negative is left, positive is right.
 */
fun laneDirectionAngle(direction: String): Float? =
    when (direction.trim().lowercase().replace('_', ' ')) {
      "straight", "through", "none", "" -> 0f
      "slight left" -> -40f
      "slight right" -> 40f
      "left" -> -90f
      "right" -> 90f
      "sharp left" -> -135f
      "sharp right" -> 135f
      "uturn", "u-turn", "reverse", "left uturn" -> -180f
      "right uturn" -> 180f
      "merge to left" -> -25f
      "merge to right" -> 25f
      else -> null
    }

/**
 * Draws one painted road arrow, the way it looks on the asphalt: a straight stem that bends into
 * the turn and ends in a solid head.
 */
fun DrawScope.drawLaneArrow(angleDeg: Float, color: Color, strokeWidth: Float) {
  val w = size.width
  val h = size.height
  val cx = w / 2f
  val bottom = Offset(cx, h * 0.94f)
  val bend = Offset(cx, h * 0.52f)
  val headLen = strokeWidth * 2.1f
  val stroke = Stroke(strokeWidth, cap = StrokeCap.Butt, join = StrokeJoin.Round)

  val path = Path()
  path.moveTo(bottom.x, bottom.y)

  if (abs(angleDeg) >= 179f) {
    // U-turn: up, around, and back down on the turning side.
    val side = if (angleDeg < 0) -1f else 1f
    val r = w * 0.2f
    val x2 = cx + side * 2 * r
    path.lineTo(cx, h * 0.38f)
    path.cubicTo(cx, h * 0.10f, x2, h * 0.10f, x2, h * 0.38f)
    val tip = Offset(x2, h * 0.62f)
    path.lineTo(x2, tip.y - headLen * 0.6f)
    drawPath(path, color, style = stroke)
    drawHead(tip, 0f, 1f, headLen, strokeWidth, color)
    return
  }

  val rad = Math.toRadians(angleDeg.toDouble())
  // Direction of the tip in screen coordinates (up is -y).
  val dx = sin(rad).toFloat()
  val dy = -cos(rad).toFloat()

  val reach = if (angleDeg == 0f) h * 0.44f else w * 0.34f
  val tip = Offset(bend.x + dx * reach, bend.y + dy * reach)
  path.lineTo(bend.x, bend.y)
  if (angleDeg == 0f) {
    path.lineTo(tip.x, tip.y + headLen * 0.6f)
  } else {
    val ctrl = Offset(bend.x, bend.y - reach * 0.55f)
    val stop = Offset(tip.x - dx * headLen * 0.6f, tip.y - dy * headLen * 0.6f)
    path.quadraticTo(ctrl.x, ctrl.y, stop.x, stop.y)
  }
  drawPath(path, color, style = stroke)
  drawHead(tip, dx, dy, headLen, strokeWidth, color)
}

private fun DrawScope.drawHead(
    tip: Offset,
    dx: Float,
    dy: Float,
    len: Float,
    strokeWidth: Float,
    color: Color,
) {
  val base = Offset(tip.x - dx * len, tip.y - dy * len)
  val px = -dy
  val py = dx
  val half = strokeWidth * 1.35f
  val head =
      Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + px * half, base.y + py * half)
        lineTo(base.x - px * half, base.y - py * half)
        close()
      }
  drawPath(head, color)
}
