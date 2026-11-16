package app.drivr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.drivr.PlacesState
import app.drivr.places.FuelPrices
import app.drivr.places.Place
import app.drivr.places.PlaceCategory
import app.drivr.places.PlaceResult
import java.util.Locale
import kotlin.math.roundToInt
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.value.SymbolAnchor
import androidx.compose.ui.unit.em
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable

// ---- Category look ------------------------------------------------------------------------------

fun PlaceCategory.color(): Color =
    when (this) {
      PlaceCategory.FUEL -> Color(0xFFE0782F)
      PlaceCategory.CHARGING -> Color(0xFF22A06B)
      PlaceCategory.FOOD -> Color(0xFFD9473F)
      PlaceCategory.COFFEE -> Color(0xFF9B6A4C)
      PlaceCategory.GROCERIES -> Color(0xFF6C5CE7)
      PlaceCategory.SHOPS -> Color(0xFFC2457F)
      PlaceCategory.PARKING -> Color(0xFF2F6FDB)
      PlaceCategory.REST -> Color(0xFF4E7D8C)
    }

/** Small line icons drawn on a 24-unit grid, so there is no icon-font dependency. */
@Composable
fun PlaceIcon(category: PlaceCategory?, size: Dp = 36.dp, modifier: Modifier = Modifier) {
  val bg = category?.color() ?: Drivr.inactive
  Box(modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
    Canvas(Modifier.size(size * 0.62f)) { drawGlyph(category) }
  }
}

private fun DrawScope.drawGlyph(category: PlaceCategory?) {
  val u = this.size.width / 24f
  val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
  val w = Color.White
  fun path(block: Path.() -> Unit) = Path().apply(block)
  fun p(x: Float, y: Float) = Offset(x * u, y * u)
  when (category) {
    PlaceCategory.FUEL -> {
      drawRoundRect(w, p(4f, 3f), Size(10f * u, 18f * u), androidx.compose.ui.geometry.CornerRadius(2f * u))
      drawRect(category.color(), p(6f, 5.5f), Size(6f * u, 5f * u))
      drawPath(
          path {
            moveTo(14f * u, 8f * u); lineTo(17.5f * u, 8f * u); lineTo(19f * u, 10f * u)
            lineTo(19f * u, 17f * u); quadraticTo(19f * u, 19f * u, 17.5f * u, 19f * u)
            quadraticTo(16f * u, 19f * u, 16f * u, 17f * u); lineTo(16f * u, 13f * u); lineTo(14f * u, 13f * u)
          },
          w, style = stroke)
    }
    PlaceCategory.CHARGING ->
        drawPath(
            path {
              moveTo(13.5f * u, 2f * u); lineTo(5f * u, 13.5f * u); lineTo(11f * u, 13.5f * u)
              lineTo(10f * u, 22f * u); lineTo(19f * u, 10f * u); lineTo(13f * u, 10f * u); close()
            },
            w)
    PlaceCategory.FOOD -> {
      // Fork
      drawLine(w, p(7f, 3f), p(7f, 21f), 2f * u, StrokeCap.Round)
      drawPath(path { moveTo(4f * u, 3f * u); lineTo(4f * u, 8f * u); quadraticTo(4f * u, 11f * u, 7f * u, 11f * u); quadraticTo(10f * u, 11f * u, 10f * u, 8f * u); lineTo(10f * u, 3f * u) }, w, style = stroke)
      // Knife
      drawPath(path { moveTo(17f * u, 21f * u); lineTo(17f * u, 3f * u); quadraticTo(13.5f * u, 6f * u, 14f * u, 13f * u); lineTo(17f * u, 13f * u) }, w, style = stroke)
    }
    PlaceCategory.COFFEE -> {
      drawPath(path { moveTo(4f * u, 9f * u); lineTo(16f * u, 9f * u); lineTo(15f * u, 17f * u); quadraticTo(14.5f * u, 20f * u, 11.5f * u, 20f * u); lineTo(8.5f * u, 20f * u); quadraticTo(5.5f * u, 20f * u, 5f * u, 17f * u); close() }, w)
      drawArc(w, -90f, 180f, false, p(13.5f, 10f), Size(6f * u, 6f * u), style = stroke)
      drawLine(w, p(8f, 3f), p(8f, 6f), 1.6f * u, StrokeCap.Round)
      drawLine(w, p(12f, 3f), p(12f, 6f), 1.6f * u, StrokeCap.Round)
    }
    PlaceCategory.GROCERIES -> {
      drawPath(path { moveTo(2.5f * u, 4f * u); lineTo(5.5f * u, 4f * u); lineTo(8f * u, 15f * u); lineTo(18.5f * u, 15f * u); lineTo(20.5f * u, 7.5f * u); lineTo(6.5f * u, 7.5f * u) }, w, style = stroke)
      drawCircle(w, 1.8f * u, p(9f, 19.5f))
      drawCircle(w, 1.8f * u, p(17.5f, 19.5f))
    }
    PlaceCategory.SHOPS -> {
      drawRoundRect(w, p(4f, 8f), Size(16f * u, 13f * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u))
      drawArc(w, 180f, 180f, false, p(8f, 3f), Size(8f * u, 10f * u), style = stroke)
    }
    PlaceCategory.PARKING ->
        drawPath(
            path { moveTo(8f * u, 20f * u); lineTo(8f * u, 4f * u); lineTo(13f * u, 4f * u); quadraticTo(18f * u, 4f * u, 18f * u, 9f * u); quadraticTo(18f * u, 14f * u, 13f * u, 14f * u); lineTo(8f * u, 14f * u) },
            w, style = Stroke(width = 3f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    PlaceCategory.REST -> {
      // A tree and a bench: the Dutch "verzorgingsplaats" sign.
      drawCircle(w, 5f * u, p(8f, 8f))
      drawLine(w, p(8f, 12f), p(8f, 21f), 2f * u, StrokeCap.Round)
      drawLine(w, p(12f, 15f), p(21f, 15f), 2f * u, StrokeCap.Round)
      drawLine(w, p(13.5f, 15f), p(13.5f, 20f), 2f * u, StrokeCap.Round)
      drawLine(w, p(19.5f, 15f), p(19.5f, 20f), 2f * u, StrokeCap.Round)
    }
    null -> drawCircle(w, 4f * u, p(12f, 12f))
  }
}

// ---- Search bar and category chips --------------------------------------------------------------

@Composable
fun PlacesSearchBar(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
) {
  Row(
      modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(26.dp))
          .background(Drivr.card)
          .padding(horizontal = 18.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Canvas(Modifier.size(18.dp)) {
      val u = size.width / 24f
      drawCircle(Drivr.textMuted, 7f * u, Offset(10f * u, 10f * u), style = Stroke(2.5f * u))
      drawLine(Drivr.textMuted, Offset(15.5f * u, 15.5f * u), Offset(21f * u, 21f * u), 2.5f * u, StrokeCap.Round)
    }
    Spacer(Modifier.width(12.dp))
    Box(Modifier.weight(1f)) {
      if (query.isEmpty()) Text("Search places, fuel, food…", color = Drivr.textMuted, fontSize = 17.sp)
      BasicTextField(
          value = query,
          onValueChange = {
            onFocus()
            onQuery(it)
          },
          singleLine = true,
          textStyle = TextStyle(color = Drivr.text, fontSize = 17.sp),
          cursorBrush = SolidColor(Drivr.accent),
          modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
fun CategoryChips(
    selected: PlaceCategory?,
    onSelect: (PlaceCategory) -> Unit,
    modifier: Modifier = Modifier,
    categories: List<PlaceCategory> = PlaceCategory.entries,
) {
  Row(
      modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    categories.forEach { c ->
      val on = c == selected
      Row(
          Modifier.clip(RoundedCornerShape(20.dp))
              .background(if (on) c.color() else Drivr.card)
              .clickable { onSelect(c) }
              .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        PlaceIcon(c, size = 24.dp)
        Spacer(Modifier.width(8.dp))
        Text(c.label, color = Drivr.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
      }
    }
  }
}

// ---- Results list -------------------------------------------------------------------------------

/**
 * Bottom sheet with places: along the route in driving order while navigating ("in 12 km,
 * +3 min"), or nearest first when browsing.
 */
@Composable
fun PlacesSheet(
    state: PlacesState,
    onSelectCategory: (PlaceCategory) -> Unit,
    onSelect: (PlaceResult) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    priceCountry: String? = null,
    maxListHeight: Dp = 420.dp,
) {
  Column(
      modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
          .background(Drivr.card)
          .padding(top = 10.dp)
  ) {
    Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Drivr.inactive))
    Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(sheetTitle(state), color = Drivr.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(sheetSubtitle(state), color = Drivr.textMuted, fontSize = 13.sp)
      }
      Text(
          "Close",
          color = Drivr.accent,
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose).padding(12.dp),
      )
    }
    CategoryChips(state.category, onSelectCategory, Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp))
    HorizontalDivider(color = Drivr.cardBand)
    when {
      state.loading ->
          Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), color = Drivr.accent, strokeWidth = 3.dp)
          }
      state.error != null -> Message(state.error)
      state.results.isEmpty() && (state.category != null || state.query.isNotBlank()) ->
          Message(if (state.alongRoute) "Nothing found within reach of your route." else "Nothing found nearby.")
      else ->
          LazyColumn(Modifier.heightIn(max = maxListHeight)) {
            items(state.results, key = { it.place.id }) { r ->
              PlaceRow(r, onClick = { onSelect(r) })
              HorizontalDivider(color = Drivr.cardBand, modifier = Modifier.padding(start = 70.dp))
            }
            if (state.category == PlaceCategory.FUEL && priceCountry == null) {
              item { PriceCoverageNote(Modifier.padding(16.dp)) }
            }
          }
    }
  }
}

private fun sheetTitle(s: PlacesState): String =
    when {
      s.category != null -> s.category.label + if (s.alongRoute) " on your route" else " nearby"
      s.query.isNotBlank() -> "“${s.query.trim()}”"
      else -> if (s.alongRoute) "Add a stop" else "Find a place"
    }

private fun sheetSubtitle(s: PlacesState): String =
    when {
      s.loading -> "Searching OpenStreetMap…"
      s.alongRoute && s.category != null -> "Next 100 km, in driving order · ${s.results.size} found"
      s.category != null -> "Nearest first · ${s.results.size} found"
      s.query.isNotBlank() -> "${s.results.size} results"
      else -> "Pick a category or type a name"
    }

@Composable
private fun Message(text: String) {
  Text(text, color = Drivr.textMuted, fontSize = 15.sp, modifier = Modifier.padding(24.dp))
}

@Composable
fun PlaceRow(r: PlaceResult, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val p = r.place
  Row(
      modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    PlaceIcon(p.category, size = 40.dp)
    Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f)) {
      Text(p.name, color = Drivr.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      val facts = facts(p)
      if (facts.isNotEmpty()) {
        Text(facts.joinToString(" · "), color = Drivr.textMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      if (p.is24h) Text("Open 24/7", color = Drivr.ok, fontSize = 13.sp)
    }
    Spacer(Modifier.width(10.dp))
    Column(horizontalAlignment = Alignment.End) {
      r.prices?.let { cheapest(it) }?.let { (fuel, price) ->
        Text(formatPrice(price), color = Drivr.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(fuel, color = Drivr.textMuted, fontSize = 12.sp)
        return@Column
      }
      if (r.alongRouteM != null) {
        Text("in ${formatDistance(r.alongRouteM)}", color = Drivr.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        r.detourS?.let { Text(detourText(it, estimate = true), color = Drivr.textMuted, fontSize = 13.sp) }
      } else {
        Text(formatDistance(r.distanceM), color = Drivr.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

private fun facts(p: Place): List<String> =
    when (p.category) {
      PlaceCategory.FUEL -> listOfNotNull(p.brand?.takeIf { !p.name.contains(it, ignoreCase = true) }) + p.fuels.take(4)
      PlaceCategory.CHARGING ->
          listOfNotNull(p.maxPowerKw?.let { "Up to ${Place.formatKw(it)}" }, p.tags["capacity"]?.let { "$it points" }) +
              p.sockets.map { it.substringBefore(' ') }.distinct().take(3)
      PlaceCategory.FOOD, PlaceCategory.COFFEE -> listOfNotNull(p.cuisine, p.tags["amenity"]?.takeIf { it == "fast_food" }?.let { "Fast food" }, p.address)
      else -> listOfNotNull(p.brand?.takeIf { it != p.name }, p.address)
    }

fun detourText(seconds: Double, estimate: Boolean): String {
  val min = (seconds / 60).roundToInt()
  val t = if (min < 1) "<1 min" else "$min min"
  return if (estimate) "+$t detour" else "Adds $t"
}

fun formatPrice(euro: Double): String = String.format(Locale.ROOT, "€%.3f", euro).replace('.', ',')

private fun cheapest(p: FuelPrices): Pair<String, Double>? =
    (p.perLitre["Euro 95"]?.let { "Euro 95" to it } ?: p.perLitre["E10"]?.let { "E10" to it }
        ?: p.perLitre["Diesel"]?.let { "Diesel" to it })

@Composable
fun PriceCoverageNote(modifier: Modifier = Modifier) {
  Text(
      "No pump prices here: the Netherlands and Belgium don't publish per-station prices as open " +
          "data. Drivr shows official prices where governments do (France today; Spain, Italy and " +
          "Austria next).",
      color = Drivr.textMuted,
      fontSize = 12.sp,
      lineHeight = 16.sp,
      modifier = modifier,
  )
}

// ---- Place details ------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceDetailCard(
    result: PlaceResult,
    navigating: Boolean,
    exactDetourS: Double?,
    onAddStop: () -> Unit,
    onDrive: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    priceCountry: String? = null,
) {
  val p = result.place
  Column(
      modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
          .background(Drivr.card)
          .padding(20.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      PlaceIcon(p.category, size = 48.dp)
      Spacer(Modifier.width(14.dp))
      Column(Modifier.weight(1f)) {
        Text(p.name, color = Drivr.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        Text(
            listOfNotNull(p.category?.label, p.address).joinToString(" · "),
            color = Drivr.textMuted,
            fontSize = 14.sp,
            maxLines = 2,
        )
      }
      Text("Back", color = Drivr.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
          modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(8.dp))
    }

    // Where it is relative to the trip.
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      if (result.alongRouteM != null) {
        Stat("On route in", formatDistance(result.alongRouteM), Modifier.weight(1f))
        Stat("Off route", formatDistance(result.offRouteM ?: 0.0), Modifier.weight(1f))
        Stat(
            if (exactDetourS != null) "Extra time" else "Extra time (est.)",
            ((exactDetourS ?: result.detourS ?: 0.0) / 60).roundToInt().let { if (it < 1) "<1 min" else "+$it min" },
            Modifier.weight(1f),
            highlight = exactDetourS != null,
        )
      } else {
        Stat("Distance", formatDistance(result.distanceM), Modifier.weight(1f))
        p.openingHours?.let { Stat("Hours", if (p.is24h) "24/7" else it, Modifier.weight(2f)) }
      }
    }

    val chips = buildList {
      addAll(p.fuels)
      addAll(p.sockets)
      p.cuisine?.let { add(it) }
      if (p.is24h) add("Open 24/7") else if (result.alongRouteM != null) p.openingHours?.let { add(it) }
      if (p.hasShop) add("Shop")
      if (p.hasToilets) add("Toilets")
      if (p.selfService) add("Unmanned")
      p.osmCharge?.let { add("Charge: $it") }
    }
    if (chips.isNotEmpty()) {
      Spacer(Modifier.height(14.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { Chip(it) }
      }
    }

    if (p.category == PlaceCategory.FUEL) {
      Spacer(Modifier.height(16.dp))
      val prices = result.prices
      if (prices != null) PriceTable(prices)
      else if (priceCountry == null) PriceCoverageNote()
      else Text("No current prices reported for this station.", color = Drivr.textMuted, fontSize = 13.sp)
    }

    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      if (navigating) {
        BigButton("Add stop", primary = true, onClick = onAddStop, modifier = Modifier.weight(1f))
        BigButton("Go here instead", primary = false, onClick = onDrive, modifier = Modifier.weight(1f))
      } else {
        BigButton("Drive here", primary = true, onClick = onDrive, modifier = Modifier.weight(1f))
      }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "Data © OpenStreetMap contributors" + (result.prices?.let { " · Prices: ${it.source}" } ?: ""),
        color = Drivr.inactive,
        fontSize = 11.sp,
    )
  }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, highlight: Boolean = false) {
  Column(modifier.clip(RoundedCornerShape(12.dp)).background(Drivr.cardBand).padding(horizontal = 12.dp, vertical = 10.dp)) {
    Text(label, color = Drivr.textMuted, fontSize = 12.sp, maxLines = 1)
    Text(value, color = if (highlight) Drivr.ok else Drivr.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
private fun Chip(text: String) {
  Text(
      text,
      color = Drivr.text,
      fontSize = 13.sp,
      modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, Drivr.inactive, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
  )
}

@Composable
private fun PriceTable(prices: FuelPrices) {
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Drivr.cardBand).padding(12.dp)) {
    Text("Pump prices", color = Drivr.textMuted, fontSize = 12.sp)
    prices.perLitre.forEach { (fuel, price) ->
      Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(fuel, color = Drivr.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text("${formatPrice(price)} /L", color = Drivr.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

@Composable
private fun BigButton(text: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier) {
  Box(
      modifier
          .clip(RoundedCornerShape(14.dp))
          .background(if (primary) Drivr.accent else Drivr.cardBand)
          .clickable(onClick = onClick)
          .padding(vertical = 15.dp),
      contentAlignment = Alignment.Center,
  ) {
    Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
  }
}

/** "Stops" button shown while driving, next to Turns/AR. */
@Composable
fun StopsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
  Column(
      modifier.clip(RoundedCornerShape(16.dp)).background(Drivr.card).clickable(onClick = onClick).padding(10.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    PlaceIcon(PlaceCategory.FUEL, size = 30.dp)
    Spacer(Modifier.height(4.dp))
    Text("Stops", color = Color.White, fontSize = 12.sp)
  }
}

// ---- Map pins -----------------------------------------------------------------------------------

/** Result pins on the map: category colour, white ring, the selected one larger and labelled. */
@Composable
@MaplibreComposable
fun PlacesLayer(results: List<PlaceResult>, selectedId: String?) {
  val json = remember(results, selectedId) { placesGeoJson(results, selectedId) }
  val source = rememberGeoJsonSource(GeoJsonData.JsonString(json))
  CircleLayer(
      id = "drivr-places",
      source = source,
      color = feature["color"].cast(),
      radius = interpolate(exponential(1.5f), zoom(), 9 to const(5.dp), 16 to const(11.dp)),
      strokeColor = const(Color.White),
      strokeWidth = const(2.5.dp),
  )
  SymbolLayer(
      id = "drivr-places-label",
      source = source,
      minZoom = 12f,
      textField = feature["name"].cast(),
      textFont = const(listOf("Noto Sans Bold")),
      textSize = const(13.sp),
      textColor = const(Color(0xFF1B1F24)),
      textHaloColor = const(Color.White),
      textHaloWidth = const(2.dp),
      textOffset = offset(0.em, 1.1.em),
      textAnchor = const(SymbolAnchor.Top),
      textOptional = const(true),
  )
}

private fun placesGeoJson(results: List<PlaceResult>, selectedId: String?): String {
  val features =
      results.joinToString(",") { r ->
        val p = r.place
        val color = "#%06X".format(0xFFFFFF and (p.category?.color() ?: Drivr.inactive).toArgb())
        val name = p.name.replace("\\", "").replace("\"", "'")
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]},"properties":{"color":"$color","name":"$name","selected":${p.id == selectedId}}}"""
      }
  return """{"type":"FeatureCollection","features":[$features]}"""
}
