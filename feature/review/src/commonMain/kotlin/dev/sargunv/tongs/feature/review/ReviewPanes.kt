package dev.sargunv.tongs.feature.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sargunv.tongs.core.composer.rememberComposerState
import dev.sargunv.tongs.core.data.DraftDestination
import dev.sargunv.tongs.core.data.DraftKey
import dev.sargunv.tongs.core.data.ReviewThreadWithComments
import dev.sargunv.tongs.core.ui.ActionIcon
import dev.sargunv.tongs.core.ui.ReviewIcons
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.forge.api.ReviewEvent

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
  val state = rememberComposerState(DraftKey.summary(controller.key), controller.draftRepository)
  val summary = state.text
  val own = controller.overview?.viewerDidAuthor == true
  var event by remember { mutableStateOf(if (own) ReviewEvent.Comment else initialEvent) }
  val sending = state.submitting
  val pending = controller.pendingCount
  val elsewhere = controller.overview?.pendingReviewComments ?: 0
  val body = summary.text.toString()
  // Forges want words for a comment or a change request that has no line comments.
  val canSend =
    state.editable &&
      controller.pendingReviewContinued &&
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
      } + if (elsewhere > 0) ", $elsewhere from a review started elsewhere" else "",
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.warning) Text("May have been sent. Check the review before sending again.")
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    OutlinedTextField(
      state = summary,
      enabled = state.editable,
      inputTransformation = state.inputTransformation,
      placeholder = { Text("Leave a summary (optional)") },
      lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 8),
      textStyle = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.fillMaxWidth().testTag("review-summary"),
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
          controller.submit(event) { ok ->
            // The stored summary is gone now, and the field follows it.
            if (ok) onSubmitted()
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

/** Draft recovery is independent of the current diff, at every window width. */
@Composable
private fun DraftList(controller: ChangesController, onEdit: () -> Unit = {}) {
  for (draft in controller.allDrafts.filter { it.body.isNotEmpty() || it.inReview }) {
    ListCard(
      title = controller.draftLabel(draft),
      body = draft.body,
      onClick = {
        controller.editDraft(draft)
        onEdit()
      },
    )
    Row {
      TextButton(
        onClick = {
          controller.editDraft(draft)
          onEdit()
        },
        enabled = !draft.submitting,
      ) {
        Text("Edit")
      }
      if (draft.inReview && draft.destination != DraftDestination.Summary)
        TextButton(onClick = { controller.removeFromReview(draft) }, enabled = !draft.submitting) {
          Text("Remove from review")
        }
      TextButton(onClick = { controller.deleteDraft(draft) }, enabled = !draft.submitting) {
        Text("Discard")
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
    text = {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        DraftList(controller, onEdit = onDismiss)
        ReviewForm(controller, initialEvent, onSubmitted = onDismiss)
      }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Keep reading") } },
  )
}

/**
 * The desktop's third pane: your pending review, with its comments and the form to finish it, then
 * the PR's existing threads. Picking either jumps the diff to it. While a comment is being written,
 * the composer takes the pane.
 */
@Composable
fun ReviewSidePane(
  controller: ChangesController,
  selectedThread: String?,
  onSelectThread: (String?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val composer = controller.composer
  if (composer != null) {
    key(composer) {
      LocalCommentComposer.current.Content(
        controller.commentRequest(composer, quote = false),
        modifier,
      )
    }
    return
  }
  LazyColumn(modifier, contentPadding = PaddingValues(16.dp)) {
    item(key = "review") {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PaneHeading("Your review")
        DraftList(controller)
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
          ThreadDetail(thread, controller, Modifier.padding(12.dp))
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
          // Resolved threads stay folded to their title until picked.
          maxLines = if (t.isResolved) 0 else 3,
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

/**
 * A thread on its own, with a header to close it: the pane beside the diff on an unfolded phone.
 */
@Composable
fun ThreadPane(
  thread: ReviewThreadWithComments,
  controller: ChangesController,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
    ) {
      ActionIcon(TongsIcons.Close, "Close", onClose)
      Text(
        "Thread",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 4.dp),
      )
    }
    ThreadDetail(
      thread,
      controller,
      Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
    )
  }
}

/**
 * A review left pending on the forge by another client: GitHub allows one per PR, so it has to be
 * continued here or discarded before this one can go out.
 */
@Composable
fun PendingReviewBanner(controller: ChangesController, modifier: Modifier = Modifier) {
  val overview = controller.overview ?: return
  if (overview.pendingReviewId == null || controller.pendingReviewContinued) return
  var confirming by remember { mutableStateOf(false) }
  val n = overview.pendingReviewComments
  Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = modifier.fillMaxWidth()) {
    FlowRow(
      verticalArrangement = Arrangement.Center,
      itemVerticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
      Text(
        "You have a pending review from another client" +
          (if (n == 1) ", with 1 comment" else if (n > 1) ", with $n comments" else ""),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
      )
      Row {
        TextButton(onClick = { confirming = true }) { Text("Discard") }
        TextButton(onClick = controller::continuePendingReview) { Text("Continue") }
      }
    }
  }
  if (confirming) {
    AlertDialog(
      onDismissRequest = { confirming = false },
      title = { Text("Discard your pending review?") },
      text = {
        Text(
          "It was started in another client. Its summary and " +
            (if (n == 1) "comment" else "$n comments") +
            " are deleted on the forge too.",
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            confirming = false
            controller.discardPendingReview()
          },
        ) {
          Text("Discard")
        }
      },
      dismissButton = { TextButton(onClick = { confirming = false }) { Text("Keep it") } },
    )
  }
}

@Composable
private fun PaneHeading(text: String) {
  Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ListCard(
  title: String,
  body: String,
  dim: Boolean = false,
  maxLines: Int = 3,
  onClick: () -> Unit,
) {
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
      if (maxLines > 0) {
        Text(
          markdownPreview(body),
          style = MaterialTheme.typography.bodyMedium,
          color = if (dim) colors.onSurfaceVariant else colors.onSurface,
          maxLines = maxLines,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
    }
  }
}
