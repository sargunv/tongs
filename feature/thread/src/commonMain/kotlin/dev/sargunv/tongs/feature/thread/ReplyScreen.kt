package dev.sargunv.tongs.feature.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sargunv.tongs.core.composer.Composer
import dev.sargunv.tongs.core.composer.ComposerAction
import dev.sargunv.tongs.core.composer.ComposerState
import dev.sargunv.tongs.core.composer.rememberComposerState
import dev.sargunv.tongs.core.markdown.MarkdownContext
import dev.sargunv.tongs.core.ui.ConversationIcons
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.forge.api.ThreadDetail
import dev.sargunv.tongs.forge.api.ThreadKind
import dev.sargunv.tongs.forge.api.ThreadState

/** The composer replying to a thread, with Comment and Close or Reopen with comment. */
@Composable
fun ReplyScreen(viewModel: ReplyViewModel, onClose: () -> Unit) {
  val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
  val webUrl by viewModel.webUrl.collectAsStateWithLifecycle()
  val state = rememberComposerState(viewModel.ref.replyDraft, viewModel.drafts)
  ReplyContent(
    state = state,
    detail = snapshot?.detail,
    number = viewModel.ref.number,
    repo = viewModel.ref.repo.toString(),
    webUrl = webUrl,
    onSend = { close, reopen ->
      viewModel.send(close, reopen)
      onClose()
    },
    onClose = onClose,
  )
}

/** [ReplyScreen]'s content, for screenshots. */
@Composable
fun ReplyContent(
  state: ComposerState,
  detail: ThreadDetail?,
  number: Int,
  repo: String,
  webUrl: String,
  onSend: (close: Boolean, reopen: Boolean) -> Unit,
  onClose: () -> Unit,
) {
  val context = remember(webUrl, detail?.repo) { MarkdownContext(webUrl, detail?.repo) }
  val blank = state.isBlank
  val noun = if (detail?.kind == ThreadKind.PullRequest) "pull request" else "issue"
  val actions = buildList {
    if (detail?.state == ThreadState.Open && detail.viewer.canClose) {
      add(
        ComposerAction(
          label = if (blank) "Close $noun" else "Close with comment",
          onClick = { onSend(true, false) },
          icon = ConversationIcons.CheckCircle,
          allowsEmpty = true,
        ),
      )
    }
    if (detail?.state == ThreadState.Closed && detail.viewer.canReopen) {
      add(
        ComposerAction(
          label = if (blank) "Reopen" else "Reopen with comment",
          onClick = { onSend(false, true) },
          icon = TongsIcons.Undo,
          allowsEmpty = true,
        ),
      )
    }
    add(
      ComposerAction(
        label = "Comment",
        onClick = { onSend(false, false) },
        icon = ConversationIcons.Send,
        primary = true,
        enabled = detail?.viewer?.canComment != false,
      ),
    )
  }
  Composer(
    state = state,
    title = detail?.title?.let { "Re: $it" } ?: "Reply to #$number",
    subtitle = "$repo#$number",
    actions = actions,
    onClose = onClose,
    context = context,
  )
}
