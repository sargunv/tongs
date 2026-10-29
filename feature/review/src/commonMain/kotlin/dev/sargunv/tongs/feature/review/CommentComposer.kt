package dev.sargunv.tongs.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sargunv.tongs.core.composer.Composer
import dev.sargunv.tongs.core.composer.ComposerAction
import dev.sargunv.tongs.core.composer.rememberComposerState
import dev.sargunv.tongs.core.data.DraftKey
import dev.sargunv.tongs.core.data.DraftRepository
import dev.sargunv.tongs.core.markdown.MarkdownContext
import dev.sargunv.tongs.core.ui.ConversationIcons
import dev.sargunv.tongs.engine.RowKind

/**
 * What a composer edits: one comment, with where it goes for context.
 *
 * @property draftKey the one row edited here, including when it is already part of a review.
 * @property title what the comment is on, like `Lines 12–14 of Map.kt`.
 * @property suggestion the selected lines as they are, for "Suggest change" to prefill as a
 *
 *   ```` ```suggestion ```` block; null where a suggestion can't apply, as on removed lines.
 *
 * @property context resolves references in the preview, and holds the lines a suggestion changes.
 * @property quoted the lines, for a composer that covers the diff; empty where the diff stays in
 *   sight.
 * @property onSubmit changes membership or submits the same persisted draft.
 */
class CommentRequest(
  val draftKey: DraftKey,
  val drafts: DraftRepository,
  val title: String,
  val subtitle: String?,
  val context: MarkdownContext,
  val suggestion: String?,
  val quoted: List<QuotedLine>,
  val submitLabel: String,
  val onSubmit: () -> Unit,
  val onClose: () -> Unit,
)

/**
 * Writes a comment. The changes screen decides where it shows (a full-screen sheet when folded, a
 * pane beside the diff otherwise) and this fills it. Provide another with [LocalCommentComposer].
 */
interface CommentComposer {
  @Composable fun Content(request: CommentRequest, modifier: Modifier)
}

val LocalCommentComposer = staticCompositionLocalOf<CommentComposer> { MarkdownCommentComposer }

/** Wraps [lines] as a suggested change: GitHub, Forgejo, and GitLab all read this fence. */
fun suggestionBlock(lines: String): String = "```suggestion\n$lines\n```\n"

/**
 * The app's Markdown [Composer], as the thread screen's: toolbar, preview, and autosaved drafts,
 * with "Suggest change" beside the send action.
 */
object MarkdownCommentComposer : CommentComposer {
  @Composable
  override fun Content(request: CommentRequest, modifier: Modifier) {
    val state = rememberComposerState(request.draftKey, request.drafts)
    val actions = buildList {
      request.suggestion?.let { lines ->
        add(
          ComposerAction(
            "Suggest change",
            onClick = {
              val text = state.text.text.toString()
              val prefix = if (text.isBlank()) "" else "${text.trimEnd()}\n\n"
              state.replaceText(prefix + suggestionBlock(lines))
            },
            icon = ConversationIcons.Code,
            allowsEmpty = true,
          ),
        )
      }
      add(
        ComposerAction(
          request.submitLabel,
          onClick = {
            request.onSubmit()
          },
          icon = ConversationIcons.Send,
          primary = true,
        ),
      )
    }
    Composer(
      state = state,
      title = request.title,
      subtitle = request.subtitle,
      actions = actions,
      onClose = request.onClose,
      context = request.context,
      modifier = modifier,
      subject = request.quoted.takeIf { it.isNotEmpty() }?.let { { QuotedLines(it) } },
    )
  }
}

/** The lines a comment is on, as the diff shows them, for a composer that covers it. */
@Composable
private fun QuotedLines(lines: List<QuotedLine>) {
  val colors = MaterialTheme.colorScheme
  val dark = colors.surface.luminance() < 0.5f
  val added = if (dark) Color(0x332EA043) else Color(0xFFE6FFEC)
  val removed = if (dark) Color(0x33F85149) else Color(0xFFFFEBE9)
  val digits = remember(lines) { lines.maxOf { it.line }.toString().length.coerceAtLeast(3) }
  val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
  Column(
    Modifier.fillMaxWidth()
      .horizontalScroll(rememberScrollState())
      .width(IntrinsicSize.Max)
      .widthIn(min = 360.dp)
      .padding(vertical = 6.dp),
  ) {
    for (line in lines) {
      val (mark, background) =
        when (line.kind) {
          RowKind.Added -> "+" to added
          RowKind.Removed -> "−" to removed
          else -> " " to Color.Transparent
        }
      Row(Modifier.fillMaxWidth().background(background).padding(horizontal = 12.dp)) {
        Text(
          line.line.toString().padStart(digits),
          style = style,
          color = colors.onSurfaceVariant,
          maxLines = 1,
        )
        Text(
          mark,
          style = style,
          color = colors.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 6.dp),
        )
        Text(line.text, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
      }
    }
  }
}
