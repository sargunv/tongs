package dev.sargunv.tongs.feature.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.sargunv.tongs.core.data.InboxRow
import dev.sargunv.tongs.core.ui.ListRow
import dev.sargunv.tongs.core.ui.StateColors
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.core.ui.shortAge
import dev.sargunv.tongs.core.ui.stateColors
import dev.sargunv.tongs.forge.api.SubjectType
import kotlin.time.Instant

/**
 * One notification: the repo and when, the title, then GitHub's reason and what's new since you
 * last read it. The icon's color is the subject's state, and an open PR's checks and review
 * decision follow the reason line as small marks. A thread read or done elsewhere says so at the
 * end of the reason line.
 */
@Composable
internal fun InboxRowItem(
  row: InboxRow,
  now: Instant,
  showAccount: Boolean,
  /** Whether the row sits under its repo's header, so needn't name the repo. */
  grouped: Boolean,
  selected: Boolean,
  cursor: Boolean,
  onClick: () -> Unit,
  hoverActions: @Composable RowScope.() -> Unit,
) {
  val colors = stateColors()
  val state = subjectStateMark(row, colors)
  val number = row.subjectNumber?.let { "#$it" }
  val account = if (showAccount) row.accountLogin else null
  val repo = "${row.repoOwner}/${row.repoName}".takeUnless { grouped }
  val overline =
    listOfNotNull(listOfNotNull(repo, number).joinToString(" "), account)
      .filter { it.isNotEmpty() }
      .joinToString(" · ")
      .ifEmpty { subjectLabel(row.subjectType) }
  val snoozed = row.snoozedUntil?.takeIf { it > now.toEpochMilliseconds() }
  ListRow(
    overline = overline,
    overlineTrailing = {
      if (snoozed != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(TongsIcons.Snooze, null, Modifier.size(14.dp))
          Text(" ${snoozeTime(Instant.fromEpochMilliseconds(snoozed), now)}")
        }
      } else {
        Text(shortAge(row.sortAt, now.toEpochMilliseconds()))
      }
    },
    headline = row.title,
    supportingContent = { SupportingLine(row, colors) },
    leading = {
      Icon(
        state.icon,
        contentDescription = state.description,
        tint = state.color ?: MaterialTheme.colorScheme.onSurfaceVariant,
      )
    },
    hoverActions = hoverActions,
    emphasized = row.isUnread,
    selected = selected,
    cursor = cursor,
    onClick = onClick,
  )
}

/** The mark on rows that GitHub stopped listing as unread without Tongs reading them. */
internal const val READ_ELSEWHERE = "Read elsewhere"

@Composable
private fun SupportingLine(row: InboxRow, colors: StateColors) {
  val content = LocalContentColor.current
  val actionable = isActionableReason(row.reason)
  val reasonColor = if (actionable) MaterialTheme.colorScheme.primary else content
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = reasonColor)) {
          append(reasonLabel(row.reason))
        }
        whatsNewAfterReason(row)?.let {
          append(" · ")
          append(it)
        }
        // Quiet, in the line's own color: GitHub can't say whether it was read or done there, so
        // the row stays until it's done here. Last, so it wraps rather than crowding the repo.
        if (row.readElsewhere) {
          append(" · ")
          append(READ_ELSEWHERE)
        }
      },
      // What's new is the point of the line; give it a second line before cutting it off.
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false),
    )
    if (row.subjectState == "OPEN") {
      Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 6.dp),
      ) {
        checksMark(row.checks, colors)?.let { (icon, tint, label) -> Mark(icon, tint, label) }
        reviewMark(row.reviewDecision, colors)?.let { (icon, tint, label) ->
          Mark(icon, tint, label)
        }
      }
    }
  }
  row.draft?.let { draft ->
    Text(
      buildAnnotatedString {
        withStyle(
          SpanStyle(color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium),
        ) {
          append("Draft: ")
        }
        append(draft)
      },
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun Mark(icon: ImageVector, tint: Color, label: String) {
  Icon(
    icon,
    contentDescription = null,
    tint = tint,
    modifier = Modifier.size(16.dp).semantics { contentDescription = label },
  )
}

/**
 * What's new, less what the reason already says: a review request's row needn't repeat "Review
 * requested from you" after its reason.
 */
internal fun whatsNewAfterReason(row: InboxRow): String? {
  val whatsNew = row.whatsNew ?: return null
  val repeat = "Review requested from you"
  if (row.reason != "review_requested" || !whatsNew.startsWith(repeat)) return whatsNew
  return whatsNew
    .removePrefix(repeat)
    .removePrefix(", ")
    .replaceFirstChar { it.uppercase() }
    .ifEmpty { null }
}

/** The subject's icon, colored by its state, and its state in words. */
internal data class StateMark(val icon: ImageVector, val color: Color?, val description: String)

internal fun subjectStateMark(row: InboxRow, colors: StateColors): StateMark {
  val type = SubjectType.of(row.subjectType)
  val label = subjectLabel(row.subjectType)
  val state = row.subjectState ?: return StateMark(subjectIcon(row.subjectType), null, label)
  return when (type) {
    SubjectType.PullRequest ->
      when {
        state == "MERGED" -> StateMark(TongsIcons.PullRequest, colors.merged, "$label, merged")
        state == "CLOSED" -> StateMark(TongsIcons.PullRequest, colors.closed, "$label, closed")
        row.isDraft -> StateMark(TongsIcons.PullRequest, colors.neutral, "$label, draft")
        else -> StateMark(TongsIcons.PullRequest, colors.open, "$label, open")
      }
    SubjectType.Issue ->
      when {
        state != "CLOSED" -> StateMark(TongsIcons.Issue, colors.open, "$label, open")
        row.stateReason == "COMPLETED" ->
          StateMark(TongsIcons.CheckCircle, colors.merged, "$label, closed as completed")
        else -> StateMark(TongsIcons.NotPlanned, colors.neutral, "$label, closed as not planned")
      }
    else -> StateMark(subjectIcon(row.subjectType), null, label)
  }
}

internal fun checksMark(checks: String?, colors: StateColors): Triple<ImageVector, Color, String>? =
  when (checks) {
    "SUCCESS" -> Triple(TongsIcons.Check, colors.success, "Checks passed")
    "FAILURE",
    "ERROR" -> Triple(TongsIcons.Close, colors.failure, "Checks failed")
    "PENDING",
    "EXPECTED" -> Triple(TongsIcons.Pending, colors.pending, "Checks running")
    else -> null
  }

internal fun reviewMark(
  decision: String?,
  colors: StateColors,
): Triple<ImageVector, Color, String>? =
  when (decision) {
    "APPROVED" -> Triple(TongsIcons.RateReview, colors.success, "Approved")
    "CHANGES_REQUESTED" -> Triple(TongsIcons.RateReview, colors.failure, "Changes requested")
    else -> null
  }
