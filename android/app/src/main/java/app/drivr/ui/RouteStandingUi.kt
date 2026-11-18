package app.drivr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.drivr.R
import app.drivr.nav.RouteStanding
import kotlin.math.abs

/** Signal colours for the two "you're not on the route" states. */
object Standing {
  val wrongWay = Color(0xFFE5322D)
  val offRoute = Color(0xFFF59E0B)
}

/** Text for which way the route lies, for an [RouteStanding.OffRoute]. */
@Composable
fun routeDirectionText(off: RouteStanding.OffRoute): String {
  val d = formatDistance(off.distanceM)
  return when {
    abs(off.turnDeg) <= 35 -> stringResource(R.string.ar_route_ahead, d)
    abs(off.turnDeg) >= 145 -> stringResource(R.string.ar_route_behind, d)
    off.turnDeg < 0 -> stringResource(R.string.ar_route_left, d)
    else -> stringResource(R.string.ar_route_right, d)
  }
}

/**
 * The big card shown instead of the turn banner when the driver is going the wrong way (red,
 * U-turn) or has left the route (amber, arrow towards it). Used on the map and in AR.
 */
@Composable
fun RouteStandingCard(
    standing: RouteStanding,
    rerouting: Boolean,
    modifier: Modifier = Modifier,
) {
  val (bg, title, sub) =
      when (standing) {
        is RouteStanding.WrongWay ->
            Triple(Standing.wrongWay, stringResource(R.string.ar_wrong_way), stringResource(R.string.ar_turn_around))
        is RouteStanding.OffRoute ->
            Triple(
                Standing.offRoute,
                stringResource(R.string.ar_off_route),
                if (rerouting) stringResource(R.string.ar_rerouting) else routeDirectionText(standing),
            )
        RouteStanding.OnRoute -> return
      }
  Row(
      modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(Drivr.cardRadius))
          .background(bg)
          .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
      when (standing) {
        is RouteStanding.WrongWay -> UTurnIcon(Color.White, 52.dp)
        is RouteStanding.OffRoute -> DirectionArrow(standing.turnDeg, Color.White, 50.dp)
        else -> {}
      }
    }
    Spacer(Modifier.width(14.dp))
    Column {
      Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, lineHeight = 30.sp)
      Text(sub, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
  }
}

/** A U-turn arrow: up, over the top to the left, and back down. */
@Composable
fun UTurnIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
  Canvas(modifier.size(size)) {
    val w = this.size.width
    val h = this.size.height
    val stroke = w * 0.14f
    val right = w * 0.72f
    val left = w * 0.28f
    val top = h * 0.16f
    val bottom = h * 0.88f
    val r = (right - left) / 2f
    val path =
        Path().apply {
          moveTo(right, bottom)
          lineTo(right, top + r)
          arcTo(Rect(left, top, right, top + 2 * r), 0f, -180f, false)
          lineTo(left, h * 0.62f)
        }
    drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val head =
        Path().apply {
          moveTo(left - w * 0.2f, h * 0.56f)
          lineTo(left + w * 0.2f, h * 0.56f)
          lineTo(left, h * 0.84f)
          close()
        }
    drawPath(head, color)
  }
}

/** A straight arrow turned [turnDeg] from straight up (clockwise). */
@Composable
fun DirectionArrow(turnDeg: Double, color: Color, size: Dp, modifier: Modifier = Modifier) {
  Canvas(modifier.size(size).rotate(turnDeg.toFloat())) {
    val w = this.size.width
    val h = this.size.height
    drawLine(color, Offset(w / 2, h * 0.9f), Offset(w / 2, h * 0.35f), w * 0.14f, cap = StrokeCap.Round)
    drawPath(
        Path().apply {
          moveTo(w * 0.2f, h * 0.42f)
          lineTo(w / 2, h * 0.08f)
          lineTo(w * 0.8f, h * 0.42f)
          close()
        },
        color,
    )
  }
}
