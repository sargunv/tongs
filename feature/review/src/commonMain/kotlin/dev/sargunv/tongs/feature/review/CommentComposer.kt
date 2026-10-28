package dev.sargunv.tongs.feature.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * What a composer edits: one comment's text, with where it goes for context.
 *
 * @property title what the comment is on, like `Lines 12–14 of src/Map.kt`.
 * @property initialBody the text to start from: an edited draft's, or empty.
 * @property suggestion the selected lines as they are, for "Suggest change" to prefill as a
 *
 *   ```` ```suggestion ```` block; null where a suggestion can't apply, as on removed lines.
 * @property submitLabel the send action's label.
 */
class CommentRequest(
  val title: String,
  val initialBody: String,
  val suggestion: String?,
  val submitLabel: String,
  val onSubmit: (body: String) -> Unit,
  val onCancel: () -> Unit,
)

/**
 * Writes a comment. This is the seam the thread screen's email-style composer can take over: an
 * inline one reserves [inlineRows] rows of the diff under the lines and draws [Content] there; one
 * that returns 0 is shown in the screen's composer slot instead, a sheet when folded and the side
 * pane when wide. Provide another with [LocalCommentComposer].
 */
interface CommentComposer {
  /** Rows of the diff to reserve, or 0 to show in the screen's composer slot. */
  val inlineRows: Int

  @Composable fun Content(request: CommentRequest, modifier: Modifier)
}

val LocalCommentComposer = staticCompositionLocalOf<CommentComposer> { InlineCommentComposer }

/** Wraps [lines] as a suggested change: GitHub, Forgejo, and GitLab all read this fence. */
fun suggestionBlock(lines: String): String = "```suggestion\n$lines\n```\n"

/**
 * A plain inline composer: a text field under the lines, with "Suggest change", Cancel, and the
 * send action. Ctrl or Cmd+Enter sends, Escape cancels.
 */
object InlineCommentComposer : CommentComposer {
  override val inlineRows = 12

  @Composable
  override fun Content(request: CommentRequest, modifier: Modifier) {
    val text = rememberTextFieldState(request.initialBody)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val send = { if (text.text.isNotBlank()) request.onSubmit(text.text.toString()) }
    Surface(
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      shape = MaterialTheme.shapes.large,
      tonalElevation = 2.dp,
      modifier = modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
      Column(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          request.title,
          style = MaterialTheme.typography.labelLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        OutlinedTextField(
          state = text,
          placeholder = { Text("Leave a comment") },
          lineLimits = TextFieldLineLimits.MultiLine(),
          textStyle = MaterialTheme.typography.bodyMedium,
          modifier =
            Modifier.weight(1f).fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
              if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
              when {
                event.key == Key.Enter && (event.isCtrlPressed || event.isMetaPressed) -> {
                  send()
                  true
                }
                event.key == Key.Escape -> {
                  request.onCancel()
                  true
                }
                else -> false
              }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (request.suggestion != null) {
            TextButton(
              onClick = {
                val prefix = text.text.toString().let { if (it.isBlank()) "" else "$it\n" }
                text.setTextAndPlaceCursorAtEnd(prefix + suggestionBlock(request.suggestion))
              },
            ) {
              Text("Suggest change")
            }
          }
          Spacer(Modifier.weight(1f))
          TextButton(onClick = request.onCancel) { Text("Cancel") }
          Button(onClick = send, enabled = text.text.isNotBlank()) {
            Text(request.submitLabel, maxLines = 1)
          }
        }
      }
    }
  }
}
