package app.drivr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.launch

/**
 * A card that can be swiped off the screen sideways (either way). Past ~30% of its width, or on
 * a quick flick, it slides out and [onDismiss] is called; otherwise it springs back. Also offers
 * "Dismiss" as an accessibility action. Taking the drag here also keeps the swipe from panning
 * the map underneath (which used to stop the camera following the car).
 */
@Composable
fun SwipeAway(
    key: Any,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = "Dismiss",
    content: @Composable () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val offset = remember(key) { Animatable(0f) }
  var width by remember { mutableIntStateOf(1) }
  val drag = rememberDraggableState { d -> scope.launch { offset.snapTo(offset.value + d) } }
  Box(
      modifier
          .onSizeChanged { width = it.width.coerceAtLeast(1) }
          .graphicsLayer {
            translationX = offset.value
            alpha = 1f - (abs(offset.value) / width).coerceIn(0f, 1f) * 0.7f
          }
          .semantics {
            customActions = listOf(CustomAccessibilityAction(dismissLabel) { onDismiss(); true })
          }
          .draggable(
              drag,
              Orientation.Horizontal,
              onDragStopped = { velocity ->
                val past = abs(offset.value) > width * 0.3f
                val flick = abs(velocity) > 1200f && sign(velocity) == sign(offset.value)
                if (past || flick) {
                  val dir = if (offset.value != 0f) sign(offset.value) else sign(velocity)
                  offset.animateTo(dir * width * 1.1f, tween(160))
                  onDismiss()
                } else {
                  offset.animateTo(0f, spring())
                }
              },
          )
  ) {
    content()
  }
}
