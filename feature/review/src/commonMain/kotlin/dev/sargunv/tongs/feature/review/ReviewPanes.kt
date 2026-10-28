package dev.sargunv.tongs.feature.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sargunv.tongs.core.ui.ReviewIcons
import dev.sargunv.tongs.forge.api.ReviewEvent
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

/**
 * Finishing the pending review: a summary, the verdict, and submit. The summary saves as it's
 * typed, like the drafts. Approve and Request changes aren't offered on your own PR, which forges
 * forbid.
 */
@Composable
fun ReviewForm(
  controller: ChangesController,
  initialEvent: ReviewEvent,
  onSubmitted: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val summary = rememberTextFieldState(controller.summary)
  LaunchedEffect(summary) {
    snapshotFlow { summary.text.toString() }.drop(1).debounce(300).collect(controller::saveSummary)
  }
  val own = controller.overview?.viewerDidAuthor == true
  var event by remember { mutableStateOf(if (own) ReviewEvent.Comment else initialEvent) }
  var sending by remember { mutableStateOf(false) }
  val pending = controller.drafts.size
  val body = summary.text.toString()
  // Forges want words for a comment or a change request that has no line comments.
  val canSend =
    !sending &&
      when (event) {
        ReviewEvent.Approve -> true
        ReviewEvent.Comment,
        ReviewEvent.RequestChanges -> pending > 0 || body.isNotBlank()
      }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      when (pending) {
        0 -> "No pending comments"
        1 -> "1 pending comment"
        else -> "$pending pending comments"
      },
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
      state = summary,
      placeholder = { Text("Leave a summary (optional)") },
      lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 8),
      textStyle = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.fillMaxWidth(),
    )
    Column(Modifier.selectableGroup()) {
      for (option in listOf(ReviewEvent.Comment, ReviewEvent.Approve, ReviewEvent.RequestChanges)) {
        val enabled = option == ReviewEvent.Comment || !own
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier =
            Modifier.fillMaxWidth()
              .clip(MaterialTheme.shapes.small)
              .selectable(event == option, enabled = enabled, role = Role.RadioButton) {
                event = option
              }
              .padding(vertical = 4.dp),
        ) {
          RadioButton(selected = event == option, onClick = null, enabled = enabled)
          Column(Modifier.padding(start = 12.dp)) {
            val (label, detail) =
              when (option) {
                ReviewEvent.Comment -> "Comment" to "General feedback, without a verdict"
                ReviewEvent.Approve -> "Approve" to "Ready to merge"
                ReviewEvent.RequestChanges ->
                  "Request changes" to "Must be addressed before merging"
              }
            val alpha = if (enabled) 1f else 0.38f
            Text(
              label,
              style = MaterialTheme.typography.bodyLarge,
              modifier = Modifier.alpha(alpha),
            )
            Text(
              detail,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.alpha(alpha),
            )
          }
        }
      }
    }
    if (own) {
      Text(
        "This is your PR, so you can only comment.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (sending)
        CircularProgressIndicator(Modifier.size(20.dp).padding(end = 8.dp), strokeWidth = 2.dp)
      Button(
        onClick = {
          sending = true
          controller.submit(event, body) { ok ->
            sending = false
            if (ok) {
              summary.edit { replace(0, length, "") }
              onSubmitted()
            }
          }
        },
        enabled = canSend,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Submit review")
      }
    }
  }
}

/** The review form in a dialog, for windows without a side pane. */
@Composable
fun FinishReviewDialog(
  controller: ChangesController,
  initialEvent: ReviewEvent,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Finish your review") },
    text = { ReviewForm(controller, initialEvent, onSubmitted = onDismiss) },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Keep reading") } },
  )
}

/**
 * The desktop's third pane: your pending review, with its comments and the form to finish it, then
 * the PR's existing threads. Picking either jumps the diff to it.
 */
@Composable
fun ReviewSidePane(
  controller: ChangesController,
  selectedThread: String?,
  onSelectThread: (String?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val composer = controller.composer
  LazyColumn(modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
    if (composer != null && controller.composerRows == 0) {
      item(key = "composer") {
        LocalCommentComposer.current.Content(
          controller.commentRequest(composer),
          Modifier.fillMaxWidth(),
        )
      }
    }
    item(key = "review") {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PaneHeading("Your review")
        for (draft in controller.drafts) {
          val item =
            controller.items.values.firstOrNull {
              it is InlineItem.Draft && it.draft.id == draft.id
            }
          ListCard(
            title = "${draft.anchor.path.substringAfterLast('/')}:${draft.anchor.endLine}",
            body = draft.body,
            onClick = { item?.let(controller::jumpTo) },
          )
        }
        ReviewForm(controller, ReviewEvent.Comment, onSubmitted = {})
      }
    }
    item(key = "threads-heading") {
      Column {
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        PaneHeading(
          when (val n = controller.threads.size) {
            0 -> "No comment threads"
            1 -> "1 thread"
            else -> "$n threads"
          },
        )
      }
    }
    items(controller.threads, key = { it.thread.id }) { thread ->
      val t = thread.thread
      val first = thread.comments.firstOrNull()
      if (t.id == selectedThread) {
        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
          shape = MaterialTheme.shapes.medium,
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable { onSelectThread(null) },
        ) {
          ThreadDetail(thread, Modifier.padding(12.dp))
        }
      } else {
        ListCard(
          title =
            "${first?.authorLogin ?: "ghost"} on ${t.path.substringAfterLast('/')}" +
              (t.line?.let { ":$it" } ?: "") +
              listOfNotNull(
                  " · resolved".takeIf { t.isResolved },
                  " · outdated".takeIf { t.isOutdated },
                )
                .joinToString(""),
          body = first?.body.orEmpty(),
          dim = t.isResolved || t.isOutdated,
          onClick = {
            onSelectThread(t.id)
            controller.items.values
              .firstOrNull { it is InlineItem.Thread && it.thread.thread.id == t.id }
              ?.let(controller::jumpTo)
          },
        )
      }
    }
  }
}

@Composable
private fun PaneHeading(text: String) {
  Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ListCard(title: String, body: String, dim: Boolean = false, onClick: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  Surface(
    color = colors.surfaceContainer,
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = onClick),
  ) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(ReviewIcons.Comment, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
        Text(
          title,
          style = MaterialTheme.typography.labelMedium,
          color = colors.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(start = 6.dp),
        )
      }
      Text(
        markdownPreview(body),
        style = MaterialTheme.typography.bodyMedium,
        color = if (dim) colors.onSurfaceVariant else colors.onSurface,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}
