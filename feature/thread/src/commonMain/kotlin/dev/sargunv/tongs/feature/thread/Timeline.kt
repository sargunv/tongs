package dev.sargunv.tongs.feature.thread

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sargunv.tongs.core.markdown.ForgeMarkdown
import dev.sargunv.tongs.core.markdown.MarkdownContext
import dev.sargunv.tongs.core.ui.Avatar
import dev.sargunv.tongs.core.ui.ConversationIcons
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.core.ui.isDesktop
import dev.sargunv.tongs.core.ui.shortAge
import dev.sargunv.tongs.core.ui.stateColors
import dev.sargunv.tongs.forge.api.Actor
import dev.sargunv.tongs.forge.api.Reactions
import dev.sargunv.tongs.forge.api.ReviewThread
import dev.sargunv.tongs.forge.api.TimelineItem
import kotlin.time.Instant

/** The width of the avatar column the timeline hangs from. */
internal val GUTTER = 40.dp
private val AVATAR = 30.dp

/** Things every timeline row may need: where links point, the time, and the screen's actions. */
internal class TimelineEnv(
  val context: MarkdownContext,
  val now: Instant,
  val actions: ThreadActions,
  val canReact: Boolean,
  val canComment: Boolean,
  val isPullRequest: Boolean,
  /** Opens a URL in the browser, even one the app could open itself. */
  val openInBrowser: (String) -> Unit,
)

internal fun ago(at: Instant, now: Instant): String {
  val age = shortAge(at.toEpochMilliseconds(), now.toEpochMilliseconds())
  return if (age.last().isLetter() && age.first().isDigit()) "$age ago" else age
}

@Composable
internal fun TimelineRowView(row: TimelineRow, env: TimelineEnv) {
  when (row) {
    is TimelineRow.Comment -> {
      val c = row.comment
      Post(
        author = c.author,
        meta = if (c.editedAt != null) "edited" else null,
        at = c.createdAt,
        body = c.body,
        reactions = c.reactions,
        subjectId = c.id.takeUnless { row.entry.pending },
        webUrl = c.webUrl,
        pending = row.entry.pending,
        minimized = c.minimizedReason?.takeIf { c.isMinimized },
        env = env,
      )
    }
    is TimelineRow.Review ->
      if (row.isReplyOnly) {
        EventRow(
          ConversationIcons.Comment,
          row.review.author,
          listOf(Part.Text("replied to review comments")),
          row.review.createdAt,
          env,
        )
      } else ReviewView(row, env)
    is TimelineRow.Commits -> CommitsView(row.commits, env)
    is TimelineRow.Labels -> {
      val parts = buildList {
        if (row.added.isNotEmpty()) {
          add(Part.Text("added"))
          row.added.forEach { add(Part.LabelChip(it)) }
        }
        if (row.removed.isNotEmpty()) {
          add(Part.Text(if (row.added.isEmpty()) "removed" else "and removed"))
          row.removed.forEach { add(Part.LabelChip(it)) }
        }
      }
      EventRow(ConversationIcons.Label, row.actor, parts, row.at, env)
    }
    is TimelineRow.Event -> {
      val line = describe(row.event, env.context.repo, env.isPullRequest)
      val colors = stateColors()
      val tint =
        when (line.tone) {
          Tone.Open -> colors.open
          Tone.Done,
          Tone.Merged -> colors.merged
          Tone.Closed -> colors.closed
          Tone.Skipped -> colors.neutral
          Tone.Quiet -> MaterialTheme.colorScheme.onSurfaceVariant
        }
      EventRow(line.icon, row.event.actor, line.parts, row.event.createdAt, env, tint)
    }
    is TimelineRow.Gap -> Unit // The screen draws the gap; it needs the loading state.
  }
}

/**
 * A comment, a review's body, or the description: avatar, name and time, the Markdown body, and
 * reactions. [subjectId] null means it can't be reacted to or quoted yet, as while sending.
 */
@Composable
internal fun Post(
  author: Actor?,
  meta: String?,
  at: Instant,
  body: String,
  reactions: List<Reactions>,
  subjectId: String?,
  webUrl: String?,
  env: TimelineEnv,
  modifier: Modifier = Modifier,
  pending: Boolean = false,
  minimized: String? = null,
  verb: (@Composable () -> Unit)? = null,
  emptyBody: String? = null,
  footer: (@Composable () -> Unit)? = null,
) {
  var showMinimized by rememberSaveable(subjectId) { mutableStateOf(false) }
  Row(modifier.fillMaxWidth().alpha(if (pending) 0.6f else 1f)) {
    Box(Modifier.width(GUTTER)) {
      Avatar(author?.avatarUrl, author?.login ?: "?", AVATAR)
    }
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          itemVerticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f).padding(top = 4.dp),
        ) {
          AuthorName(author)
          verb?.invoke()
          Text(
            listOfNotNull(if (pending) "Sending…" else ago(at, env.now), meta).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (subjectId != null) PostMenu(author, body, webUrl, env)
      }
      when {
        minimized != null && !showMinimized ->
          TextButton(onClick = { showMinimized = true }, contentPadding = PaddingTight) {
            Text(
              "Hidden as ${minimized.lowercase().replace('_', ' ')}. Show",
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        body.isBlank() && emptyBody != null ->
          Text(
            emptyBody,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
          )
        body.isNotBlank() ->
          ForgeMarkdown(
            body,
            env.context,
            Modifier.padding(top = 2.dp),
            textStyle = postTextStyle(),
          )
      }
      if (subjectId != null && (reactions.isNotEmpty())) {
        ReactionRow(
          reactions,
          env.canReact,
          { env.actions.react(subjectId, it) },
          Modifier.padding(top = 8.dp),
        )
      }
      footer?.invoke()
    }
  }
}

@Composable
internal fun postTextStyle() =
  if (isDesktop) MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp)
  else MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp)

@Composable
private fun AuthorName(author: Actor?) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      author?.login ?: "ghost",
      style = MaterialTheme.typography.titleSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (author?.isBot == true) {
      Text(
        "bot",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
          Modifier.padding(start = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
      )
    }
  }
}

private val PaddingTight = androidx.compose.foundation.layout.PaddingValues(0.dp)

@Composable
private fun PostMenu(author: Actor?, body: String, webUrl: String?, env: TimelineEnv) {
  var open by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
      Icon(
        TongsIcons.MoreVert,
        "More",
        Modifier.size(18.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      if (env.canComment) {
        DropdownMenuItem(
          text = { Text("Quote reply") },
          leadingIcon = { Icon(ConversationIcons.Quote, null) },
          onClick = {
            open = false
            env.actions.quote(body, author?.login)
          },
        )
      }
      if (webUrl != null) {
        DropdownMenuItem(
          text = { Text("Open in browser") },
          leadingIcon = { Icon(dev.sargunv.tongs.core.ui.TongsIcons.OpenInNew, null) },
          onClick = {
            open = false
            env.openInBrowser(webUrl)
          },
        )
      }
    }
  }
}

/** One line of the timeline: who did what, quietly, with an icon in the avatar column. */
@Composable
internal fun EventRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  actor: Actor?,
  parts: List<Part>,
  at: Instant,
  env: TimelineEnv,
  iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
  val colors = MaterialTheme.colorScheme
  val style = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurfaceVariant)
  val uri = LocalUriHandler.current
  Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
    Box(Modifier.width(GUTTER).padding(start = 5.dp, top = 1.dp)) {
      Surface(
        shape = CircleShape,
        color = colors.surfaceContainerHigh,
        modifier = Modifier.size(20.dp),
      ) {
        Icon(icon, null, Modifier.padding(3.dp), tint = iconTint)
      }
    }
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
      itemVerticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.weight(1f),
    ) {
      if (actor != null) {
        Text(
          actor.login,
          style = style.copy(color = colors.onSurface, fontWeight = FontWeight.Medium),
        )
      }
      for (part in parts) {
        when (part) {
          is Part.Text ->
            part.text.split(' ').filter { it.isNotEmpty() }.forEach { Text(it, style = style) }
          is Part.Strong ->
            Text(
              part.text,
              style = style.copy(color = colors.onSurface, fontWeight = FontWeight.Medium),
            )
          is Part.Code ->
            Text(
              part.text,
              style =
                style.copy(
                  fontFamily = FontFamily.Monospace,
                  fontSize = style.fontSize * 0.9f,
                  color = colors.onSurface,
                ),
              modifier =
                Modifier.background(colors.surfaceContainerHigh, RoundedCornerShape(4.dp))
                  .padding(horizontal = 4.dp),
            )
          is Part.LabelChip -> LabelChip(part.label)
          is Part.Link ->
            Text(
              part.text,
              style = style.copy(color = colors.primary),
              modifier =
                Modifier.clickable(enabled = part.url.isNotEmpty()) { uri.openUri(part.url) },
            )
        }
      }
      Text("· ${ago(at, env.now)}", style = style.copy(fontSize = style.fontSize * 0.92f))
    }
  }
}

@Composable
internal fun LabelChip(label: dev.sargunv.tongs.forge.api.Label) {
  val (fill, text) = labelColors(label.color)
  Text(
    label.name,
    style = MaterialTheme.typography.labelMedium,
    color = text,
    maxLines = 1,
    modifier =
      Modifier.clip(CircleShape).background(fill).padding(horizontal = 8.dp, vertical = 1.dp),
  )
}

@Composable
private fun ReviewView(row: TimelineRow.Review, env: TimelineEnv) {
  val review = row.review
  val color = reviewColor(review.state)
  Post(
    author = review.author,
    meta = null,
    at = review.createdAt,
    body = review.body,
    reactions = review.reactions,
    subjectId = review.id.takeIf { review.body.isNotBlank() },
    webUrl = review.webUrl,
    env = env,
    verb = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(reviewIcon(review.state), null, Modifier.size(16.dp), tint = color)
        Text(
          reviewVerb(review.state),
          style = MaterialTheme.typography.bodyMedium,
          color = if (color == MaterialTheme.colorScheme.onSurfaceVariant) color else color,
          modifier = Modifier.padding(start = 4.dp),
        )
      }
    },
    footer =
      if (row.threads.isEmpty()) null
      else {
        {
          Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 8.dp),
          ) {
            for (thread in row.threads.sortedBy { it.comments.firstOrNull()?.createdAt }) {
              ReviewThreadView(thread, env)
            }
          }
        }
      },
  )
}

/** A review comment thread: collapsed to its file and line, expanding to the hunk and comments. */
@Composable
private fun ReviewThreadView(thread: ReviewThread, env: TimelineEnv) {
  var expanded by rememberSaveable(thread.id) { mutableStateOf(false) }
  val colors = MaterialTheme.colorScheme
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = colors.surfaceContainer,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
          Modifier.fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp),
      ) {
        Icon(
          if (expanded) ConversationIcons.ExpandLess else ConversationIcons.ExpandMore,
          null,
          Modifier.size(18.dp),
          tint = colors.onSurfaceVariant,
        )
        val line = thread.line ?: thread.originalLine
        Text(
          thread.path.substringAfterLast('/') + (line?.let { ":$it" } ?: ""),
          style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false),
        )
        Spacer(Modifier.weight(0.001f))
        val tags = buildList {
          if (thread.isResolved) add("Resolved")
          if (thread.isOutdated) add("Outdated")
        }
        for (tag in tags) {
          Text(
            tag,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier =
              Modifier.padding(start = 6.dp)
                .background(colors.surfaceContainerHighest, CircleShape)
                .padding(horizontal = 6.dp, vertical = 1.dp),
          )
        }
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(start = 8.dp),
        ) {
          Icon(
            ConversationIcons.Comment,
            null,
            Modifier.size(14.dp),
            tint = colors.onSurfaceVariant,
          )
          Text(
            thread.totalComments.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 3.dp),
          )
        }
      }
      if (!expanded) {
        // A peek at the first comment, so collapsed threads still read as a conversation.
        thread.comments.firstOrNull()?.let { first ->
          Text(
            plainPeek(first.body),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 36.dp, end = 12.dp, bottom = 10.dp),
          )
        }
      }
      AnimatedVisibility(expanded) {
        Column {
          Text(
            thread.path,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
          )
          thread.diffHunk?.let { DiffHunk(it, Modifier.padding(12.dp)) }
          Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
          ) {
            for (comment in thread.comments) {
              Post(
                author = comment.author,
                meta = null,
                at = comment.createdAt,
                body = comment.body,
                reactions = comment.reactions,
                subjectId = comment.id,
                webUrl = comment.webUrl,
                env = env,
              )
            }
            if (thread.totalComments > thread.comments.size) {
              Text(
                "${thread.totalComments - thread.comments.size} more in the changes",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = GUTTER),
              )
            }
          }
        }
      }
    }
  }
}

/** The first line of a comment with Markdown and HTML dropped, for a one-line peek. */
internal fun plainPeek(markdown: String): String =
  markdown
    .lineSequence()
    .map { line ->
      line
        .replace(Regex("""<[^>]*>"""), "")
        .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
        .replace(Regex("""`|\*\*|__|~~"""), "")
        .trim()
    }
    .firstOrNull { it.isNotEmpty() && !it.startsWith(">") && !it.startsWith("```") }
    .orEmpty()

@Composable
private fun DiffHunk(hunk: String, modifier: Modifier = Modifier) {
  val colors = stateColors()
  val scheme = MaterialTheme.colorScheme
  val style =
    MaterialTheme.typography.bodySmall.copy(
      fontFamily = FontFamily.Monospace,
      fontSize = 12.sp,
      lineHeight = 17.sp,
    )
  Column(
    modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(scheme.surfaceContainerLowest)
      .horizontalScroll(rememberScrollState())
      .padding(vertical = 6.dp),
  ) {
    for (line in hunk.lines().filterNot { it.startsWith("@@") }) {
      val (bg, fg) =
        when {
          line.startsWith("+") -> colors.open.copy(alpha = 0.14f) to scheme.onSurface
          line.startsWith("-") -> colors.closed.copy(alpha = 0.14f) to scheme.onSurface
          else -> Color.Transparent to scheme.onSurfaceVariant
        }
      Text(
        line.ifEmpty { " " },
        style = style,
        color = fg,
        softWrap = false,
        modifier = Modifier.background(bg).padding(horizontal = 10.dp).fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun CommitsView(commits: List<TimelineItem.Commit>, env: TimelineEnv) {
  var showAll by rememberSaveable(commits.first().id) { mutableStateOf(commits.size <= 4) }
  val authors = commits.mapNotNull { it.author?.login ?: it.authorName }.distinct()
  val actor = commits.first().author ?: commits.first().authorName?.let { Actor(it) }
  val colors = MaterialTheme.colorScheme
  Column {
    EventRow(
      TongsIcons.Commit,
      actor.takeIf { authors.size <= 1 },
      listOf(
        Part.Text(
          (if (authors.size > 1) "${authors.size} people " else "") +
            "added " +
            plural(commits.size, "commit"),
        ),
      ),
      commits.last().createdAt,
      env,
    )
    val shown = if (showAll) commits else commits.takeLast(3)
    Column(Modifier.padding(start = GUTTER, top = 4.dp)) {
      if (!showAll) {
        Text(
          "Show ${commits.size - shown.size} earlier",
          style = MaterialTheme.typography.labelMedium,
          color = colors.primary,
          modifier = Modifier.clickable { showAll = true }.padding(vertical = 4.dp),
        )
      }
      for (commit in shown) CommitLine(commit, env)
    }
  }
}

@Composable
private fun CommitLine(commit: TimelineItem.Commit, env: TimelineEnv) {
  val colors = MaterialTheme.colorScheme
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(6.dp))
        .clickable(enabled = commit.webUrl != null) { commit.webUrl?.let(env.openInBrowser) }
        .padding(vertical = 3.dp),
  ) {
    Text(
      commit.headline,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    commit.checks?.let {
      Icon(checkIcon(it), null, Modifier.padding(start = 6.dp).size(14.dp), tint = checkColor(it))
    }
    Text(
      commit.oid.take(7),
      style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
      color = colors.onSurfaceVariant,
      modifier = Modifier.padding(start = 8.dp),
    )
  }
}

/** A divider-free spacer for the timeline's rhythm. */
@Composable
internal fun TimelineDivider() {
  HorizontalDivider(
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    modifier = Modifier.padding(start = GUTTER),
  )
}
