package dev.sargunv.tongs.feature.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.sargunv.tongs.core.data.ReviewCommentEntity
import dev.sargunv.tongs.core.ui.ReviewIcons
import dev.sargunv.tongs.core.ui.shortAge
import kotlin.time.Clock

/** How much of a thread shows inline before "Open thread". */
private const val MAX_BODY_LINES = 8
private const val MAX_COMMENTS = 3

/** Cards sit this far in from the diff's left edge, and as far from the scrollbar. */
private val CardInset = 8.dp
private val CardPadding = 12.dp

/**
 * Card text that lays out on the code's row grid: one line of text per row, so a card's height in
 * rows is exact.
 */
class CardMetrics(val rowPx: Float, val body: TextStyle, val label: TextStyle) {
  /** Rows a card with these bodies takes, given the diff's width. */
  fun rows(item: InlineItem, measurer: TextMeasurer, diffWidthPx: Int, density: Float): Int {
    val textWidth =
      (diffWidthPx - ((CardInset + CardPadding) * 2).value * density).toInt().coerceAtLeast(1)
    fun lines(text: String) =
      measurer
        .measure(
          markdownPreview(text),
          body,
          maxLines = MAX_BODY_LINES,
          constraints = Constraints(maxWidth = textWidth),
        )
        .lineCount
    return when (item) {
      is InlineItem.Thread ->
        if (item.folded) 2
        else {
          val comments = item.thread.comments
          val shown = comments.take(MAX_COMMENTS).sumOf { 1 + lines(it.body) }
          1 + 1 + shown + if (comments.size > MAX_COMMENTS) 1 else 0
        }
      is InlineItem.Draft -> 1 + 1 + lines(item.draft.body)
      is InlineItem.Composer -> 0
    }
  }
}

@Composable
fun rememberCardMetrics(rowPx: Float): CardMetrics {
  val density = LocalDensity.current
  val typography = MaterialTheme.typography
  return remember(rowPx, density, typography) {
    val lineHeight = with(density) { rowPx.toSp() }
    val grid = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    CardMetrics(
      rowPx,
      typography.bodyMedium.copy(lineHeight = lineHeight, lineHeightStyle = grid),
      typography.labelMedium.copy(lineHeight = lineHeight, lineHeightStyle = grid),
    )
  }
}

/** Draws [item] over its rows in the diff. */
@Composable
fun InlineCard(
  item: InlineItem?,
  controller: ChangesController,
  metrics: CardMetrics,
  onOpenThread: (String) -> Unit,
) {
  when (item) {
    null -> Unit
    is InlineItem.Thread -> ThreadCard(item, controller, metrics, onOpenThread)
    is InlineItem.Draft -> DraftCard(item, controller, metrics)
    is InlineItem.Composer -> {
      val composer = LocalCommentComposer.current
      composer.Content(controller.commentRequest(item.target), Modifier.fillMaxSize())
    }
  }
}

/** The composer's request for [target]: send saves it to the pending review. */
fun ChangesController.commentRequest(target: CommentTarget) =
  CommentRequest(
    title = target.title,
    initialBody = target.body,
    suggestion = target.suggestion,
    submitLabel =
      if (target.draftId != 0L) "Update"
      else if (drafts.isEmpty()) "Start review" else "Add to review",
    onSubmit = ::saveComment,
    onCancel = ::cancelComment,
  )

@Composable
private fun CardFrame(
  metrics: CardMetrics,
  color: Color,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val half = with(LocalDensity.current) { (metrics.rowPx / 2).toDp() }
  Box(Modifier.fillMaxSize().padding(horizontal = CardInset, vertical = half / 2)) {
    Surface(
      color = color,
      shape = MaterialTheme.shapes.medium,
      modifier = modifier.fillMaxSize(),
    ) {
      Box(Modifier.padding(horizontal = CardPadding, vertical = half / 2)) { content() }
    }
  }
}

@Composable
private fun ThreadCard(
  item: InlineItem.Thread,
  controller: ChangesController,
  metrics: CardMetrics,
  onOpenThread: (String) -> Unit,
) {
  val thread = item.thread
  val colors = MaterialTheme.colorScheme
  val first = thread.comments.firstOrNull()
  if (item.folded) {
    CardFrame(
      metrics,
      colors.surfaceContainer,
      Modifier.clickable { controller.toggleThread(thread.thread.id) },
    ) {
      Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Icon(ReviewIcons.Check, null, Modifier.size(16.dp), tint = colors.primary)
        Text(
          "Resolved · ${first?.authorLogin ?: "someone"}: " +
            markdownPreview(first?.body.orEmpty()).lineSequence().firstOrNull().orEmpty(),
          style = metrics.label,
          color = colors.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(start = 8.dp),
        )
      }
    }
    return
  }
  CardFrame(
    metrics,
    colors.surfaceContainerHigh,
    Modifier.clickable { onOpenThread(thread.thread.id) },
  ) {
    Column {
      val tags =
        listOfNotNull(
          "Outdated".takeIf { thread.thread.isOutdated },
          "Resolved".takeIf { thread.thread.isResolved },
          "File".takeIf { thread.thread.isFileLevel },
        )
      for (comment in thread.comments.take(MAX_COMMENTS)) {
        CommentHeader(comment, metrics, tags.takeIf { comment === first }.orEmpty())
        Text(
          markdownPreview(comment.body),
          style = metrics.body,
          maxLines = MAX_BODY_LINES,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (thread.comments.size > MAX_COMMENTS) {
        Text(
          "${thread.comments.size - MAX_COMMENTS} more replies",
          style = metrics.label,
          color = colors.primary,
        )
      }
    }
  }
}

@Composable
private fun CommentHeader(comment: ReviewCommentEntity, metrics: CardMetrics, tags: List<String>) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      comment.authorLogin ?: "ghost",
      style = metrics.label.copy(fontWeight = FontWeight.SemiBold),
    )
    Text(
      " · " + shortAge(comment.createdAt, Clock.System.now().toEpochMilliseconds()),
      style = metrics.label,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (tag in tags) {
      Text(
        "  $tag",
        style = metrics.label,
        color = MaterialTheme.colorScheme.tertiary,
      )
    }
  }
}

@Composable
private fun DraftCard(item: InlineItem.Draft, controller: ChangesController, metrics: CardMetrics) {
  val colors = MaterialTheme.colorScheme
  CardFrame(
    metrics,
    colors.secondaryContainer,
    Modifier.clickable { controller.editDraft(item.draft) },
  ) {
    CompositionLocalProvider(LocalContentColor provides colors.onSecondaryContainer) {
      Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("Pending", style = metrics.label.copy(fontWeight = FontWeight.SemiBold))
          Spacer(Modifier.weight(1f))
          Text(
            "Edit",
            style = metrics.label,
            color = colors.primary,
            modifier =
              Modifier.clickable { controller.editDraft(item.draft) }.padding(horizontal = 8.dp),
          )
          Text(
            "Delete",
            style = metrics.label,
            color = colors.error,
            modifier =
              Modifier.clickable { controller.deleteDraft(item.draft) }.padding(start = 8.dp),
          )
        }
        Text(
          markdownPreview(item.draft.body),
          style = metrics.body,
          maxLines = MAX_BODY_LINES,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/** Every comment of a thread, for the sheet or side pane a card opens. */
@Composable
fun ThreadDetail(
  thread: dev.sargunv.tongs.core.data.ReviewThreadWithComments,
  modifier: Modifier = Modifier,
) {
  val t = thread.thread
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
    for (comment in thread.comments) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
          Text(
            comment.authorLogin ?: "ghost",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            " · " + shortAge(comment.createdAt, Clock.System.now().toEpochMilliseconds()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(markdownPreview(comment.body), style = MaterialTheme.typography.bodyMedium)
      }
    }
    Spacer(Modifier.height(4.dp).fillMaxWidth())
  }
}
