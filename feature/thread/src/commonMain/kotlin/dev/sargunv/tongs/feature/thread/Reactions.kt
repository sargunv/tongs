package dev.sargunv.tongs.feature.thread

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sargunv.tongs.core.ui.ConversationIcons
import dev.sargunv.tongs.forge.api.Reaction
import dev.sargunv.tongs.forge.api.Reactions

/**
 * A body's reactions as chips, with an add button when the account can react. Tapping a chip
 * toggles the account's own reaction.
 */
@Composable
internal fun ReactionRow(
  reactions: List<Reactions>,
  canReact: Boolean,
  onReact: (Reaction) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (reactions.isEmpty() && !canReact) return
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
    itemVerticalAlignment = Alignment.CenterVertically,
    modifier = modifier,
  ) {
    for (group in reactions) {
      ReactionChip(group, enabled = canReact) { onReact(group.reaction) }
    }
    if (canReact) AddReaction(reactions, onReact)
  }
}

@Composable
private fun ReactionChip(group: Reactions, enabled: Boolean, onClick: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  val mine = group.viewerReacted
  Surface(
    onClick = onClick,
    enabled = enabled,
    shape = CircleShape,
    color = if (mine) colors.primaryContainer else colors.surfaceContainerHigh,
    contentColor = if (mine) colors.onPrimaryContainer else colors.onSurfaceVariant,
    border = if (mine) BorderStroke(1.dp, colors.primary.copy(alpha = 0.6f)) else null,
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
      Text(group.reaction.emoji, fontSize = 14.sp)
      Text(
        group.count.toString(),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 5.dp),
      )
    }
  }
}

@Composable
private fun AddReaction(reactions: List<Reactions>, onReact: (Reaction) -> Unit) {
  var open by remember { mutableStateOf(false) }
  val colors = MaterialTheme.colorScheme
  Box {
    Surface(
      onClick = { open = true },
      shape = CircleShape,
      color = colors.surfaceContainerHigh,
      contentColor = colors.onSurfaceVariant,
    ) {
      Icon(
        ConversationIcons.AddReaction,
        "Add a reaction",
        Modifier.padding(horizontal = 8.dp, vertical = 3.dp).size(18.dp),
      )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = CircleShape) {
      Row(Modifier.padding(horizontal = 6.dp)) {
        for (reaction in Reaction.GitHub) {
          val mine = reactions.any { it.reaction == reaction && it.viewerReacted }
          Surface(
            onClick = {
              open = false
              onReact(reaction)
            },
            shape = CircleShape,
            color = if (mine) colors.primaryContainer else colors.surfaceContainer.copy(alpha = 0f),
          ) {
            Text(reaction.emoji, fontSize = 22.sp, modifier = Modifier.padding(6.dp))
          }
        }
      }
    }
  }
}
