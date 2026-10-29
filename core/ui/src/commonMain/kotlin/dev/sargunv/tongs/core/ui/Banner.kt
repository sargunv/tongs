package dev.sargunv.tongs.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * A strip above a list that explains a problem without taking over the screen, as when sync is
 * offline or rate limited: an icon (in the error color if [serious]), the text, and an action.
 */
@Composable
fun Banner(
  icon: ImageVector,
  text: String,
  serious: Boolean,
  modifier: Modifier = Modifier,
  action: String? = null,
  onAction: (() -> Unit)? = null,
) {
  val colors = MaterialTheme.colorScheme
  Surface(
    color = colors.surfaceContainerHigh,
    contentColor = colors.onSurface,
    shape = MaterialTheme.shapes.large,
    modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
      Icon(icon, null, Modifier.size(20.dp), tint = if (serious) colors.error else colors.primary)
      Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
      if (action != null && onAction != null) {
        TextButton(onClick = onAction) { Text(action) }
      }
    }
  }
}

/** An issue's or PR's icon, colored by its state, and its state in words. */
data class SubjectMark(val icon: ImageVector, val color: Color?, val description: String)

/**
 * The mark for an issue or PR in a list: PRs open green, merged purple, closed red, draft gray;
 * issues open green, closed as completed purple, otherwise gray. [type] is raw (`PullRequest`,
 * `Issue`), and [state] is the forge's raw state in either case (`OPEN`, `open`).
 */
fun subjectMark(
  type: String,
  state: String?,
  stateReason: String?,
  isDraft: Boolean,
  colors: StateColors,
): SubjectMark {
  val s = state?.uppercase()
  return when (type) {
    "PullRequest" ->
      when {
        s == null -> SubjectMark(TongsIcons.PullRequest, null, "Pull request")
        s == "MERGED" -> SubjectMark(TongsIcons.PullRequest, colors.merged, "Pull request, merged")
        s == "CLOSED" -> SubjectMark(TongsIcons.PullRequest, colors.closed, "Pull request, closed")
        isDraft -> SubjectMark(TongsIcons.PullRequest, colors.neutral, "Pull request, draft")
        else -> SubjectMark(TongsIcons.PullRequest, colors.open, "Pull request, open")
      }
    "Issue" ->
      when {
        s == null -> SubjectMark(TongsIcons.Issue, null, "Issue")
        s != "CLOSED" -> SubjectMark(TongsIcons.Issue, colors.open, "Issue, open")
        stateReason == "COMPLETED" ->
          SubjectMark(TongsIcons.CheckCircle, colors.merged, "Issue, closed as completed")
        stateReason == null -> SubjectMark(TongsIcons.CheckCircle, colors.merged, "Issue, closed")
        else -> SubjectMark(TongsIcons.NotPlanned, colors.neutral, "Issue, closed as not planned")
      }
    else -> SubjectMark(TongsIcons.Discussion, null, type)
  }
}
