package app.drivr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.stadiamaps.ferrostar.core.NavigationUiState
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.IconPitchAlignment
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable

/** The prototype's position marker: a white chevron with a blue base, lying flat on the road. */
@Composable
@MaplibreComposable
fun ChevronPuck(ui: NavigationUiState) {
  val loc = ui.location ?: return
  val bearing = loc.courseOverGround?.degrees?.toFloat() ?: 0f
  val json =
      """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point",""" +
          """"coordinates":[${loc.coordinates.lng},${loc.coordinates.lat}]},"properties":{"bearing":$bearing}}]}"""
  val source =
      rememberGeoJsonSource(
          GeoJsonData.JsonString(json),
          options = GeoJsonOptions(synchronousUpdate = true),
      )
  val painter = remember { ChevronPainter() }
  SymbolLayer(
      id = "drivr-puck",
      source = source,
      iconImage = image(painter, size = DpSize(64.dp, 64.dp)),
      iconAnchor = const(SymbolAnchor.Center),
      iconRotate = feature["bearing"].asNumber(const(0f)),
      iconPitchAlignment = const(IconPitchAlignment.Map),
      iconRotationAlignment = const(IconRotationAlignment.Map),
      iconAllowOverlap = const(true),
      iconIgnorePlacement = const(true),
  )
}

internal class ChevronPainter : Painter() {
  override val intrinsicSize: Size = Size.Unspecified

  override fun DrawScope.onDraw() {
    val w = size.width
    val h = size.height
    // Soft halo on the road.
    drawCircle(Drivr.accent.copy(alpha = 0.18f), radius = w * 0.48f, center = Offset(w / 2, h / 2))
    val base =
        Path().apply {
          moveTo(w * 0.5f, h * 0.70f)
          lineTo(w * 0.80f, h * 0.84f)
          lineTo(w * 0.80f, h * 0.76f)
          lineTo(w * 0.5f, h * 0.62f)
          lineTo(w * 0.20f, h * 0.76f)
          lineTo(w * 0.20f, h * 0.84f)
          close()
        }
    drawPath(base, Drivr.accent)
    val chevron =
        Path().apply {
          moveTo(w * 0.5f, h * 0.14f)
          lineTo(w * 0.80f, h * 0.76f)
          lineTo(w * 0.5f, h * 0.62f)
          lineTo(w * 0.20f, h * 0.76f)
          close()
        }
    drawPath(chevron, Color.Black.copy(alpha = 0.18f), alpha = 1f)
    drawPath(chevron, Color.White)
  }
}
