package app.drivr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.stadiamaps.ferrostar.core.NavigationUiState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.expressions.value.SymbolPlacement
import org.maplibre.compose.expressions.value.TextPitchAlignment
import org.maplibre.compose.expressions.value.TextRotationAlignment
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import kotlin.math.cos
import uniffi.ferrostar.GeographicCoordinate

object RouteColors {
  val casing = Color(0xFF1446A8)
  val fill = Color(0xFF3D7FF2)
  val arrow = Color.White
}

private val boldFont = const(listOf("Noto Sans Bold"))

private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""

/**
 * The route on the map, matching the prototype: a thick blue line with a dark casing and white
 * direction chevrons. Only the part still ahead is drawn, so the line starts at the car. Numbered
 * maneuver dots appear when zoomed out (overview), and stay out of the way while driving.
 */
@Composable
@MaplibreComposable
fun DrivrRouteLine(ui: NavigationUiState) {
  // Always compose the source and layers (empty when there's no route), so they are added once.
  // The line is the whole route and only changes when the route does (start, reroute), not on
  // every GPS fix: re-sending a 50 km line every second kept the map re-tiling it, and a stale or
  // half-built line is what made it "not plot". The car and chevrons show where you are on it.
  val geometry = ui.routeGeometry?.takeIf { it.size >= 2 }
  val lineJson = remember(geometry) { if (geometry == null) EMPTY_FC else lineJson(geometry) }
  val line = rememberGeoJsonSource(GeoJsonData.JsonString(lineJson))

  LineLayer(
      id = "drivr-route-casing",
      source = line,
      color = const(RouteColors.casing),
      width = interpolate(exponential(1.5f), zoom(), 10 to const(9.dp), 18 to const(34.dp)),
      cap = const(LineCap.Round),
      join = const(LineJoin.Round),
  )
  LineLayer(
      id = "drivr-route-fill",
      source = line,
      color = const(RouteColors.fill),
      width = interpolate(exponential(1.5f), zoom(), 10 to const(6.dp), 18 to const(24.dp)),
      cap = const(LineCap.Round),
      join = const(LineJoin.Round),
  )
  SymbolLayer(
      id = "drivr-route-chevrons",
      source = line,
      placement = const(SymbolPlacement.Line),
      spacing = const(90.dp),
      textField = const("›").cast(),
      textFont = boldFont,
      textSize = interpolate(exponential(1.5f), zoom(), 10 to const(18.sp), 18 to const(40.sp)),
      textColor = const(RouteColors.arrow),
      textKeepUpright = const(false),
      textAllowOverlap = const(true),
      textIgnorePlacement = const(true),
      textRotationAlignment = const(TextRotationAlignment.Map),
      textPitchAlignment = const(TextPitchAlignment.Map),
  )

  // Overview: numbered maneuvers and the destination.
  val pointsJson = remember(ui.remainingSteps) { maneuverPointsJson(ui) }
  val points = rememberGeoJsonSource(GeoJsonData.JsonString(pointsJson))
  CircleLayer(
      id = "drivr-maneuver-dot",
      source = points,
      maxZoom = 14f,
      color = const(RouteColors.casing),
      radius = const(11.dp),
      strokeColor = const(Color.White),
      strokeWidth = const(2.dp),
  )
  SymbolLayer(
      id = "drivr-maneuver-label",
      source = points,
      maxZoom = 14f,
      textField = feature["label"].cast(),
      textFont = boldFont,
      textSize = const(12.sp),
      textColor = const(Color.White),
      textAllowOverlap = const(true),
  )
  SymbolLayer(
      id = "drivr-maneuver-name",
      source = points,
      maxZoom = 14f,
      textField = feature["action"].cast(),
      textFont = boldFont,
      textSize = const(13.sp),
      textColor = const(Drivr.text),
      textHaloColor = const(Drivr.card),
      textHaloWidth = const(4.dp),
      textAnchor = const(SymbolAnchor.Left),
      textOffset = offset(1.4.em, 0.em),
      textOptional = const(true),
  )
}

/**
 * The route from the car to the destination: the full route geometry, cut at the point nearest
 * the car. Works from the route line alone (not step indices), so it can't come out empty while a
 * route exists. Null only when there is no route.
 */
internal fun remainingGeometry(ui: NavigationUiState): List<GeographicCoordinate>? {
  val route = ui.routeGeometry?.takeIf { it.size >= 2 } ?: return null
  val car = ui.location?.coordinates ?: return route
  return sliceRouteAt(route, car)
}

/** [route] from the point nearest [car] onwards, starting exactly at that point. */
internal fun sliceRouteAt(route: List<GeographicCoordinate>, car: GeographicCoordinate): List<GeographicCoordinate> {
  if (route.size < 2) return route
  val kx = cos(Math.toRadians(car.lat))
  var best = Double.MAX_VALUE
  var bestI = 0
  var bestT = 0.0
  for (i in 0 until route.size - 1) {
    val ax = route[i].lng * kx; val ay = route[i].lat
    val dx = route[i + 1].lng * kx - ax; val dy = route[i + 1].lat - ay
    val l2 = dx * dx + dy * dy
    val t = if (l2 == 0.0) 0.0 else (((car.lng * kx - ax) * dx + (car.lat - ay) * dy) / l2).coerceIn(0.0, 1.0)
    val ex = ax + t * dx - car.lng * kx; val ey = ay + t * dy - car.lat
    val d = ex * ex + ey * ey
    if (d < best) { best = d; bestI = i; bestT = t }
  }
  val a = route[bestI]; val b = route[bestI + 1]
  val start = GeographicCoordinate(a.lat + (b.lat - a.lat) * bestT, a.lng + (b.lng - a.lng) * bestT)
  return listOf(start) + route.subList(bestI + 1, route.size)
}

private fun lineJson(points: List<GeographicCoordinate>): String {
  val coords = points.joinToString(",") { "[${it.lng},${it.lat}]" }
  return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]},"properties":{}}]}"""
}

private fun maneuverPointsJson(ui: NavigationUiState): String {
  val steps = ui.remainingSteps.orEmpty()
  val features =
      steps.drop(1).mapIndexedNotNull { i, step ->
        val at = step.geometry.firstOrNull() ?: return@mapIndexedNotNull null
        val content = step.visualInstructions.firstOrNull()?.primaryContent
        val action =
            actionPhrase(content?.maneuverType, content?.maneuverModifier)
                ?: content?.text.orEmpty()
        val label = if (i == steps.size - 2) "★" else "${i + 1}"
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[${at.lng},${at.lat}]},""" +
            """"properties":{"label":"$label","action":"${action.replace("\"", "'")}"}}"""
      }
  return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
}
