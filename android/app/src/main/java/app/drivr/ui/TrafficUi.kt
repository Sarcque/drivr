package app.drivr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.drivr.traffic.TrafficAhead
import app.drivr.traffic.TrafficEvent
import app.drivr.traffic.TrafficKind
import kotlin.math.roundToInt
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable

object TrafficColors {
  val jam = Color(0xFFD7262E)
  val slow = Color(0xFFF08A24)
  val incident = Color(0xFFD7262E)
  val closure = Color(0xFF3B3F45)
}

fun TrafficKind.color(): Color =
    when (this) {
      TrafficKind.JAM, TrafficKind.ACCIDENT -> TrafficColors.jam
      TrafficKind.SLOW, TrafficKind.BROKEN_DOWN, TrafficKind.OBSTRUCTION, TrafficKind.LANE_CLOSED ->
          TrafficColors.slow
      TrafficKind.ROAD_CLOSED -> TrafficColors.closure
    }

/** Jams and closures as lines over the roads (on top of the route, so it turns red where jammed); incidents as dots. */
@Composable
@MaplibreComposable
fun TrafficLayers(events: List<TrafficEvent>) {
  // Hundreds of municipal closures are in the feed: show those only when zoomed in.
  val closuresJson = remember(events) { geoJson(events.filter { it.isLine && it.kind == TrafficKind.ROAD_CLOSED }) }
  val linesJson = remember(events) { geoJson(events.filter { it.isLine && it.kind != TrafficKind.ROAD_CLOSED }) }
  val closures = rememberGeoJsonSource(GeoJsonData.JsonString(closuresJson))
  LineLayer(
      id = "drivr-traffic-closures",
      source = closures,
      minZoom = 14f,
      color = const(TrafficColors.closure),
      width = interpolate(exponential(1.5f), zoom(), 13 to const(3.dp), 18 to const(10.dp)),
      dasharray = const(listOf(1, 1)),
  )
  val pointsJson = remember(events) { geoJson(events.filter { !it.isLine }) }
  val lines = rememberGeoJsonSource(GeoJsonData.JsonString(linesJson))
  val points = rememberGeoJsonSource(GeoJsonData.JsonString(pointsJson))

  LineLayer(
      id = "drivr-traffic-lines",
      source = lines,
      minZoom = 8f,
      color = org.maplibre.compose.expressions.dsl.feature["color"].cast(),
      width = interpolate(exponential(1.5f), zoom(), 8 to const(3.dp), 18 to const(16.dp)),
      cap = const(LineCap.Round),
      join = const(LineJoin.Round),
  )
  CircleLayer(
      id = "drivr-traffic-points",
      source = points,
      minZoom = 10f,
      color = org.maplibre.compose.expressions.dsl.feature["color"].cast(),
      radius = interpolate(exponential(1.5f), zoom(), 8 to const(5.dp), 16 to const(13.dp)),
      strokeColor = const(Color.White),
      strokeWidth = const(2.dp),
  )
  SymbolLayer(
      id = "drivr-traffic-points-mark",
      source = points,
      minZoom = 12f,
      textField = const("!").cast(),
      textFont = const(listOf("Noto Sans Bold")),
      textSize = const(14.sp),
      textColor = const(Color.White),
      textAllowOverlap = const(true),
  )
}

private fun geoJson(events: List<TrafficEvent>): String {
  val features =
      events.joinToString(",") { e ->
        val color = "#%06X".format(0xFFFFFF and e.kind.color().toArgb())
        val geom =
            if (e.isLine)
                """{"type":"LineString","coordinates":[${e.geometry.joinToString(",") { "[${it.lon},${it.lat}]" }}]}"""
            else """{"type":"Point","coordinates":[${e.geometry[0].lon},${e.geometry[0].lat}]}"""
        """{"type":"Feature","geometry":$geom,"properties":{"color":"$color","kind":"${e.kind.name}"}}"""
      }
  return """{"type":"FeatureCollection","features":[$features]}"""
}

/** "Traffic jam in 4.2 km · 2.8 km long · +4 min". Shown above the arrival card. */
@Composable
fun TrafficAheadCard(next: TrafficAhead, modifier: Modifier = Modifier) {
  val e = next.event
  val details =
      listOfNotNull(
          e.queueLengthM?.takeIf { it > 0 }?.let { "${formatDistance(it)} long" },
          e.delaySeconds?.takeIf { it >= 60 }?.let { "+${(it / 60).roundToInt()} min" },
      )
  Row(
      modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(Drivr.cardRadius))
          .background(Drivr.card)
          .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(e.kind.color()),
        contentAlignment = Alignment.Center,
    ) {
      Text("!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
    Spacer(Modifier.width(12.dp))
    Column {
      Text(
          "${e.kind.label} in ${formatDistance(next.distanceM)}",
          color = Drivr.text,
          fontSize = 16.sp,
          fontWeight = FontWeight.SemiBold,
      )
      if (details.isNotEmpty()) {
        Text(details.joinToString(" · "), color = Drivr.textMuted, fontSize = 14.sp)
      }
    }
  }
}

/**
 * Inside a jam: how much is left, as a bar that fills as you crawl through it (like Waze's jam
 * meter). "Traffic jam · 1.8 km to go · ~6 min" with the car's position on the bar.
 */
@Composable
fun JamCountdownCard(jam: TrafficAhead, modifier: Modifier = Modifier) {
  val left = jam.endM ?: return
  val color = jam.event.kind.color()
  Column(
      modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(Drivr.cardRadius))
          .background(Drivr.card)
          .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
          "${jam.event.kind.label} · ${formatDistance(left)} to go",
          color = Drivr.text,
          fontSize = 16.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.weight(1f),
      )
      jam.secondsLeft?.let {
        Text("~${(it / 60).roundToInt().coerceAtLeast(1)} min", color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
      }
    }
    Spacer(Modifier.height(10.dp))
    // The jam as a bar: driven part grey, what's left in the jam's colour, car at the boundary.
    BoxWithConstraints(Modifier.fillMaxWidth().height(18.dp)) {
      val p = jam.progress.toFloat()
      Row(Modifier.fillMaxWidth().height(8.dp).align(Alignment.CenterStart).clip(CircleShape)) {
        if (p > 0f) Box(Modifier.weight(p).fillMaxHeight().background(Drivr.inactive))
        if (p < 1f) Box(Modifier.weight(1f - p).fillMaxHeight().background(color))
      }
      val x = (maxWidth - 18.dp) * p
      Box(
          Modifier.offset(x = x).size(18.dp).clip(CircleShape).background(Drivr.accent)
              .border(3.dp, Color.White, CircleShape)
      )
    }
    Spacer(Modifier.height(6.dp))
    Row {
      Text(
          "${(jam.progress * 100).roundToInt()}% through",
          color = Drivr.textMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
      jam.lengthM?.let { Text("${formatDistance(it)} jam", color = Drivr.textMuted, fontSize = 13.sp) }
    }
  }
}
