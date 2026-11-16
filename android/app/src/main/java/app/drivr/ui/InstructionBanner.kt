package app.drivr.ui

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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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

private val BannerGreen = Color(0xFF0F5132) // Dutch motorway-sign green, darkened for driving
private val BannerInk = Color(0xFFFFFFFF)
private val BannerSub = Color(0xFFCFE3D8)

/**
 * The top banner: maneuver arrow, distance, where you're going, and the lane strip right under it
 * when the route has lane data for this maneuver.
 */
@Composable
fun InstructionBanner(
    instruction: VisualInstruction,
    distanceToManeuverMeters: Double?,
    modifier: Modifier = Modifier,
) {
  val primary = instruction.primaryContent
  val lanes: List<LaneInfo> = instruction.subContent?.laneInfo ?: primary.laneInfo ?: emptyList()

  Column(
      modifier =
          modifier
              .fillMaxWidth()
              .shadow(10.dp, RoundedCornerShape(18.dp))
              .clip(RoundedCornerShape(18.dp))
              .background(BannerGreen)
              .padding(horizontal = 16.dp, vertical = 14.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        ManeuverImage(primary, tint = BannerInk)
      }
      Spacer(Modifier.width(14.dp))
      Column(Modifier.weight(1f)) {
        distanceToManeuverMeters?.let {
          Text(
              formatDistance(it),
              color = BannerInk,
              fontSize = 30.sp,
              fontWeight = FontWeight.Bold,
          )
        }
        Text(
            primary.text,
            color = BannerInk,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        instruction.secondaryContent?.let {
          Text(it.text, color = BannerSub, fontSize = 15.sp, maxLines = 1)
        }
      }
      if (primary.exitNumbers.isNotEmpty()) {
        Text(
            primary.exitNumbers.joinToString(" "),
            color = BannerGreen,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier =
                Modifier.clip(RoundedCornerShape(6.dp))
                    .background(BannerInk)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
        )
      }
    }
    if (lanes.isNotEmpty()) {
      Spacer(Modifier.height(12.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        LaneGuidanceStrip(lanes)
      }
    }
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
                  text = "A27: Hilversum, Amersfoort",
                  maneuverType = ManeuverType.FORK,
                  maneuverModifier = ManeuverModifier.SLIGHT_RIGHT,
                  roundaboutExitDegrees = null,
                  laneInfo = null,
                  exitNumbers = listOf("33"),
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
                          LaneInfo(false, listOf("straight"), null),
                          LaneInfo(true, listOf("slight right"), "slight right"),
                          LaneInfo(true, listOf("slight right"), "slight right"),
                      ),
                  exitNumbers = emptyList(),
              ),
          triggerDistanceBeforeManeuver = 1000.0,
      ),
      distanceToManeuverMeters = 650.0,
  )
}
