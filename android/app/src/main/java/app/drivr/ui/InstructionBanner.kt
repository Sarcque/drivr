package app.drivr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stadiamaps.ferrostar.composeui.views.components.maneuver.ManeuverImage
import java.util.Locale
import kotlin.math.roundToInt
import uniffi.ferrostar.LaneInfo
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent

/**
 * The maneuver card: arrow, distance, what to do with route shields and destinations, the lane
 * strip in its own band, and a "Then" row for the maneuver after this one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstructionBanner(
    instruction: VisualInstruction,
    distanceToManeuverMeters: Double?,
    modifier: Modifier = Modifier,
    then: VisualInstructionContent? = null,
) {
  val primary = instruction.primaryContent
  val lanes: List<LaneInfo> = instruction.subContent?.laneInfo ?: primary.laneInfo ?: emptyList()
  val sign = parseSignText(primary.text)
  val action = actionPhrase(primary.maneuverType, primary.maneuverModifier)

  Column(
      modifier
          .fillMaxWidth()
          .shadow(8.dp, RoundedCornerShape(Drivr.cardRadius))
          .clip(RoundedCornerShape(Drivr.cardRadius))
          .background(Drivr.card)
  ) {
    Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp)) {
      Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        ManeuverImage(primary, tint = Drivr.text)
      }
      Spacer(Modifier.width(14.dp))
      Column(Modifier.weight(1f)) {
        distanceToManeuverMeters?.let {
          Text(formatDistance(it), color = Drivr.text, fontSize = 38.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
          Text(
              action ?: sign.label,
              color = Drivr.text,
              fontSize = 21.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.align(Alignment.CenterVertically),
          )
          sign.refs.forEach { RoadShield(it, Modifier.align(Alignment.CenterVertically)) }
        }
        val sub = listOfNotNull(if (action != null) sign.label else null, instruction.secondaryContent?.text)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        if (sub.isNotEmpty()) {
          Text(sub, color = Drivr.textMuted, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
      }
    }
    if (lanes.isNotEmpty()) {
      LaneGuidanceStrip(
          lanes,
          Modifier.fillMaxWidth().background(Drivr.cardBand).padding(horizontal = 18.dp, vertical = 12.dp),
      )
    }
    then?.let { next ->
      Row(
          Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Then", color = Drivr.text, fontSize = 15.sp)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(22.dp)) { ManeuverImage(next, tint = Drivr.text) }
        Spacer(Modifier.width(10.dp))
        val nextAction = actionPhrase(next.maneuverType, next.maneuverModifier)
        val nextSign = parseSignText(next.text)
        Text(
            listOfNotNull(nextAction?.lowercase(Locale.ROOT), nextSign.label.ifBlank { null }).joinToString(" · "),
            color = Drivr.text,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/** Short verb phrase for the headline: "Keep right", "Take the exit", "Turn left". */
fun actionPhrase(type: ManeuverType?, modifier: ManeuverModifier?): String? {
  val side =
      when (modifier) {
        ManeuverModifier.LEFT, ManeuverModifier.SLIGHT_LEFT, ManeuverModifier.SHARP_LEFT -> "left"
        ManeuverModifier.RIGHT, ManeuverModifier.SLIGHT_RIGHT, ManeuverModifier.SHARP_RIGHT -> "right"
        else -> null
      }
  return when (type) {
    ManeuverType.FORK -> side?.let { "Keep $it" }
    ManeuverType.OFF_RAMP -> "Take the exit"
    ManeuverType.ON_RAMP -> "Take the ramp"
    ManeuverType.MERGE -> "Merge"
    ManeuverType.TURN, ManeuverType.END_OF_ROAD ->
        when (modifier) {
          ManeuverModifier.SLIGHT_LEFT, ManeuverModifier.SLIGHT_RIGHT -> side?.let { "Bear $it" }
          ManeuverModifier.U_TURN -> "Make a U-turn"
          else -> side?.let { "Turn $it" }
        }
    ManeuverType.ROUNDABOUT, ManeuverType.ROTARY -> "At the roundabout"
    ManeuverType.ARRIVE -> "Arrive"
    else -> null
  }
}

fun formatDistance(meters: Double): String =
    when {
      meters >= 10_000 -> "${(meters / 1000).roundToInt()} km"
      meters >= 1_000 -> String.format(Locale.ROOT, "%.1f km", meters / 1000)
      meters >= 100 -> "${(meters / 50).roundToInt() * 50} m"
      else -> "${(meters / 10).roundToInt() * 10} m"
    }

@Preview(widthDp = 400)
@Composable
private fun InstructionBannerPreview() {
  InstructionBanner(
      VisualInstruction(
          primaryContent =
              VisualInstructionContent(
                  text = "A27/E30: Breda, Rotterdam",
                  maneuverType = ManeuverType.FORK,
                  maneuverModifier = ManeuverModifier.SLIGHT_RIGHT,
                  roundaboutExitDegrees = null,
                  laneInfo = null,
                  exitNumbers = emptyList(),
              ),
          secondaryContent = null,
          subContent =
              VisualInstructionContent(
                  text = "",
                  maneuverType = null,
                  maneuverModifier = null,
                  roundaboutExitDegrees = null,
                  laneInfo =
                      listOf(
                          LaneInfo(false, listOf("straight"), null),
                          LaneInfo(true, listOf("slight right"), "slight right"),
                          LaneInfo(true, listOf("slight right"), "slight right"),
                      ),
                  exitNumbers = emptyList(),
              ),
          triggerDistanceBeforeManeuver = 1000.0,
      ),
      distanceToManeuverMeters = 750.0,
  )
}
