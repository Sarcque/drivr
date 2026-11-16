package app.drivr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import uniffi.ferrostar.LaneInfo

object RoadColors {
  val asphalt = Color(0xFF2B3036)
  val asphaltEdge = Color(0xFF1E2227)
  val marking = Color(0xFFE9ECEF)
  val markingDim = Color(0x66E9ECEF)
  val activeLane = Color(0xFF2F8CFF)
  val arrowInactive = Color(0xFF6B737C)
}

/**
 * A strip of road surface, one tile per lane, drawn like the real carriageway: dashed white lane
 * dividers, solid edge lines, and the painted arrows from OSM `turn:lanes`. The lanes you should
 * use get a blue wash and show the arrow you will follow in bright white.
 */
@Composable
fun LaneGuidanceStrip(lanes: List<LaneInfo>, modifier: Modifier = Modifier) {
  if (lanes.isEmpty()) return
  val laneWidth = if (lanes.size > 5) 44.dp else 54.dp
  Row(
      modifier =
          modifier
              .clip(RoundedCornerShape(12.dp))
              .background(RoadColors.asphaltEdge)
              .padding(4.dp)
  ) {
    lanes.forEachIndexed { index, lane ->
      LaneTile(
          lane = lane,
          isFirst = index == 0,
          isLast = index == lanes.lastIndex,
          modifier = Modifier.width(laneWidth).height(64.dp),
      )
    }
  }
}

@Composable
private fun LaneTile(lane: LaneInfo, isFirst: Boolean, isLast: Boolean, modifier: Modifier) {
  Box(modifier) {
    Canvas(Modifier.matchParentSize()) {
      drawRect(RoadColors.asphalt)
      if (lane.active) drawRect(RoadColors.activeLane.copy(alpha = 0.28f))

      val line = 2.dp.toPx()
      val dash = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 7.dp.toPx()))
      // Solid lines on the outer edges of the carriageway, dashed between lanes.
      if (isFirst) {
        drawLine(RoadColors.marking, Offset(line, 0f), Offset(line, size.height), line)
      }
      if (isLast) {
        drawLine(
            RoadColors.marking,
            Offset(size.width - line, 0f),
            Offset(size.width - line, size.height),
            line,
        )
      } else {
        drawLine(
            RoadColors.markingDim,
            Offset(size.width, 0f),
            Offset(size.width, size.height),
            line,
            pathEffect = dash,
        )
      }

      // Arrows you won't take first (dim), then the one you will (bright).
      val stroke = 4.5.dp.toPx()
      val chosen = lane.activeDirection
      lane.directions.forEach { dir ->
        if (lane.active && dir == chosen) return@forEach
        val angle = laneDirectionAngle(dir) ?: return@forEach
        drawLaneArrow(
            angle,
            if (lane.active) RoadColors.markingDim else RoadColors.arrowInactive,
            stroke,
        )
      }
      if (lane.active) {
        val dir = chosen ?: lane.directions.firstOrNull() ?: "straight"
        drawLaneArrow(laneDirectionAngle(dir) ?: 0f, RoadColors.marking, stroke)
      }
    }
  }
}

@Preview
@Composable
private fun LaneGuidanceStripPreview() {
  LaneGuidanceStrip(
      listOf(
          LaneInfo(false, listOf("straight"), null),
          LaneInfo(false, listOf("straight"), null),
          LaneInfo(false, listOf("straight"), null),
          LaneInfo(true, listOf("straight", "slight right"), "slight right"),
          LaneInfo(true, listOf("slight right"), "slight right"),
      )
  )
}
