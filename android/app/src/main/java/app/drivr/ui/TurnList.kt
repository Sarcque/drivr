package app.drivr.ui

import androidx.compose.ui.res.stringResource
import app.drivr.i18n.L10n
import app.drivr.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stadiamaps.ferrostar.composeui.views.components.maneuver.ManeuverImage
import uniffi.ferrostar.RouteStep

/** The turn list from the prototype's overview: number, icon, action with shields, lane advice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TurnList(
    steps: List<RouteStep>,
    distanceToNextManeuver: Double,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(
      modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(Drivr.cardRadius))
          .background(Drivr.card)
          .padding(vertical = 8.dp)
  ) {
    Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(stringResource(R.string.turns_title), color = Drivr.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
      TextButton(onClick = onClose) { Text(stringResource(R.string.action_close), color = Drivr.accent) }
    }
    LazyColumn(Modifier.heightIn(max = 420.dp)) {
      itemsIndexed(steps.drop(1)) { i, step ->
        val visual = step.visualInstructions.firstOrNull() ?: return@itemsIndexed
        val content = visual.primaryContent
        val sign = parseSignText(content.text)
        val lanes = visual.subContent?.laneInfo.orEmpty()
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
          Box(Modifier.size(24.dp).clip(CircleShape).background(Drivr.accent), contentAlignment = Alignment.Center) {
            Text("${i + 1}", color = Drivr.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }
          Spacer(Modifier.width(10.dp))
          Box(Modifier.size(28.dp)) { ManeuverImage(content, tint = Drivr.text) }
          Spacer(Modifier.width(10.dp))
          Column(Modifier.weight(1f)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(
                  actionPhrase(content.maneuverType, content.maneuverModifier) ?: sign.label,
                  color = Drivr.text,
                  fontSize = 16.sp,
                  fontWeight = FontWeight.SemiBold,
              )
              sign.refs.forEach { RoadShield(it) }
            }
            if (sign.label.isNotBlank() && actionPhrase(content.maneuverType, content.maneuverModifier) != null) {
              Text(sign.label, color = Drivr.textMuted, fontSize = 14.sp)
            }
            laneSummary(lanes)?.let { Text(it, color = Drivr.textMuted, fontSize = 13.sp) }
          }
          Text(formatDistance(distanceToNextManeuver + steps.subList(1, i + 1).sumOf { it.distance }), color = Drivr.textMuted, fontSize = 13.sp)
        }
        HorizontalDivider(color = Drivr.cardBand)
      }
    }
  }
}

/** "Use lanes 2 & 3 of 3", as in the prototype. */
fun laneSummary(lanes: List<uniffi.ferrostar.LaneInfo>): String? {
  val active = lanes.indices.filter { lanes[it].active }.map { it + 1 }
  if (active.isEmpty() || active.size == lanes.size) return null
  return if (active.size == 1) L10n.s(R.string.lane_summary_one, active[0], lanes.size)
  else L10n.s(R.string.lane_summary_many, active.dropLast(1).joinToString(", "), active.last(), lanes.size)
}
