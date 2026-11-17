package app.drivr.ui

import app.drivr.i18n.L10n
import app.drivr.R
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
fun LaneGuidanceStrip(
    lanes: List<LaneInfo>,
    modifier: Modifier = Modifier,
    distanceToManeuverM: Double? = null,
    motorway: Boolean = false,
) {
  if (lanes.isEmpty()) return
  val laneWidth = if (lanes.size > 5) 34.dp else 42.dp
  val advice = laneAdvice(lanes, distanceToManeuverM, motorway)
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      lanes.forEach { LaneArrowCell(it, Modifier.width(laneWidth)) }
    }
    advice?.let {
      Spacer(Modifier.width(14.dp))
      Text(
          it.text,
          color = if (it.urgent) LaneColors.urgent else Drivr.text,
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

object LaneColors {
  val urgent = androidx.compose.ui.graphics.Color(0xFFFFB020)
}

/** What the lane strip says next to the arrows, and whether it's a "do it now" prompt. */
data class LaneAdvice(val text: String, val urgent: Boolean)

/** Metres of road a driver needs per lane change, unhurried, at motorway speed. */
const val METRES_PER_LANE_CHANGE = 400.0

/** The last stretch before the maneuver, where at most one lane change should be left. */
const val FINAL_STRETCH_M = 500.0

/** Extra warning before the "move now" point on motorways, so nobody is surprised. */
const val MOTORWAY_HEADS_UP_M = 1500.0

/**
 * The most lane changes the driver may need: from the lane furthest from a usable one. The phone
 * can't tell which lane the car is in, so plan for the worst case (e.g. leftmost lane to a
 * right-hand exit lane = 4 changes on a 5-lane road).
 */
fun laneChangesNeeded(lanes: List<LaneInfo>): Int {
  val active = lanes.indices.filter { lanes[it].active }
  if (active.isEmpty() || active.size == lanes.size) return 0
  return lanes.indices.filter { it !in active }.maxOf { i -> active.minOf { kotlin.math.abs(it - i) } }
}

/**
 * Where to start moving over: one change per [METRES_PER_LANE_CHANGE], all but one done before
 * the final [FINAL_STRETCH_M], plus one change's worth of slack for traffic. Three changes →
 * start 1.7 km out (500 + 2 × 400 + 400).
 */
fun laneChangeStartM(changes: Int): Double =
    if (changes <= 0) 0.0 else FINAL_STRETCH_M + changes * METRES_PER_LANE_CHANGE

/**
 * Progressive lane advice, so nobody has to cross three lanes in the last few hundred metres:
 * - far out on a motorway: a heads-up ("Keep right soon: up to 3 lane changes");
 * - from [laneChangeStartM]: "Move right now · up to 3 lanes" (urgent colour);
 * - in the final stretch: "Use the right lane".
 */
fun laneAdvice(lanes: List<LaneInfo>, distanceM: Double?, motorway: Boolean): LaneAdvice? {
  val hint = laneHint(lanes) ?: return null
  val changes = laneChangesNeeded(lanes)
  if (distanceM == null || changes == 0) return LaneAdvice(hint, urgent = false)
  val side = laneSide(lanes)
  val start = laneChangeStartM(changes)
  val lanesWord = L10n.p(R.plurals.lane_changes, changes)
  return when {
    distanceM <= FINAL_STRETCH_M -> LaneAdvice(hint, urgent = changes > 1)
    distanceM <= start -> {
      val id = when (side) { "right" -> R.string.lane_now_right; "left" -> R.string.lane_now_left; else -> R.string.lane_now }
      LaneAdvice(L10n.s(id, lanesWord), urgent = true)
    }
    motorway && distanceM <= start + MOTORWAY_HEADS_UP_M -> {
      val id = when (side) { "right" -> R.string.lane_soon_right; "left" -> R.string.lane_soon_left; else -> R.string.lane_soon }
      LaneAdvice(L10n.s(id, lanesWord), urgent = false)
    }
    else -> LaneAdvice(hint, urgent = false)
  }
}

/** "right" when the usable lanes are all on the right, "left" for the left, else null. */
fun laneSide(lanes: List<LaneInfo>): String? {
  val active = lanes.indices.filter { lanes[it].active }
  if (active.isEmpty()) return null
  return when {
    active.last() == lanes.lastIndex && active.first() > 0 -> "right"
    active.first() == 0 && active.last() < lanes.lastIndex -> "left"
    else -> null
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
  return when {
    active.last() == lanes.lastIndex && active.first() == lanes.size - n -> L10n.p(R.plurals.lane_use_right_n, n)
    active.first() == 0 && active.last() == n - 1 -> L10n.p(R.plurals.lane_use_left_n, n)
    else ->
        if (n == 1) L10n.s(R.string.lane_use_one, active.first() + 1)
        else L10n.s(R.string.lane_use_range, active.first() + 1, active.last() + 1)
  }
}
