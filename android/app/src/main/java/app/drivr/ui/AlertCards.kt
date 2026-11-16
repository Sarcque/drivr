package app.drivr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.drivr.cameras.Enforcement
import app.drivr.cameras.EnforcementAhead
import app.drivr.traffic.TrafficAhead
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable

/**
 * Waze-style countdown while you're in a jam: a bar that fills as you get through it, with the
 * distance and an estimate of the time left.
 */
@Composable
fun JamCountdownCard(jam: TrafficAhead, modifier: Modifier = Modifier) {
  val left = jam.endDistanceM ?: return
  val kind = jam.event.kind
  AlertCard(modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("In ${kind.label.lowercase()}", color = Drivr.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
      Text(
          listOfNotNull(
                  "${formatDistance(left)} to go",
                  jam.secondsLeft?.let { "~${formatDuration(it)}" },
              )
              .joinToString(" · "),
          color = Drivr.text,
          fontSize = 15.sp,
      )
    }
    Spacer(Modifier.height(10.dp))
    ProgressRoad(jam.progress, kind.color())
  }
}

/** Fixed camera ahead, section control ahead, or the live section countdown with average speed. */
@Composable
fun EnforcementCard(e: EnforcementAhead, averageKmh: Int?, modifier: Modifier = Modifier) {
  val en = e.enforcement
  val limit =
      when (en) {
        is Enforcement.Camera -> en.maxspeedKmh
        is Enforcement.Section -> en.maxspeedKmh
      }
  AlertCard(modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      CameraBadge()
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        val title =
            when {
              e.isInsideSection -> "Section control"
              en is Enforcement.Section -> "Section control in ${formatDistance(e.distanceM)}"
              en is Enforcement.Camera && en.kind == "red light" -> "Red-light camera in ${formatDistance(e.distanceM)}"
              else -> "Speed camera in ${formatDistance(e.distanceM)}"
            }
        Text(title, color = Drivr.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (e.isInsideSection) {
          val over = averageKmh != null && limit != null && averageKmh > limit
          Text(
              listOfNotNull(
                      averageKmh?.let { "Average $it km/h" },
                      e.endDistanceM?.let { "${formatDistance(it)} left" },
                  )
                  .joinToString(" · "),
              color = if (over) TrafficColors.jam else Drivr.textMuted,
              fontSize = 14.sp,
          )
        }
      }
      if (limit != null) LimitSign(limit)
    }
    if (e.isInsideSection) {
      val len = e.sectionLengthM ?: 0.0
      val p = if (len > 0) (1 - (e.endDistanceM ?: 0.0) / len).toFloat() else 0f
      Spacer(Modifier.height(10.dp))
      ProgressRoad(p.coerceIn(0f, 1f), Drivr.accent)
    }
  }
}

@Composable
private fun AlertCard(modifier: Modifier, content: @Composable () -> Unit) {
  Column(
      modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(Drivr.cardRadius))
          .background(Drivr.card)
          .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    content()
  }
}

/** A strip of road: the part behind you is filled, the chevron is you. */
@Composable
private fun ProgressRoad(progress: Float, color: Color) {
  Canvas(Modifier.fillMaxWidth().height(18.dp)) {
    val h = size.height
    val barH = 8.dp.toPx()
    val y = (h - barH) / 2
    drawRoundRect(Drivr.cardBand, Offset(0f, y), Size(size.width, barH), CornerRadius(barH / 2))
    val x = (size.width * progress).coerceIn(barH, size.width)
    drawRoundRect(color, Offset(0f, y), Size(x, barH), CornerRadius(barH / 2))
    // Car marker.
    val c = Path().apply {
      moveTo(x + 7.dp.toPx(), h / 2)
      lineTo(x - 6.dp.toPx(), h / 2 - 7.dp.toPx())
      lineTo(x - 3.dp.toPx(), h / 2)
      lineTo(x - 6.dp.toPx(), h / 2 + 7.dp.toPx())
      close()
    }
    drawPath(c, Color.White)
  }
}

@Composable
private fun LimitSign(kmh: Int) {
  Box(
      Modifier.size(40.dp).clip(CircleShape).background(Color.White).border(5.dp, Color(0xFFD7262E), CircleShape),
      contentAlignment = Alignment.Center,
  ) {
    Text("$kmh", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = if (kmh >= 100) 13.sp else 15.sp)
  }
}

@Composable
private fun CameraBadge() {
  Canvas(Modifier.size(28.dp)) { drawCamera(Drivr.text) }
}

private fun DrawScope.drawCamera(color: Color) {
  val w = size.width
  val h = size.height
  drawRoundRect(color, Offset(w * 0.08f, h * 0.30f), Size(w * 0.62f, h * 0.44f), CornerRadius(w * 0.08f))
  val lens = Path().apply {
    moveTo(w * 0.70f, h * 0.42f)
    lineTo(w * 0.94f, h * 0.30f)
    lineTo(w * 0.94f, h * 0.74f)
    lineTo(w * 0.70f, h * 0.62f)
    close()
  }
  drawPath(lens, color)
}

private class CameraPinPainter : Painter() {
  override val intrinsicSize: Size = Size.Unspecified

  override fun DrawScope.onDraw() {
    drawCircle(Color.White, radius = size.minDimension / 2)
    drawCircle(Drivr.card, radius = size.minDimension / 2 - 2.dp.toPx())
    val inset = size.minDimension * 0.22f
    drawContext.transform.translate(inset, inset)
    val inner = Size(size.width - 2 * inset, size.height - 2 * inset)
    val w = inner.width
    val h = inner.height
    drawRoundRect(Color.White, Offset(w * 0.08f, h * 0.30f), Size(w * 0.62f, h * 0.44f), CornerRadius(w * 0.08f))
    drawPath(
        Path().apply {
          moveTo(w * 0.70f, h * 0.42f)
          lineTo(w * 0.94f, h * 0.30f)
          lineTo(w * 0.94f, h * 0.74f)
          lineTo(w * 0.70f, h * 0.62f)
          close()
        },
        Color.White,
    )
    drawContext.transform.translate(-inset, -inset)
  }
}

/** Camera pins on the map, at fixed cameras and at the start and end of section controls. */
@Composable
@MaplibreComposable
fun EnforcementLayers(all: List<Enforcement>) {
  val json =
      remember(all) {
        val pts =
            all.flatMap {
              when (it) {
                is Enforcement.Camera -> listOf(it.at)
                is Enforcement.Section -> listOf(it.from, it.to)
              }
            }
        """{"type":"FeatureCollection","features":[${pts.joinToString(",") {
          """{"type":"Feature","geometry":{"type":"Point","coordinates":[${it.lon},${it.lat}]},"properties":{}}"""
        }}]}"""
      }
  val source = rememberGeoJsonSource(GeoJsonData.JsonString(json))
  val painter = remember { CameraPinPainter() }
  SymbolLayer(
      id = "drivr-cameras",
      source = source,
      minZoom = 11f,
      iconImage = image(painter, size = DpSize(26.dp, 26.dp)),
      iconAllowOverlap = const(true),
  )
}

