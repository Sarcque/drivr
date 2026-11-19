package app.drivr.ui

import androidx.compose.ui.res.stringResource
import app.drivr.i18n.L10n
import app.drivr.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Bottom row while driving: speed limit + current speed on the left, then one low arrival card.
 * Tapping the arrival card ends the trip.
 */
@Composable
fun TripCards(
    speedLimitKmh: Int?,
    speedKmh: Int?,
    distanceRemainingM: Double,
    durationRemainingS: Double,
    onEnd: (() -> Unit)?,
    trafficDelayS: Double = 0.0,
    modifier: Modifier = Modifier,
    now: LocalTime = LocalTime.now(),
) {
  Row(
      modifier.fillMaxWidth().height(IntrinsicSize.Min),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    SpeedCard(speedLimitKmh, speedKmh, Modifier.fillMaxHeight())
    // One low row: arrival time on the left, distance/time and the traffic delay beside it, so
    // the bottom of the screen stays short and the road above it stays clear.
    Row(
        Modifier.weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(Drivr.cardRadius))
            .background(Drivr.card)
            .let { if (onEnd != null) it.clickable(onClick = onEnd) else it }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column {
        Text(stringResource(R.string.trip_arrival), color = Drivr.textMuted, fontSize = 11.sp, letterSpacing = 1.sp, lineHeight = 13.sp)
        Text(
            now.plusSeconds(durationRemainingS.toLong()).format(DateTimeFormatter.ofPattern("HH:mm")),
            color = Drivr.text,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 30.sp,
        )
      }
      Spacer(Modifier.width(14.dp))
      Column(Modifier.weight(1f)) {
        Text(
            "${formatDistance(distanceRemainingM)} · ${formatDuration(durationRemainingS)}",
            color = Drivr.text,
            fontSize = 15.sp,
            maxLines = 1,
        )
        if (trafficDelayS >= 60) {
          Text(
              stringResource(R.string.trip_traffic_delay, formatDuration(trafficDelayS)),
              color = TrafficColors.slow,
              fontSize = 13.sp,
              maxLines = 1,
          )
        }
      }
    }
  }
}

@Composable
private fun SpeedCard(limitKmh: Int?, speedKmh: Int?, modifier: Modifier = Modifier) {
  Row(
      modifier.clip(RoundedCornerShape(Drivr.cardRadius))
          .background(Drivr.card)
          .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    if (limitKmh != null) {
      Box(
          Modifier.size(50.dp)
              .clip(CircleShape)
              .background(Color.White)
              .border(6.dp, Color(0xFFD7262E), CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        Text("$limitKmh", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = if (limitKmh >= 100) 16.sp else 19.sp)
      }
      Spacer(Modifier.width(12.dp))
    }
    Column {
      val mph = app.drivr.settings.DisplayUnits.imperial
      val shown = speedKmh?.let { if (mph) (it / 1.609344).roundToInt() else it }
      Text("${shown ?: "–"}", color = Drivr.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
      Text(if (mph) "mph" else "km/h", color = Drivr.textMuted, fontSize = 12.sp)
    }
    Spacer(Modifier.height(0.dp))
  }
}

fun formatDuration(seconds: Double): String {
  val min = (seconds / 60).roundToInt().coerceAtLeast(1)
  return if (min < 60) L10n.s(R.string.duration_min, min) else L10n.s(R.string.duration_h_min, min / 60, min % 60)
}
