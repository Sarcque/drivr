package app.drivr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.drivr.PlanField
import app.drivr.PlanState
import app.drivr.R
import app.drivr.RouteStart
import app.drivr.places.Place
import app.drivr.places.SavedPlaces
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable

object PlanColors {
  val start = Color(0xFF5BD39A)
  val destination = Color(0xFFE5484D)
}

// ---- Start screen: home, work, recents (replaces the test-drive card) ----------------------------

/**
 * The start screen's bottom card: a "Route" button (opens the planner, where the start can be any
 * address), then Home, Work and recent destinations, one tap each. The test drive only shows when
 * turned on under Settings › Developer.
 */
@Composable
fun QuickPanel(
    saved: SavedPlaces,
    onPlan: () -> Unit,
    onPlace: (Place) -> Unit,
    onSetHome: () -> Unit,
    onSetWork: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    onTestDrive: (() -> Unit)? = null,
) {
  Column(
      modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Drivr.card).padding(vertical = 12.dp),
  ) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
          Modifier.clip(RoundedCornerShape(20.dp)).background(Drivr.accent).clickable(onClick = onPlan)
              .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
        else RouteGlyph(Color.White, Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(stringResource(R.string.plan_route), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
      }
      SavedChip(SavedKind.HOME, saved.home?.let { stringResource(R.string.plan_home) } ?: stringResource(R.string.plan_set_home)) {
        saved.home?.let(onPlace) ?: onSetHome()
      }
      SavedChip(SavedKind.WORK, saved.work?.let { stringResource(R.string.plan_work) } ?: stringResource(R.string.plan_set_work)) {
        saved.work?.let(onPlace) ?: onSetWork()
      }
      saved.recent.take(5).forEach { p -> SavedChip(SavedKind.RECENT, p.name) { onPlace(p) } }
      if (onTestDrive != null) SavedChip(SavedKind.TEST, stringResource(R.string.browse_test_drive), onClick = onTestDrive)
    }
    Text(
        stringResource(R.string.osm_credit),
        color = Drivr.inactive,
        fontSize = 10.sp,
        modifier = Modifier.padding(start = 18.dp, top = 8.dp),
    )
  }
}

enum class SavedKind { HOME, WORK, RECENT, TEST, PIN, HERE }

@Composable
private fun SavedChip(kind: SavedKind, label: String, onClick: () -> Unit) {
  Row(
      Modifier.clip(RoundedCornerShape(20.dp)).background(Drivr.cardBand).clickable(onClick = onClick)
          .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    SavedGlyph(kind, Drivr.textMuted, Modifier.size(18.dp))
    Spacer(Modifier.width(7.dp))
    Text(label, color = Drivr.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 170.dp))
  }
}


// ---- Planner: from / to --------------------------------------------------------------------------

/** The two fields, From and To, with a swap button. Tap a field to change it. */
@Composable
fun PlanFieldsCard(
    plan: PlanState,
    onEdit: (PlanField) -> Unit,
    onSwap: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Row(
      modifier.fillMaxWidth().clip(RoundedCornerShape(Drivr.cardRadius)).background(Drivr.card)
          .padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    BackButton(onClose)
    Column(Modifier.weight(1f)) {
      val from = plan.from
      FieldRow(
          dot = PlanColors.start,
          text = if (from is RouteStart.At) from.place.name else stringResource(R.string.plan_your_location),
          highlight = from is RouteStart.At,
          onClick = { onEdit(PlanField.FROM) },
      )
      HorizontalDivider(color = Drivr.cardBand, modifier = Modifier.padding(start = 30.dp))
      FieldRow(
          dot = PlanColors.destination,
          text = plan.to?.name ?: stringResource(R.string.places_where_to),
          muted = plan.to == null,
          onClick = { onEdit(PlanField.TO) },
      )
    }
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(enabled = plan.to != null, onClickLabel = stringResource(R.string.plan_swap), onClick = onSwap),
        contentAlignment = Alignment.Center,
    ) { SwapGlyph(if (plan.to != null) Drivr.text else Drivr.inactive) }
  }
}

@Composable
private fun FieldRow(dot: Color, text: String, onClick: () -> Unit, muted: Boolean = false, highlight: Boolean = false) {
  Row(
      Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 11.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(dot).border(2.dp, Color.White, CircleShape))
    Spacer(Modifier.width(12.dp))
    Text(
        text,
        color = if (muted) Drivr.textMuted else Drivr.text,
        fontSize = 16.sp,
        fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
  }
}

/** Typing into one field: a search box with a back button. */
@Composable
fun PlanSearchField(
    field: PlanField,
    query: String,
    onQuery: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
  val focus = remember { FocusRequester() }
  Row(
      modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Drivr.card).padding(start = 4.dp, end = 18.dp, top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    BackButton(onBack)
    Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
      if (query.isEmpty()) Text(fieldHint(field), color = Drivr.textMuted, fontSize = 17.sp, maxLines = 1)
      BasicTextField(
          value = query,
          onValueChange = onQuery,
          singleLine = true,
          textStyle = TextStyle(color = Drivr.text, fontSize = 17.sp),
          cursorBrush = SolidColor(Drivr.accent),
          modifier = Modifier.fillMaxWidth().focusRequester(focus),
      )
    }
  }
  if (autoFocus) LaunchedEffect(field) { runCatching { focus.requestFocus() } }
}

@Composable
private fun fieldHint(field: PlanField): String =
    stringResource(
        when (field) {
          PlanField.FROM -> R.string.plan_choose_start
          PlanField.TO -> R.string.places_where_to
          PlanField.HOME -> R.string.plan_set_home
          PlanField.WORK -> R.string.plan_set_work
        })

/**
 * Suggestions under the field being edited: your location (for the start), a point on the map,
 * home and work, recent places, then search results as you type.
 */
@Composable
fun PlanSuggestions(
    plan: PlanState,
    saved: SavedPlaces,
    onHere: () -> Unit,
    onPickOnMap: () -> Unit,
    onPlace: (Place) -> Unit,
    modifier: Modifier = Modifier,
    maxListHeight: Dp = 420.dp,
) {
  val field = plan.editing ?: return
  data class Row_(val kind: SavedKind, val title: String, val sub: String?, val onClick: () -> Unit)
  val rows = buildList {
    if (plan.query.isBlank()) {
      if (field == PlanField.FROM) add(Row_(SavedKind.HERE, stringResource(R.string.plan_your_location), null, onHere))
      add(Row_(SavedKind.PIN, stringResource(R.string.plan_choose_on_map), null, onPickOnMap))
      if (field == PlanField.FROM || field == PlanField.TO) {
        saved.home?.let { add(Row_(SavedKind.HOME, stringResource(R.string.plan_home), it.address ?: it.name) { onPlace(it) }) }
        saved.work?.let { add(Row_(SavedKind.WORK, stringResource(R.string.plan_work), it.address ?: it.name) { onPlace(it) }) }
      }
      saved.recent.forEach { p -> add(Row_(SavedKind.RECENT, p.name, p.address) { onPlace(p) }) }
    } else {
      plan.results.forEach { r -> add(Row_(SavedKind.PIN, r.place.name, listOfNotNull(r.place.address, formatDistance(r.distanceM)).joinToString(" · ")) { onPlace(r.place) }) }
    }
  }
  Column(
      modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Drivr.card).padding(vertical = 8.dp),
  ) {
    when {
      plan.searching && rows.isEmpty() -> Status(stringResource(R.string.places_searching), spinner = true)
      plan.searchError != null -> Status(plan.searchError)
      plan.query.isNotBlank() && rows.isEmpty() -> Status(stringResource(R.string.plan_nothing_found))
      else ->
          LazyColumn(Modifier.heightIn(max = maxListHeight)) {
            items(rows) { r ->
              Row(
                  Modifier.fillMaxWidth().clickable(onClick = r.onClick).padding(horizontal = 20.dp, vertical = 11.dp),
                  verticalAlignment = Alignment.CenterVertically,
              ) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(Drivr.cardBand), contentAlignment = Alignment.Center) {
                  SavedGlyph(r.kind, if (r.kind == SavedKind.HERE) Drivr.accent else Drivr.text, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                  Text(r.title, color = Drivr.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                  r.sub?.let { Text(it, color = Drivr.textMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
              }
            }
          }
    }
  }
}

@Composable
private fun Status(text: String, spinner: Boolean = false) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
    if (spinner) {
      CircularProgressIndicator(Modifier.size(18.dp), color = Drivr.accent, strokeWidth = 2.dp)
      Spacer(Modifier.width(12.dp))
    }
    Text(text, color = Drivr.textMuted, fontSize = 15.sp)
  }
}

/** Waiting for a long-press on the map. */
@Composable
fun MapPickHint(field: PlanField, onCancel: () -> Unit, modifier: Modifier = Modifier) {
  Row(
      modifier.fillMaxWidth().clip(RoundedCornerShape(Drivr.cardRadius)).background(Drivr.card)
          .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    SavedGlyph(SavedKind.PIN, if (field == PlanField.FROM) PlanColors.start else PlanColors.destination, Modifier.size(20.dp))
    Spacer(Modifier.width(12.dp))
    Text(
        stringResource(if (field == PlanField.FROM) R.string.plan_pick_start_on_map else R.string.plan_pick_on_map),
        color = Drivr.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(vertical = 10.dp),
    )
    Text(stringResource(R.string.action_cancel), color = Drivr.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onCancel).padding(12.dp))
  }
}

/**
 * The routes found, under the fields: pick one, then Start. From another address the button says
 * "Preview drive": the phone isn't there, so it plays the drive as a simulation.
 */
@Composable
fun PlanRoutesCard(plan: PlanState, onSelect: (Int) -> Unit, onStart: () -> Unit, modifier: Modifier = Modifier) =
    PlanRoutesCard(plan.options.map { it.score }, plan.selected, plan.fromHere, onSelect, onStart, modifier)

@Composable
fun PlanRoutesCard(
    options: List<app.drivr.traffic.ScoredRoute>,
    selected: Int,
    fromHere: Boolean,
    onSelect: (Int) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
  if (options.isEmpty()) return
  RouteChoiceCard(
      options = options,
      selected = selected,
      onSelect = onSelect,
      onGo = onStart,
      modifier = modifier,
      goLabel = stringResource(if (fromHere) R.string.plan_start else R.string.plan_preview_drive),
      note = if (fromHere) null else stringResource(R.string.plan_preview_note),
  )
}

// ---- Driving: end the trip only on purpose ---------------------------------------------------------

/** "End this trip?" so a stray tap on the arrival card or the menu doesn't drop the guidance. */
@Composable
fun ConfirmEndTrip(destination: String?, onKeep: () -> Unit, onEnd: () -> Unit) {
  Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(onClick = onKeep), contentAlignment = Alignment.Center) {
    Column(
        Modifier.padding(24.dp).clip(RoundedCornerShape(Drivr.cardRadius)).background(Drivr.card).clickable(enabled = false) {}
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp),
    ) {
      Text(stringResource(R.string.end_trip_title), color = Drivr.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
      if (destination != null) {
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.end_trip_body, destination), color = Drivr.textMuted, fontSize = 15.sp, lineHeight = 20.sp)
      }
      Spacer(Modifier.height(18.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.end_trip_keep), color = Drivr.text, fontSize = 15.sp, textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Drivr.cardBand).clickable(onClick = onKeep).padding(vertical = 12.dp),
        )
        Text(
            stringResource(R.string.menu_end), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(PlanColors.destination).clickable(onClick = onEnd).padding(vertical = 12.dp),
        )
      }
    }
  }
}

// ---- Map pins for the planner ----------------------------------------------------------------------

/** Start (green) and destination (red) of the planned route. Always composed, empty when unused. */
@Composable
@MaplibreComposable
fun PlanPinsLayer(from: Place?, to: Place?) {
  val json =
      remember(from, to) {
        val features =
            listOfNotNull(from?.let { it to "#5BD39A" }, to?.let { it to "#E5484D" }).joinToString(",") { (p, c) ->
              """{"type":"Feature","geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]},"properties":{"color":"$c"}}"""
            }
        """{"type":"FeatureCollection","features":[$features]}"""
      }
  val source = rememberGeoJsonSource(GeoJsonData.JsonString(json))
  CircleLayer(
      id = "drivr-plan-pins",
      source = source,
      color = feature["color"].cast(),
      radius = const(9.dp),
      strokeColor = const(Color.White),
      strokeWidth = const(3.dp),
  )
}

// ---- Glyphs ----------------------------------------------------------------------------------------

@Composable
private fun BackButton(onClick: () -> Unit) {
  Box(
      Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = stringResource(R.string.action_close), onClick = onClick),
      contentAlignment = Alignment.Center,
  ) {
    Canvas(Modifier.size(18.dp)) {
      val w = size.width
      val h = size.height
      val s = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
      drawLine(Drivr.text, Offset(w * 0.1f, h / 2), Offset(w * 0.9f, h / 2), 2.dp.toPx(), StrokeCap.Round)
      drawPath(Path().apply { moveTo(w * 0.5f, h * 0.1f); lineTo(w * 0.1f, h / 2); lineTo(w * 0.5f, h * 0.9f) }, Drivr.text, style = s)
    }
  }
}

@Composable
private fun SwapGlyph(color: Color) {
  Canvas(Modifier.size(22.dp)) {
    val w = size.width
    val h = size.height
    val sw = 2.dp.toPx()
    val s = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // Up arrow on the left, down arrow on the right.
    drawLine(color, Offset(w * 0.32f, h * 0.88f), Offset(w * 0.32f, h * 0.12f), sw, StrokeCap.Round)
    drawPath(Path().apply { moveTo(w * 0.1f, h * 0.34f); lineTo(w * 0.32f, h * 0.12f); lineTo(w * 0.54f, h * 0.34f) }, color, style = s)
    drawLine(color, Offset(w * 0.68f, h * 0.12f), Offset(w * 0.68f, h * 0.88f), sw, StrokeCap.Round)
    drawPath(Path().apply { moveTo(w * 0.46f, h * 0.66f); lineTo(w * 0.68f, h * 0.88f); lineTo(w * 0.9f, h * 0.66f) }, color, style = s)
  }
}

@Composable
fun RouteGlyph(color: Color, modifier: Modifier = Modifier) {
  Canvas(modifier) {
    val w = size.width
    val h = size.height
    // A turn arrow: up, then right.
    val s = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(Path().apply { moveTo(w * 0.25f, h * 0.95f); lineTo(w * 0.25f, h * 0.5f); quadraticTo(w * 0.25f, h * 0.35f, w * 0.4f, h * 0.35f); lineTo(w * 0.85f, h * 0.35f) }, color, style = s)
    drawPath(Path().apply { moveTo(w * 0.62f, h * 0.12f); lineTo(w * 0.85f, h * 0.35f); lineTo(w * 0.62f, h * 0.58f) }, color, style = s)
  }
}

@Composable
fun SavedGlyph(kind: SavedKind, color: Color, modifier: Modifier = Modifier) {
  Canvas(modifier) {
    val w = size.width
    val h = size.height
    val sw = 1.8.dp.toPx()
    val s = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (kind) {
      SavedKind.HOME -> {
        drawPath(Path().apply { moveTo(w * 0.1f, h * 0.5f); lineTo(w * 0.5f, h * 0.12f); lineTo(w * 0.9f, h * 0.5f) }, color, style = s)
        drawPath(Path().apply { moveTo(w * 0.22f, h * 0.42f); lineTo(w * 0.22f, h * 0.9f); lineTo(w * 0.78f, h * 0.9f); lineTo(w * 0.78f, h * 0.42f) }, color, style = s)
      }
      SavedKind.WORK -> {
        drawRoundRect(color, Offset(w * 0.1f, h * 0.3f), Size(w * 0.8f, h * 0.58f), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = s)
        drawPath(Path().apply { moveTo(w * 0.36f, h * 0.3f); lineTo(w * 0.36f, h * 0.14f); lineTo(w * 0.64f, h * 0.14f); lineTo(w * 0.64f, h * 0.3f) }, color, style = s)
      }
      SavedKind.RECENT -> {
        drawCircle(color, w * 0.42f, center, style = s)
        drawLine(color, center, Offset(w * 0.5f, h * 0.24f), sw, StrokeCap.Round)
        drawLine(color, center, Offset(w * 0.7f, h * 0.6f), sw, StrokeCap.Round)
      }
      SavedKind.TEST -> drawPath(Path().apply { moveTo(w * 0.25f, h * 0.15f); lineTo(w * 0.85f, h * 0.5f); lineTo(w * 0.25f, h * 0.85f); close() }, color)
      SavedKind.PIN -> {
        drawCircle(color, w * 0.3f, Offset(w * 0.5f, h * 0.36f), style = s)
        drawPath(Path().apply { moveTo(w * 0.24f, h * 0.52f); lineTo(w * 0.5f, h * 0.95f); lineTo(w * 0.76f, h * 0.52f) }, color, style = s)
      }
      SavedKind.HERE -> {
        drawCircle(color, w * 0.22f, center)
        drawCircle(color, w * 0.42f, center, style = s)
      }
    }
  }
}

/**
 * The planner's routes on the map before the trip starts: the chosen one blue, the others grey.
 * Own layer ids, so it never clashes with the driving route line.
 */
@Composable
@MaplibreComposable
fun PlanRoutesLayer(selected: List<uniffi.ferrostar.GeographicCoordinate>?, others: List<List<uniffi.ferrostar.GeographicCoordinate>>) {
  fun fc(lines: List<List<uniffi.ferrostar.GeographicCoordinate>>) =
      """{"type":"FeatureCollection","features":[${lines.filter { it.size >= 2 }.joinToString(",") { r ->
        """{"type":"Feature","geometry":{"type":"LineString","coordinates":[${r.joinToString(",") { "[${it.lng},${it.lat}]" }}]},"properties":{}}"""
      }}]}"""
  val altJson = remember(others) { fc(others) }
  val mainJson = remember(selected) { fc(listOfNotNull(selected)) }
  val alt = rememberGeoJsonSource(GeoJsonData.JsonString(altJson))
  val main = rememberGeoJsonSource(GeoJsonData.JsonString(mainJson))
  val w = { a: Dp, b: Dp -> org.maplibre.compose.expressions.dsl.interpolate(org.maplibre.compose.expressions.dsl.exponential(1.5f), org.maplibre.compose.expressions.dsl.zoom(), 10 to const(a), 18 to const(b)) }
  val round = const(org.maplibre.compose.expressions.value.LineCap.Round)
  val join = const(org.maplibre.compose.expressions.value.LineJoin.Round)
  org.maplibre.compose.layers.LineLayer(id = "drivr-plan-alt-casing", source = alt, color = const(AltRouteColors.casing), width = w(7.dp, 26.dp), cap = round, join = join)
  org.maplibre.compose.layers.LineLayer(id = "drivr-plan-alt-fill", source = alt, color = const(AltRouteColors.fill), width = w(4.dp, 18.dp), cap = round, join = join)
  org.maplibre.compose.layers.LineLayer(id = "drivr-plan-casing", source = main, color = const(RouteColors.casing), width = w(9.dp, 34.dp), cap = round, join = join)
  org.maplibre.compose.layers.LineLayer(id = "drivr-plan-fill", source = main, color = const(RouteColors.fill), width = w(6.dp, 24.dp), cap = round, join = join)
}
