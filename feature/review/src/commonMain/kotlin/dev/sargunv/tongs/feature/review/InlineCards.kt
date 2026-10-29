package dev.sargunv.tongs.feature.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sargunv.tongs.core.data.ChangesThreadCommentEntity
import dev.sargunv.tongs.core.data.CommentState
import dev.sargunv.tongs.core.data.DraftEntity
import dev.sargunv.tongs.core.data.ReviewThreadWithComments
import dev.sargunv.tongs.core.markdown.ForgeMarkdown
import dev.sargunv.tongs.core.markdown.MarkdownContext
import dev.sargunv.tongs.core.ui.Avatar
import dev.sargunv.tongs.core.ui.ConversationIcons
import dev.sargunv.tongs.core.ui.ReviewIcons
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.core.ui.shortAge
import kotlin.math.ceil
import kotlin.time.Clock

/** How much of a thread shows inline before "Open thread". */
private const val MAX_COMMENTS = 3
private val MaxBodyHeight = 220.dp

/** Cards sit this far in from the diff's left edge, and as far from the scrollbar. */
private val CardInset = 8.dp
private val CardPadding = 12.dp

/**
 * Estimates how many of the diff's rows a card takes before it's drawn, from its text at the diff's
 * width. Once drawn, a card measures itself and reports the real height (see
 * [ChangesController.measured]), so an estimate only has to be close enough not to jump far.
 */
class CardMetrics(val rowPx: Float, val body: TextStyle, val density: Float) {
  private fun rowsOf(dp: Dp) = ceil(dp.value * density / rowPx).toInt()

  fun rows(item: InlineItem, measurer: TextMeasurer, diffWidthPx: Int): Int {
    val textWidth =
      (diffWidthPx - ((CardInset + CardPadding) * 2).value * density).toInt().coerceAtLeast(1)
    fun bodyPx(text: String) =
      measurer
        .measure(markdownPreview(text), body, constraints = Constraints(maxWidth = textWidth))
        .size
        .height
        .coerceAtMost((MaxBodyHeight.value * density).toInt())
    val chrome = rowsOf(CardInset + CardPadding)
    fun comment(text: String) = ceil((bodyPx(text) + 28 * density) / rowPx).toInt()
    return when (item) {
      is InlineItem.Thread ->
        if (item.folded) rowsOf(44.dp)
        else {
          val shown = shownComments(item.thread.comments).sumOf { comment(it.body) }
          chrome + shown + item.replies.sumOf { comment(it.body) } + rowsOf(40.dp)
        }
      is InlineItem.Draft -> chrome + comment(item.draft.body)
    }
  }
}

@Composable
fun rememberCardMetrics(rowPx: Float): CardMetrics {
  val density = LocalDensity.current
  val typography = MaterialTheme.typography
  return remember(rowPx, density, typography) {
    CardMetrics(rowPx, typography.bodyMedium, density.density)
  }
}

/** The first comment and the last few, which are what a reply answers. */
private fun shownComments(comments: List<ChangesThreadCommentEntity>) =
  if (comments.size <= MAX_COMMENTS) comments
  else listOf(comments.first()) + comments.takeLast(MAX_COMMENTS - 1)

/** Draws [item] over its rows in the diff, and reports its height so the rows fit it. */
@Composable
fun InlineCard(item: InlineItem?, controller: ChangesController, onOpenThread: (String) -> Unit) {
  if (item == null) return
  Box(
    Modifier.fillMaxSize()
      .wrapContentHeight(Alignment.Top, unbounded = true)
      .onSizeChanged { controller.measured(item, it.height) }
      .padding(horizontal = CardInset, vertical = 4.dp),
  ) {
    when (item) {
      is InlineItem.Thread -> ThreadCard(item, controller, onOpenThread)
      is InlineItem.Draft -> DraftCard(item.draft, controller)
    }
  }
}

/** The composer's request for [target]. */
fun ChangesController.commentRequest(target: ComposerTarget, quote: Boolean) =
  CommentRequest(
    draftKey = draftKey(target),
    drafts = draftRepository,
    title = target.title,
    subtitle = "${key.repo}#${key.number}",
    context =
      when (target) {
        is ComposerTarget.Line -> markdownContext.copy(suggestionBase = target.suggestion)
        is ComposerTarget.Reply -> markdownContext
        is ComposerTarget.Text -> markdownContext
      },
    suggestion = (target as? ComposerTarget.Line)?.suggestion,
    quoted = if (quote) (target as? ComposerTarget.Line)?.quoted.orEmpty() else emptyList(),
    submitLabel = sendLabel(target),
    onSubmit = { send(target) },
    onClose = ::closeComposer,
  )

@Composable
private fun Card(color: Color, onClick: (() -> Unit)?, content: @Composable () -> Unit) {
  Surface(
    color = color,
    shape = MaterialTheme.shapes.medium,
    modifier =
      Modifier.fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
  ) {
    Box(Modifier.padding(horizontal = CardPadding, vertical = 8.dp)) { content() }
  }
}

@Composable
private fun ThreadCard(
  item: InlineItem.Thread,
  controller: ChangesController,
  onOpenThread: (String) -> Unit,
) {
  val thread = item.thread
  val t = thread.thread
  val colors = MaterialTheme.colorScheme
  if (item.folded) {
    Card(colors.surfaceContainer, onClick = { controller.toggleThread(t.id) }) {
      ResolvedLine(thread)
    }
    return
  }
  Card(colors.surfaceContainerHigh, onClick = { onOpenThread(t.id) }) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      val comments = thread.comments
      val shown = shownComments(comments)
      for ((i, comment) in shown.withIndex()) {
        if (i == 1 && comments.size > shown.size) {
          Text(
            "${comments.size - shown.size} more replies",
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
          )
        }
        ThreadComment(
          comment,
          controller.markdownContext.copy(suggestionBase = controller.suggestionBase(thread)),
          tags = if (i == 0) threadTags(thread) else emptyList(),
          clip = true,
          onOverflow = { onOpenThread(t.id) },
        )
      }
      for (reply in item.replies) PendingReply(reply, controller)
      ThreadActions(thread, item.replyDrafted, controller)
    }
  }
}

/** A resolved thread, folded to one line: who resolved it and how it starts. */
@Composable
private fun ResolvedLine(thread: ReviewThreadWithComments) {
  val colors = MaterialTheme.colorScheme
  val first = thread.comments.firstOrNull()
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Icon(ReviewIcons.Check, null, Modifier.size(16.dp), tint = colors.primary)
    Text(
      (thread.thread.resolvedBy?.let { "Resolved by $it" } ?: "Resolved") +
        " · ${first?.authorLogin ?: "someone"}: " +
        markdownPreview(first?.body.orEmpty()).lineSequence().firstOrNull().orEmpty(),
      style = MaterialTheme.typography.labelMedium,
      color = colors.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f).padding(start = 8.dp),
    )
    if (thread.comments.size > 1) {
      Text(
        "${thread.comments.size}",
        style = MaterialTheme.typography.labelMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
      )
    }
    Icon(ConversationIcons.ExpandMore, "Show", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
  }
}

private fun threadTags(thread: ReviewThreadWithComments) =
  listOfNotNull(
    "Outdated".takeIf { thread.thread.isOutdated },
    "Resolved".takeIf { thread.thread.isResolved },
    "File".takeIf { thread.thread.isFileLevel },
  )

/** Reply and Resolve, as the reader may. */
@Composable
private fun ThreadActions(
  thread: ReviewThreadWithComments,
  replyDrafted: Boolean,
  controller: ChangesController,
) {
  val t = thread.thread
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    itemVerticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth(),
  ) {
    if (t.viewerCanReply) {
      SmallAction(
        if (replyDrafted) TongsIcons.Edit else ConversationIcons.Reply,
        if (replyDrafted) "Draft reply" else "Reply",
      ) {
        controller.startReply(thread)
      }
    }
    if (t.isResolved && t.viewerCanUnresolve) {
      SmallAction(TongsIcons.Undo, "Unresolve") { controller.setResolved(thread, false) }
    } else if (!t.isResolved && t.viewerCanResolve) {
      SmallAction(ConversationIcons.CheckCircle, "Resolve") { controller.setResolved(thread, true) }
    }
    if (t.isResolved) {
      SmallAction(ConversationIcons.ExpandLess, "Fold") { controller.toggleThread(t.id) }
    }
  }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
  TextButton(onClick = onClick, contentPadding = ButtonDefaults.SmallContentPadding) {
    Icon(icon, null, Modifier.size(16.dp))
    Text(label, Modifier.padding(start = 6.dp), maxLines = 1)
  }
}

/**
 * One comment: who, when, and its body as Markdown. With [clip], a long body stops at a few lines,
 * and [onOverflow] opens the rest.
 */
@Composable
private fun ThreadComment(
  comment: ChangesThreadCommentEntity,
  context: MarkdownContext,
  tags: List<String>,
  clip: Boolean,
  onOverflow: () -> Unit = {},
) {
  val tagged =
    tags +
      listOfNotNull(
        "Pending".takeIf { comment.state == CommentState.Pending },
        "Sending…".takeIf { comment.state == CommentState.Sending },
      )
  Column(
    Modifier.alpha(if (comment.state == CommentState.Sending) 0.6f else 1f),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    CommentHeader(comment.authorLogin, comment.authorAvatarUrl, comment.createdAt, tagged)
    Body(comment.body, context, clip, onOverflow)
  }
}

@Composable
private fun Body(
  markdown: String,
  context: MarkdownContext,
  clip: Boolean,
  onOverflow: () -> Unit,
) {
  if (!clip) {
    ForgeMarkdown(markdown, context, textStyle = MaterialTheme.typography.bodyMedium)
    return
  }
  var overflow by remember(markdown) { mutableStateOf(false) }
  Box(
    Modifier.clipToBounds().layout { measurable, constraints ->
      val max = MaxBodyHeight.roundToPx()
      val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
      val over = placeable.height > max
      if (over != overflow) overflow = over
      layout(placeable.width, placeable.height.coerceAtMost(max)) { placeable.place(0, 0) }
    },
  ) {
    ForgeMarkdown(markdown, context, textStyle = MaterialTheme.typography.bodyMedium)
  }
  if (overflow) {
    Text(
      "Show more",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.clickable(onClick = onOverflow).padding(vertical = 2.dp),
    )
  }
}

@Composable
private fun CommentHeader(login: String?, avatarUrl: String?, createdAt: Long, tags: List<String>) {
  val colors = MaterialTheme.colorScheme
  val label = MaterialTheme.typography.labelMedium
  Column {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Avatar(avatarUrl, login ?: "ghost", 18.dp)
      Text(
        login ?: "ghost",
        style = label.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.weight(1f).padding(start = 6.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        shortAge(createdAt, Clock.System.now().toEpochMilliseconds()),
        style = label,
        color = colors.onSurfaceVariant,
        maxLines = 1,
      )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for (tag in tags) Text(tag, style = label, color = colors.tertiary, maxLines = 1)
    }
  }
}

/** A reply waiting in the pending review, under its thread. */
@Composable
private fun PendingReply(draft: DraftEntity, controller: ChangesController) {
  val colors = MaterialTheme.colorScheme
  Surface(color = colors.secondaryContainer, shape = MaterialTheme.shapes.small) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
      DraftHeader("Pending reply", draft, controller)
      Body(
        draft.body,
        controller.markdownContext,
        clip = true,
        onOverflow = { controller.editDraft(draft) },
      )
    }
  }
}

@Composable
private fun DraftHeader(title: String, draft: DraftEntity, controller: ChangesController) {
  DraftHeader(
    title,
    onEdit = { controller.editDraft(draft) },
    onDelete = { controller.deleteDraft(draft) },
    deleteLabel = "Delete",
  )
}

@Composable
private fun DraftHeader(
  title: String,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  deleteLabel: String,
  editLabel: String = "Edit",
) {
  val colors = MaterialTheme.colorScheme
  val label = MaterialTheme.typography.labelMedium
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(title, style = label.copy(fontWeight = FontWeight.SemiBold))
    Spacer(Modifier.weight(1f))
    Text(
      editLabel,
      style = label,
      color = colors.primary,
      modifier = Modifier.clickable(onClick = onEdit).padding(horizontal = 8.dp, vertical = 4.dp),
    )
    Text(
      deleteLabel,
      style = label,
      color = colors.error,
      modifier =
        Modifier.clickable(onClick = onDelete).padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
    )
  }
}

@Composable
private fun DraftCard(draft: DraftEntity, controller: ChangesController) {
  val colors = MaterialTheme.colorScheme
  Card(colors.secondaryContainer, onClick = { controller.editDraft(draft) }) {
    CompositionLocalProvider(LocalContentColor provides colors.onSecondaryContainer) {
      Column {
        DraftHeader(controller.draftLabel(draft), draft, controller)
        val a = checkNotNull(draft.anchor)
        Body(
          draft.body,
          controller.markdownContext.copy(
            suggestionBase =
              if (controller.anchorIsCurrent(draft))
                controller.lines(a.path, a.side, a.startLine, a.endLine)
              else null,
          ),
          clip = true,
          onOverflow = { controller.editDraft(draft) },
        )
      }
    }
  }
}

/**
 * Every comment of a thread, with Reply and Resolve: for the side pane, the pane beside the diff
 * when unfolded, or a sheet when folded.
 */
@Composable
fun ThreadDetail(
  thread: ReviewThreadWithComments,
  controller: ChangesController,
  modifier: Modifier = Modifier,
) {
  val t = thread.thread
  val context = controller.markdownContext.copy(suggestionBase = controller.suggestionBase(thread))
  Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    val where =
      when {
        t.isFileLevel -> t.path
        t.line != null -> "${t.path}:${t.startLine?.let { "$it–" }.orEmpty()}${t.line}"
        else -> "${t.path} (outdated)"
      }
    Text(
      where,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for ((i, comment) in thread.comments.withIndex()) {
      ThreadComment(comment, context, if (i == 0) threadTags(thread) else emptyList(), clip = false)
    }
    for (reply in controller.drafts.filter { it.inReplyTo == t.id }) PendingReply(reply, controller)
    ThreadActions(thread, controller.replyDrafted(t.id), controller)
  }
}
