package dev.sargunv.tongs.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** What a swipe does, and how its background looks while the row is dragged. */
data class SwipeAction(
  val label: String,
  val icon: ImageVector,
  val container: Color,
  val content: Color,
  val onSwipe: () -> Unit,
)

/**
 * Swipes a row left or right to act on it, like a mail client. Actions are by the direction the
 * finger moves, not start and end, so settings mean the same in every layout direction. The row
 * snaps back after the action; actions that remove the item remove the row themselves.
 */
@Composable
fun SwipeActionsBox(
  left: SwipeAction?,
  right: SwipeAction?,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val state = rememberSwipeToDismissBoxState()
  val scope = rememberCoroutineScope()
  val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  // StartToEnd is a swipe right in left-to-right layouts.
  val startToEnd = if (rtl) left else right
  val endToStart = if (rtl) right else left
  SwipeToDismissBox(
    state = state,
    modifier = modifier,
    enableDismissFromStartToEnd = startToEnd != null,
    enableDismissFromEndToStart = endToStart != null,
    onDismiss = { direction ->
      when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> startToEnd?.onSwipe?.invoke()
        SwipeToDismissBoxValue.EndToStart -> endToStart?.onSwipe?.invoke()
        SwipeToDismissBoxValue.Settled -> {}
      }
      scope.launch { state.snapTo(SwipeToDismissBoxValue.Settled) }
    },
    backgroundContent = {
      val action =
        when (state.dismissDirection) {
          SwipeToDismissBoxValue.StartToEnd -> startToEnd
          SwipeToDismissBoxValue.EndToStart -> endToStart
          SwipeToDismissBoxValue.Settled -> null
        }
      if (action != null) {
        val atStart = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement =
            Arrangement.spacedBy(12.dp, if (atStart) Alignment.Start else Alignment.End),
          modifier =
            Modifier.fillMaxSize().background(action.container).padding(horizontal = 24.dp),
        ) {
          Icon(action.icon, contentDescription = null, tint = action.content)
          Text(action.label, color = action.content, style = MaterialTheme.typography.labelLarge)
        }
      }
    },
  ) {
    content()
  }
}
