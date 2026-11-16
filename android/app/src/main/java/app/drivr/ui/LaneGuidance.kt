package app.drivr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.ferrostar.LaneInfo

/**
 * The lane strip from OSM `turn:lanes`, as in the prototype: one arrow per lane, the lanes you can
 * use in white with a blue bar underneath, the others in grey. Followed by a short hint.
 */
@Composable
fun LaneGuidanceStrip(lanes: List<LaneInfo>, modifier: Modifier = Modifier) {
  if (lanes.isEmpty()) return
  val laneWidth = if (lanes.size > 5) 34.dp else 42.dp
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      lanes.forEach { LaneArrowCell(it, Modifier.width(laneWidth)) }
    }
    laneHint(lanes)?.let {
      Spacer(Modifier.width(14.dp))
      Text(it, color = Drivr.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
private fun LaneArrowCell(lane: LaneInfo, modifier: Modifier) {
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.fillMaxWidth().height(40.dp)) {
      Canvas(Modifier.size(width = 42.dp, height = 40.dp).align(Alignment.Center)) {
        val stroke = 4.dp.toPx()
        if (lane.active) {
          val dir = lane.activeDirection ?: lane.directions.firstOrNull() ?: "straight"
          drawLaneArrow(laneDirectionAngle(dir) ?: 0f, Drivr.text, stroke)
        } else {
          val dir = lane.directions.firstOrNull() ?: "straight"
          drawLaneArrow(laneDirectionAngle(dir) ?: 0f, Drivr.inactive, stroke)
        }
      }
    }
    Spacer(Modifier.height(5.dp))
    Box(
        Modifier.fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (lane.active) Drivr.accent else androidx.compose.ui.graphics.Color.Transparent)
    )
  }
}

/**
 * "Use the right 2 lanes" and similar. We don't know which lane the car is in (phone GPS can't
 * tell), so this names the lanes rather than saying "move right 1 lane" like the prototype does.
 */
fun laneHint(lanes: List<LaneInfo>): String? {
  val active = lanes.indices.filter { lanes[it].active }
  if (active.isEmpty() || active.size == lanes.size) return null
  val n = active.size
  val word = if (n == 1) "lane" else "$n lanes"
  return when {
    active.last() == lanes.lastIndex && active.first() == lanes.size - n ->
        if (n == 1) "Use the right lane" else "Use the right $word"
    active.first() == 0 && active.last() == n - 1 ->
        if (n == 1) "Use the left lane" else "Use the left $word"
    else -> if (n == 1) "Use lane ${active.first() + 1}" else "Use lanes ${active.first() + 1}–${active.last() + 1}"
  }
}
