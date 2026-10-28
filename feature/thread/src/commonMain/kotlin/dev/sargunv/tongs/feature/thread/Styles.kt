package dev.sargunv.tongs.feature.thread

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.sargunv.tongs.core.ui.ConversationIcons
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.core.ui.stateColors
import dev.sargunv.tongs.forge.api.CheckState
import dev.sargunv.tongs.forge.api.ReviewState
import dev.sargunv.tongs.forge.api.StateReason
import dev.sargunv.tongs.forge.api.ThreadDetail
import dev.sargunv.tongs.forge.api.ThreadKind
import dev.sargunv.tongs.forge.api.ThreadState

/** A thread's state as a label, an icon, and a color. */
internal data class StateLook(val label: String, val icon: ImageVector, val color: Color)

@Composable
internal fun stateLook(detail: ThreadDetail): StateLook {
  val colors = stateColors()
  val isPr = detail.kind == ThreadKind.PullRequest
  val icon = if (isPr) TongsIcons.PullRequest else TongsIcons.Issue
  return when (detail.state) {
    ThreadState.Open ->
      if (detail.pullRequest?.isDraft == true) StateLook("Draft", icon, colors.neutral)
      else StateLook("Open", icon, colors.open)
    ThreadState.Merged -> StateLook("Merged", TongsIcons.PullRequest, colors.merged)
    ThreadState.Closed ->
      when {
        isPr -> StateLook("Closed", icon, colors.closed)
        detail.stateReason == StateReason.NotPlanned ->
          StateLook("Not planned", ConversationIcons.Skipped, colors.neutral)
        detail.stateReason == StateReason.Duplicate ->
          StateLook("Duplicate", ConversationIcons.Skipped, colors.neutral)
        else -> StateLook("Closed", ConversationIcons.CheckCircle, colors.merged)
      }
    is ThreadState.Other ->
      StateLook(
        detail.state.raw.lowercase().replaceFirstChar { it.uppercase() },
        icon,
        colors.neutral,
      )
  }
}

/** A filled pill with the thread's state, like GitHub's, but quieter. */
@Composable
internal fun StatePill(look: StateLook, modifier: Modifier = Modifier) {
  Surface(
    color = look.color,
    contentColor = Color.White,
    shape = CircleShape,
    modifier = modifier,
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
    ) {
      Icon(look.icon, null, Modifier.size(15.dp))
      Spacer(Modifier.width(4.dp))
      Text(look.label, style = MaterialTheme.typography.labelMedium)
    }
  }
}

@Composable
internal fun checkColor(state: CheckState?): Color {
  val colors = stateColors()
  return when (state) {
    CheckState.Success -> colors.open
    CheckState.Failure,
    CheckState.Error -> colors.closed
    CheckState.Pending,
    CheckState.Expected -> colors.pending
    else -> colors.neutral
  }
}

internal fun checkIcon(state: CheckState?): ImageVector =
  when (state) {
    CheckState.Success -> ConversationIcons.CheckCircle
    CheckState.Failure,
    CheckState.Error -> ConversationIcons.Cancel
    CheckState.Pending,
    CheckState.Expected -> TongsIcons.Schedule
    else -> ConversationIcons.Skipped
  }

@Composable
internal fun reviewColor(state: ReviewState): Color {
  val colors = stateColors()
  return when (state) {
    ReviewState.Approved -> colors.open
    ReviewState.ChangesRequested -> colors.closed
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
}

internal fun reviewIcon(state: ReviewState): ImageVector =
  when (state) {
    ReviewState.Approved -> ConversationIcons.CheckCircle
    ReviewState.ChangesRequested -> ConversationIcons.ErrorFilled
    else -> TongsIcons.RateReview
  }

internal fun reviewVerb(state: ReviewState): String =
  when (state) {
    ReviewState.Approved -> "approved"
    ReviewState.ChangesRequested -> "requested changes"
    ReviewState.Dismissed -> "reviewed (dismissed)"
    ReviewState.Pending -> "started a review"
    else -> "reviewed"
  }

/** A label's color, from the forge's hex, or the theme's when there's none. */
@Composable
internal fun labelColors(hex: String?): Pair<Color, Color> {
  val base =
    hex?.removePrefix("#")?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
      ?: return MaterialTheme.colorScheme.secondaryContainer to
        MaterialTheme.colorScheme.onSecondaryContainer
  val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
  // GitHub's approach: a tinted fill with text in the label's own hue, readable on either theme.
  return if (dark) {
    base.copy(alpha = 0.22f) to lighten(base)
  } else {
    base.copy(alpha = 0.18f) to darken(base)
  }
}

private fun lighten(color: Color): Color =
  if (color.luminance() > 0.45f) color
  else Color(lerp(color.red, 1f, 0.45f), lerp(color.green, 1f, 0.45f), lerp(color.blue, 1f, 0.45f))

private fun darken(color: Color): Color =
  if (color.luminance() < 0.25f) color
  else Color(lerp(color.red, 0f, 0.5f), lerp(color.green, 0f, 0.5f), lerp(color.blue, 0f, 0.5f))

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** `1 commit`, `3 commits`. */
internal fun plural(count: Int, one: String, many: String = one + "s") =
  "$count ${if (count == 1) one else many}"
