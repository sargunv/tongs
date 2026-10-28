package dev.sargunv.tongs.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A row in a list: the shape every list in the app uses, so lists look and behave alike.
 *
 * On desktop, [hoverActions] replace [trailing] while the pointer is over the row or it has
 * keyboard focus, like a mail client's row actions. Touch platforms never show them; offer the same
 * actions by swiping (see [SwipeActionsBox]).
 *
 * @param emphasized bolds the headline and shows a dot, for unread items.
 * @param selected highlights the row, as for the item open in a detail pane.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
  headline: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  overline: String? = null,
  supporting: String? = null,
  leading: (@Composable () -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null,
  hoverActions: (@Composable RowScope.() -> Unit)? = null,
  emphasized: Boolean = false,
  selected: Boolean = false,
  onLongClick: (() -> Unit)? = null,
) {
  val density = LocalTongsDensity.current
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focused by interactions.collectIsFocusedAsState()
  val colors = MaterialTheme.colorScheme
  // Rows are inset pills, so a selected or hovered row reads as one shape.
  Surface(
    color = if (selected) colors.secondaryContainer else colors.surface,
    contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
    shape = MaterialTheme.shapes.large,
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp)
        .clip(MaterialTheme.shapes.large)
        .combinedClickable(
          interactionSource = interactions,
          onClick = onClick,
          onLongClick = onLongClick,
        ),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      modifier =
        Modifier.padding(
          horizontal = density.rowHorizontalPadding - 8.dp,
          vertical = density.rowVerticalPadding,
        ),
    ) {
      if (leading != null) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { leading() }
      }
      Column(Modifier.weight(1f)) {
        if (overline != null) {
          Text(
            overline,
            style = MaterialTheme.typography.labelMedium,
            color = LocalContentColor.current.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Text(
          headline,
          style =
            MaterialTheme.typography.bodyLarge.copy(
              fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            ),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (supporting != null) {
          Text(
            supporting,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      Box(contentAlignment = Alignment.CenterEnd) {
        if (hoverActions != null && (hovered || focused)) {
          Row(verticalAlignment = Alignment.CenterVertically, content = hoverActions)
        } else {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            trailing?.invoke()
            if (emphasized) UnreadDot() else Box(Modifier.width(8.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun UnreadDot() {
  Surface(
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.size(8.dp).clip(CircleShape),
  ) {}
}

/** An icon button with a tooltip, for row actions and app bar actions. */
@Composable
fun ActionIcon(
  icon: ImageVector,
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  TooltipBox(
    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
    tooltip = { PlainTooltip { Text(label) } },
    state = rememberTooltipState(),
  ) {
    IconButton(onClick = onClick, modifier = modifier) { Icon(icon, contentDescription = label) }
  }
}
