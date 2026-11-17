package app.drivr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.drivr.RouteStatus

/** One map action in the button column. */
data class MapAction(val label: String, val icon: MapIcon, val onClick: () -> Unit)

enum class MapIcon { Search, Overview, Recenter, More }

private val ButtonSize = 52.dp
private val ButtonGap = 10.dp

/**
 * The only floating buttons on the map: one column on the right edge. [primary] buttons are shown
 * as round buttons while there is room for them; anything that doesn't fit, plus [menu], goes into
 * a "⋯" menu (a dropdown anchored to its button, so it never covers the cards). The column is laid
 * out inside the free space between the top and bottom cards, so it can't sit on top of them.
 */
@Composable
fun MapButtonColumn(
    primary: List<MapAction>,
    menu: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = false,
) {
  BoxWithConstraints(modifier) {
    val slots = ((maxHeight + ButtonGap) / (ButtonSize + ButtonGap)).toInt()
    if (slots <= 0) return@BoxWithConstraints
    val needsMenu = menu.isNotEmpty() || primary.size > slots
    val shown = if (needsMenu) primary.take((slots - 1).coerceAtLeast(0)) else primary
    val overflow = primary.drop(shown.size).map { it.label to it.onClick } + menu
    Column(
        Modifier.align(Alignment.CenterEnd),
        verticalArrangement = Arrangement.spacedBy(ButtonGap),
        horizontalAlignment = Alignment.End,
    ) {
      shown.forEach { MapRoundButton(it.icon, it.label, it.onClick) }
      if (overflow.isNotEmpty()) {
        var open by rememberSaveable { mutableStateOf(initiallyOpen) }
        Box {
          MapRoundButton(MapIcon.More, "More", onClick = { open = true })
          DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Drivr.card) {
            overflow.forEach { (label, action) ->
              DropdownMenuItem(
                  text = { Text(label, color = Drivr.text, fontSize = 16.sp) },
                  onClick = {
                    open = false
                    action()
                  },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun MapRoundButton(icon: MapIcon, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
      modifier.size(ButtonSize).clip(CircleShape).background(Drivr.card).clickable(onClickLabel = label, onClick = onClick),
      contentAlignment = Alignment.Center,
  ) {
    when (icon) {
      MapIcon.Search -> SearchGlyph(Drivr.text)
      MapIcon.More ->
          Canvas(Modifier.size(18.dp)) {
            listOf(0.2f, 0.5f, 0.8f).forEach { drawCircle(Drivr.text, 2.dp.toPx(), Offset(size.width * it, size.height / 2)) }
          }
      MapIcon.Overview ->
          Canvas(Modifier.size(22.dp)) {
            // A route squiggle between two dots: "show the whole route".
            val w = size.width
            val h = size.height
            val p = Path().apply {
              moveTo(w * 0.2f, h * 0.85f)
              cubicTo(w * 0.2f, h * 0.45f, w * 0.8f, h * 0.55f, w * 0.8f, h * 0.15f)
            }
            drawPath(p, Drivr.text, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(Drivr.text, 2.5.dp.toPx(), Offset(w * 0.2f, h * 0.85f))
            drawCircle(Drivr.accent, 3.dp.toPx(), Offset(w * 0.8f, h * 0.15f))
          }
      MapIcon.Recenter ->
          Canvas(Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            val p = Path().apply {
              moveTo(w * 0.5f, h * 0.08f)
              lineTo(w * 0.86f, h * 0.9f)
              lineTo(w * 0.5f, h * 0.7f)
              lineTo(w * 0.14f, h * 0.9f)
              close()
            }
            drawPath(p, Drivr.accent)
          }
    }
  }
}

/**
 * Routing progress or failure, always visible (also over the places sheet): a spinner with what
 * is happening, or what went wrong with a Retry button.
 */
@Composable
fun RouteStatusCard(status: RouteStatus, onRetry: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
  if (status is RouteStatus.Idle) return
  Row(
      modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(Drivr.cardRadius))
          .background(Drivr.card)
          .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    when (status) {
      is RouteStatus.Loading -> {
        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(status.message, color = Drivr.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
      }
      is RouteStatus.Failed -> {
        Text(status.message, color = Color(0xFFFF8A80), fontSize = 14.sp, maxLines = 3, modifier = Modifier.weight(1f))
        if (status.canRetry) TextButton(onClick = onRetry) { Text("Retry", color = Drivr.accent) }
        TextButton(onClick = onDismiss) { Text("✕", color = Drivr.textMuted) }
      }
      RouteStatus.Idle -> {}
    }
  }
}

/**
 * Portrait layout for everything on top of the map: [top] cards, then the free strip where the
 * [buttons] column lives, then the [bottom] cards. A plain column, so nothing can overlap: when
 * space runs short the button strip shrinks (and folds buttons into "⋯") instead.
 */
@Composable
fun StackedOverlay(
    modifier: Modifier,
    top: @Composable () -> Unit,
    buttons: @Composable (Modifier) -> Unit,
    bottom: @Composable () -> Unit,
    onTopHeight: (androidx.compose.ui.unit.Dp) -> Unit = {},
    onBottomHeight: (androidx.compose.ui.unit.Dp) -> Unit = {},
) {
  val density = androidx.compose.ui.platform.LocalDensity.current
  Column(modifier) {
    Column(
        Modifier.onSizeChanged { onTopHeight(with(density) { it.height.toDp() }) },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { top() }
    Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp)) { buttons(Modifier.fillMaxSize()) }
    Box(Modifier.onSizeChanged { onBottomHeight(with(density) { it.height.toDp() }) }) { bottom() }
  }
}

/**
 * Landscape layout: the cards in one column on the left ([top] cards up, [bottom] cards down),
 * the road on the right, the [buttons] column at the right edge.
 */
@Composable
fun SideOverlay(
    modifier: Modifier,
    top: @Composable () -> Unit,
    buttons: @Composable (Modifier) -> Unit,
    bottom: @Composable () -> Unit,
    onPanelWidth: (androidx.compose.ui.unit.Dp) -> Unit = {},
) {
  val density = androidx.compose.ui.platform.LocalDensity.current
  Row(modifier) {
    Column(
        Modifier.width(380.dp).fillMaxHeight().onSizeChanged { onPanelWidth(with(density) { it.width.toDp() }) },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      top()
      Spacer(Modifier.weight(1f))
      bottom()
    }
    Spacer(Modifier.weight(1f))
    buttons(Modifier.fillMaxHeight().width(52.dp))
  }
}
