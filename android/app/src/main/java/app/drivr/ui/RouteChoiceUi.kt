package app.drivr.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.drivr.R
import app.drivr.i18n.L10n
import app.drivr.traffic.ScoredRoute
import androidx.compose.ui.res.stringResource
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import uniffi.ferrostar.GeographicCoordinate

object AltRouteColors {
  val casing = Color(0xFF5E6670)
  val fill = Color(0xFFB5BCC6)
}

/**
 * Other routes, drawn grey under the blue one: the alternatives at the start of a trip, or the
 * faster way on offer while driving. Always composed (empty when there are none), so the layers
 * are added once and sit below the route line.
 */
@Composable
@MaplibreComposable
fun AlternativeRoutesLayer(routes: List<List<GeographicCoordinate>>) {
  val json =
      remember(routes) {
        val features =
            routes.filter { it.size >= 2 }.joinToString(",") { r ->
              """{"type":"Feature","geometry":{"type":"LineString","coordinates":[${r.joinToString(",") { "[${it.lng},${it.lat}]" }}]},"properties":{}}"""
            }
        """{"type":"FeatureCollection","features":[$features]}"""
      }
  val source = rememberGeoJsonSource(GeoJsonData.JsonString(json))
  LineLayer(
      id = "drivr-alt-casing",
      source = source,
      color = const(AltRouteColors.casing),
      width = interpolate(exponential(1.5f), zoom(), 10 to const(7.dp), 18 to const(26.dp)),
      cap = const(LineCap.Round),
      join = const(LineJoin.Round),
  )
  LineLayer(
      id = "drivr-alt-fill",
      source = source,
      color = const(AltRouteColors.fill),
      width = interpolate(exponential(1.5f), zoom(), 10 to const(4.dp), 18 to const(18.dp)),
      cap = const(LineCap.Round),
      join = const(LineJoin.Round),
  )
}

/**
 * At the start of a trip, when there is more than one sensible way: one row per route, quickest
 * (with live traffic) first. Tap a row to drive it; the chosen one is blue on the map, the others
 * grey. Closes by itself after a few seconds.
 */
@Composable
fun RouteChoiceCard(
    options: List<ScoredRoute>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val fastest = options.minOf { it.totalSeconds }
  val fastestIndex = options.indexOfFirst { it.totalSeconds == fastest }
  Column(
      modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(Drivr.cardRadius))
          .background(Drivr.card)
          .padding(vertical = 6.dp),
  ) {
    options.forEachIndexed { i, o ->
      val isSelected = i == selected
      Row(
          Modifier.fillMaxWidth()
              .clickable { onSelect(i) }
              .background(if (isSelected) Drivr.cardBand else Color.Transparent)
              .padding(horizontal = 16.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
            Modifier.size(14.dp).clip(CircleShape)
                .background(if (isSelected) RouteColors.fill else AltRouteColors.fill)
                .border(2.dp, if (isSelected) RouteColors.casing else AltRouteColors.casing, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            formatDuration(o.totalSeconds),
            color = Drivr.text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(96.dp),
        )
        Column(Modifier.weight(1f)) {
          Text(
              listOfNotNull(o.via?.let { stringResource(R.string.route_choice_via, it) }, formatDistance(o.distanceM)).joinToString(" · "),
              color = Drivr.text,
              fontSize = 14.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
          trafficNote(o)?.let { (text, color) -> Text(text, color = color, fontSize = 12.sp, maxLines = 1) }
        }
        val extra = o.totalSeconds - fastest
        if (i == fastestIndex) {
          Text(stringResource(R.string.route_choice_fastest), color = Drivr.ok, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        } else if (extra < 60) {
          Text(stringResource(R.string.route_choice_same_time), color = Drivr.textMuted, fontSize = 13.sp)
        } else {
          Text(stringResource(R.string.route_choice_extra, formatDuration(extra)), color = Drivr.textMuted, fontSize = 13.sp)
        }
      }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(stringResource(R.string.route_choice_live), color = Drivr.textMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
      Text(
          stringResource(R.string.route_choice_go),
          color = Color.White,
          fontWeight = FontWeight.SemiBold,
          fontSize = 15.sp,
          modifier =
              Modifier.clip(RoundedCornerShape(12.dp)).background(Drivr.accent).clickable(onClick = onGo)
                  .padding(horizontal = 22.dp, vertical = 8.dp),
      )
    }
  }
}

private fun trafficNote(o: ScoredRoute): Pair<String, Color>? =
    when {
      o.closures > 0 -> L10n.s(R.string.route_choice_closed) to TrafficColors.jam
      o.trafficSeconds >= 60 -> L10n.s(R.string.route_choice_traffic_delay, formatDuration(o.trafficSeconds)) to TrafficColors.slow
      else -> null
    }

/**
 * While driving, when a jam ahead makes another way quicker: "Faster route · saves 7 min". The
 * new route is grey on the map until the driver taps Switch. Nothing changes by itself.
 */
@Composable
fun FasterRouteCard(
    savesSeconds: Double,
    via: String?,
    currentDelaySeconds: Double,
    onSwitch: () -> Unit,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(
      modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(Drivr.cardRadius))
          .background(Drivr.card)
          .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(28.dp).clip(CircleShape).background(Drivr.ok), contentAlignment = Alignment.Center) {
        Text("»", color = Drivr.card, fontWeight = FontWeight.Bold, fontSize = 18.sp)
      }
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(
            stringResource(R.string.faster_route_title, formatDuration(savesSeconds)),
            color = Drivr.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            listOfNotNull(
                    via?.let { stringResource(R.string.route_choice_via, it) },
                    if (currentDelaySeconds >= 60) stringResource(R.string.faster_route_jam, formatDuration(currentDelaySeconds)) else stringResource(R.string.faster_route_traffic),
                )
                .joinToString(" · "),
            color = Drivr.textMuted,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(
          stringResource(R.string.faster_route_keep),
          color = Drivr.text,
          fontSize = 15.sp,
          modifier =
              Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Drivr.cardBand).clickable(onClick = onKeep)
                  .padding(vertical = 10.dp),
          textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      )
      Text(
          stringResource(R.string.faster_route_switch),
          color = Color.White,
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
          modifier =
              Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Drivr.accent).clickable(onClick = onSwitch)
                  .padding(vertical = 10.dp),
          textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      )
    }
  }
}
