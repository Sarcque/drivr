package app.drivr.ui

import androidx.compose.ui.res.stringResource
import app.drivr.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.drivr.cameras.CameraAhead
import app.drivr.cameras.CameraKind
import app.drivr.cameras.SpeedCamera
import kotlin.math.roundToInt
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable

private val cameraColor = Color(0xFF2B2F36)

/** A small camera glyph in a dark circle. */
@Composable
fun CameraIcon(modifier: Modifier = Modifier) {
  Box(modifier.size(36.dp).clip(CircleShape).background(cameraColor).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
    Canvas(Modifier.size(20.dp)) {
      val u = size.width / 24f
      drawRoundRect(Color.White, Offset(2f * u, 7f * u), Size(20f * u, 13f * u), CornerRadius(2.5f * u))
      drawRect(Color.White, Offset(8f * u, 4f * u), Size(8f * u, 4f * u))
      drawCircle(cameraColor, 4.2f * u, Offset(12f * u, 13.5f * u))
      drawCircle(Color.White, 2.2f * u, Offset(12f * u, 13.5f * u))
    }
  }
}

@Composable
private fun LimitSign(kmh: Int) {
  Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White).border(5.dp, Color(0xFFD7262E), CircleShape), contentAlignment = Alignment.Center) {
    Text("$kmh", color = Color.Black, fontSize = if (kmh >= 100) 14.sp else 16.sp, fontWeight = FontWeight.Bold)
  }
}

/**
 * Next fixed camera within 1.5 km, or, inside an average-speed section, the distance left and
 * your average so far (green under the limit, red over it).
 */
@Composable
fun CameraAheadCard(next: CameraAhead, averageKmh: Double?, modifier: Modifier = Modifier) {
  val cam = next.camera
  Row(
      modifier.fillMaxWidth().clip(RoundedCornerShape(Drivr.cardRadius)).background(Drivr.card)
          .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    CameraIcon()
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      if (next.inside) {
        Text(stringResource(R.string.camera_left, cam.kind.label, formatDistance(next.endM ?: 0.0)), color = Drivr.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        val avg = averageKmh?.roundToInt()
        val over = avg != null && cam.maxSpeedKmh != null && avg > cam.maxSpeedKmh
        Text(
            if (avg == null) stringResource(R.string.camera_measuring) else stringResource(R.string.camera_average, avg),
            color = if (avg == null) Drivr.textMuted else if (over) Color(0xFFFF6B6B) else Drivr.ok,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
      } else {
        Text(stringResource(R.string.event_in_distance, cam.kind.label, formatDistance(next.distanceM)), color = Drivr.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        next.endM?.let {
          Text(stringResource(R.string.camera_section_long, formatDistance(it - next.distanceM)), color = Drivr.textMuted, fontSize = 14.sp)
        }
      }
    }
    cam.maxSpeedKmh?.let { LimitSign(it) }
  }
}

/** Camera positions on the map (sections: start and end). */
@Composable
@MaplibreComposable
fun CamerasLayer(cameras: List<SpeedCamera>) {
  val json = remember(cameras) {
    val f = cameras.flatMap { listOfNotNull(it.at, it.end) }.joinToString(",") {
      """{"type":"Feature","geometry":{"type":"Point","coordinates":[${it.lon},${it.lat}]},"properties":{}}"""
    }
    """{"type":"FeatureCollection","features":[$f]}"""
  }
  val source = rememberGeoJsonSource(GeoJsonData.JsonString(json))
  CircleLayer(
      id = "drivr-cameras",
      source = source,
      minZoom = 10f,
      color = const(cameraColor),
      radius = interpolate(exponential(1.5f), zoom(), 10 to const(4.dp), 16 to const(9.dp)),
      strokeColor = const(Color.White),
      strokeWidth = const(2.dp),
  )
}

/** Next camera worth showing: inside a section, or a camera within 1.5 km. */
fun List<CameraAhead>.relevant(): CameraAhead? =
    firstOrNull { it.inside } ?: firstOrNull { it.distanceM < 1500 }
