package dev.sargunv.tongs.feature.feed

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.sargunv.tongs.core.data.FeedItem
import dev.sargunv.tongs.core.data.FeedPerson
import dev.sargunv.tongs.core.ui.Avatar
import dev.sargunv.tongs.core.ui.ConversationIcons
import dev.sargunv.tongs.core.ui.FeedIcons
import dev.sargunv.tongs.core.ui.ListRow
import dev.sargunv.tongs.core.ui.StateColors
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.core.ui.shortAge
import dev.sargunv.tongs.core.ui.stateColors
import dev.sargunv.tongs.core.ui.subjectMark
import dev.sargunv.tongs.forge.api.FeedEventKind
import dev.sargunv.tongs.forge.api.RefType
import kotlin.time.Instant

/**
 * One feed item, in the inbox's shape: the repo and when, a headline (the issue's or PR's title, or
 * what happened), then who did what with their avatars, and a peek: the latest comment, or a
 * burst's commit titles. The icon says what kind of thing it is, colored by state for issues and
 * PRs, as in the inbox.
 */
@Composable
internal fun FeedRowItem(
  item: FeedItem,
  now: Instant,
  viewer: String?,
  /** The account's login, when the feed spans several. */
  account: String?,
  /** Whether the row sits under its repo's header, so needn't name the repo. */
  groupedByRepo: Boolean,
  selected: Boolean,
  cursor: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  hoverActions: @Composable RowScope.() -> Unit,
) {
  val colors = stateColors()
  val mark = leadingMark(item, colors)
  val number = (item as? FeedItem.Subject)?.subject?.number?.let { "#$it" }
  val repo = repoLabel(item).takeUnless { groupedByRepo && item.repos.size == 1 }
  val overline =
    listOfNotNull(listOfNotNull(repo, number).joinToString(" ").ifEmpty { null }, account)
      .joinToString(" · ")
      .ifEmpty { kindLabel(item) }
  ListRow(
    overline = overline,
    overlineTrailing = { Text(shortAge(item.at, now.toEpochMilliseconds())) },
    headline = headline(item, viewer),
    supportingContent = { Supporting(item, viewer) },
    leading = {
      Icon(
        mark.icon,
        contentDescription = mark.description,
        tint = mark.tint ?: LocalContentColor.current.copy(alpha = 0.72f),
      )
    },
    hoverActions = hoverActions,
    selected = selected,
    cursor = cursor,
    onClick = onClick,
    onLongClick = onLongClick,
  )
}

@Composable
private fun Supporting(item: FeedItem, viewer: String?) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Avatars(item.actors)
      Text(
        byline(item, viewer),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 6.dp),
      )
    }
    when (item) {
      is FeedItem.Push -> Commits(item)
      is FeedItem.Subject ->
        item.excerpt?.let { Peek("${personName(it.actor.login, viewer)}: ${it.text}") }
      is FeedItem.Single -> item.event.excerpt?.let { Peek(it) }
      else -> {}
    }
  }
}

/** Up to three avatars, overlapping, for who's in the item. */
@Composable
private fun Avatars(people: List<FeedPerson>) {
  val shown = people.take(3)
  val size = 18.dp
  val overlap = 6.dp
  Box(
    Modifier.size(
      width = size + (size - overlap) * (shown.size - 1).coerceAtLeast(0),
      height = size,
    ),
  ) {
    for ((i, person) in shown.withIndex()) {
      Avatar(
        person.avatarUrl,
        person.login,
        size,
        modifier =
          Modifier.offset(x = (size - overlap) * i)
            .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
      )
    }
  }
}

/** A burst's commits, newest first: up to three titles, and how many more. */
@Composable
private fun Commits(item: FeedItem.Push) {
  val shown = item.commits.take(3)
  for (commit in shown) {
    Text(
      buildAnnotatedString {
        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(commit.sha.take(7)) }
        append("  ")
        append(commit.title)
      },
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
  val total = item.commitCount ?: item.commits.size
  val more = total - shown.size
  when {
    more > 0 -> Text("and $more more", style = MaterialTheme.typography.labelMedium)
    !item.hydrated && shown.isEmpty() && item.pushes > 0 ->
      Text("Loading commits…", style = MaterialTheme.typography.labelMedium)
  }
}

@Composable
private fun Peek(text: String) {
  Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontStyle = FontStyle.Italic)
}

internal data class LeadingMark(
  val icon: ImageVector,
  val tint: androidx.compose.ui.graphics.Color?,
  val description: String,
)

internal fun leadingMark(item: FeedItem, colors: StateColors): LeadingMark =
  when (item) {
    is FeedItem.Subject -> {
      val s = item.subject
      val mark = subjectMark(s.type, s.state, s.stateReason, s.isDraft, colors)
      LeadingMark(mark.icon, mark.color, mark.description)
    }
    is FeedItem.Push ->
      if (item.pushes == 0) LeadingMark(FeedIcons.Branch, null, "Branch")
      else LeadingMark(TongsIcons.Commit, null, "Push")
    is FeedItem.Starred -> LeadingMark(FeedIcons.Star, colors.pending, "Stars")
    is FeedItem.Forked -> LeadingMark(FeedIcons.Fork, null, "Forks")
    is FeedItem.Refs ->
      when {
        !item.created -> LeadingMark(TongsIcons.Delete, null, "Deleted")
        item.refType == RefType.Tag.name -> LeadingMark(FeedIcons.Tag, null, "Tag")
        else -> LeadingMark(FeedIcons.Branch, null, "Branch")
      }
    is FeedItem.Single ->
      when (item.event.eventKind) {
        FeedEventKind.Release -> LeadingMark(TongsIcons.Release, colors.open, "Release")
        FeedEventKind.Member -> LeadingMark(FeedIcons.Member, null, "Collaborator")
        FeedEventKind.Publicized -> LeadingMark(FeedIcons.Public, null, "Public")
        FeedEventKind.Discussion -> LeadingMark(TongsIcons.Discussion, null, "Discussion")
        FeedEventKind.CommitComment -> LeadingMark(ConversationIcons.Comment, null, "Comment")
        FeedEventKind.Wiki -> LeadingMark(TongsIcons.EditNote, null, "Wiki")
        FeedEventKind.RefCreated -> LeadingMark(TongsIcons.Folder, null, "Repository")
        else -> LeadingMark(TongsIcons.Activity, null, "Activity")
      }
  }

private fun kindLabel(item: FeedItem) = leadingMark(item, PLACEHOLDER_COLORS).description

private val PLACEHOLDER_COLORS =
  androidx.compose.ui.graphics.Color.Unspecified.let { c -> StateColors(c, c, c, c, c, c, c) }
