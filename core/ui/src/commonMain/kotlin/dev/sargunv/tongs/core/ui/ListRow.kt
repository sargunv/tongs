package dev.sargunv.tongs.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
 * @param cursor outlines the row, for a keyboard cursor that moves apart from [selected].
 * @param overlineTrailing goes at the end of the overline, as a mail client puts a message's time.
 * @param supportingContent replaces [supporting] when the line needs more than plain text.
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
  cursor: Boolean = false,
  overlineTrailing: (@Composable () -> Unit)? = null,
  supportingContent: (@Composable () -> Unit)? = null,
) {
  val density = LocalTongsDensity.current
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focused by interactions.collectIsFocusedAsState()
  val colors = MaterialTheme.colorScheme
  // Rows are inset pills, so a selected or hovered row reads as one shape.
  Surface(
    color =
      when {
        selected -> colors.secondaryContainer
        hovered && hoverActions != null -> colors.surfaceContainerHigh
        else -> colors.surface
      },
    contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
    shape = MaterialTheme.shapes.large,
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp)
        .clip(MaterialTheme.shapes.large)
        .then(
          if (cursor) {
            Modifier.border(2.dp, colors.outline, MaterialTheme.shapes.large)
          } else {
            Modifier
          },
        )
        .combinedClickable(
          interactionSource = interactions,
          onClick = onClick,
          onLongClick = onLongClick,
        ),
  ) {
    Box(contentAlignment = Alignment.CenterEnd) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                overline,
                style = MaterialTheme.typography.labelMedium,
                color = LocalContentColor.current.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
              if (overlineTrailing != null) {
                CompositionLocalProvider(
                  LocalTextStyle provides MaterialTheme.typography.labelMedium,
                  LocalContentColor provides LocalContentColor.current.copy(alpha = 0.72f),
                ) {
                  Box(Modifier.padding(start = 8.dp)) { overlineTrailing() }
                }
              }
            }
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
          if (supportingContent != null) {
            CompositionLocalProvider(
              LocalTextStyle provides MaterialTheme.typography.bodyMedium,
              LocalContentColor provides LocalContentColor.current.copy(alpha = 0.72f),
            ) {
              supportingContent()
            }
          } else if (supporting != null) {
            Text(
              supporting,
              style = MaterialTheme.typography.bodyMedium,
              color = LocalContentColor.current.copy(alpha = 0.72f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          trailing?.invoke()
          if (emphasized) UnreadDot() else Box(Modifier.width(8.dp))
        }
      }
      // Over the row's end rather than in place of its trailing content, so the row keeps its
      // layout as the pointer passes over it, like a mail client's row actions.
      if (hoverActions != null && (hovered || focused)) {
        val background = if (selected) colors.secondaryContainer else colors.surfaceContainerHigh
        // The row's full height, so the actions cover its time and dot rather than cut them.
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
              Modifier.fillMaxHeight()
                .background(
                  Brush.horizontalGradient(0f to background.copy(alpha = 0f), 0.2f to background),
                )
                .padding(start = 24.dp, end = 8.dp),
            content = hoverActions,
          )
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
  enabled: Boolean = true,
) {
  TooltipBox(
    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
    tooltip = { PlainTooltip { Text(label) } },
    state = rememberTooltipState(),
  ) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
      Icon(icon, contentDescription = label)
    }
  }
}
