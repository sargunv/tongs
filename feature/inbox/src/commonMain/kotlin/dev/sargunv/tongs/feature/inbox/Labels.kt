package dev.sargunv.tongs.feature.inbox

import androidx.compose.ui.graphics.vector.ImageVector
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.forge.api.NotificationReason
import dev.sargunv.tongs.forge.api.SubjectType
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** GitHub's reason, as its web inbox words it. */
fun reasonLabel(raw: String): String =
  when (NotificationReason.of(raw)) {
    NotificationReason.ApprovalRequested -> "Approval requested"
    NotificationReason.Assign -> "Assigned"
    NotificationReason.Author -> "Author"
    NotificationReason.CiActivity -> "CI activity"
    NotificationReason.Comment -> "Commented"
    NotificationReason.Invitation -> "Invited"
    NotificationReason.Manual -> "Subscribed"
    NotificationReason.MemberFeatureRequested -> "Feature requested"
    NotificationReason.Mention -> "Mentioned"
    NotificationReason.ReviewRequested -> "Review requested"
    NotificationReason.SecurityAdvisoryCredit -> "Advisory credit"
    NotificationReason.SecurityAlert -> "Security alert"
    NotificationReason.StateChange -> "State changed"
    NotificationReason.Subscribed -> "Watching"
    NotificationReason.TeamMention -> "Team mentioned"
    is NotificationReason.Other -> raw.replace('_', ' ').replaceFirstChar { it.uppercase() }
  }

fun subjectIcon(raw: String): ImageVector =
  when (SubjectType.of(raw)) {
    SubjectType.PullRequest -> TongsIcons.PullRequest
    SubjectType.Issue -> TongsIcons.Issue
    SubjectType.Discussion -> TongsIcons.Discussion
    SubjectType.Release -> TongsIcons.Release
    SubjectType.Commit -> TongsIcons.Commit
    SubjectType.CheckSuite -> TongsIcons.CheckSuite
    is SubjectType.Other -> TongsIcons.Notifications
  }

data class SnoozeOption(val label: String, val until: Instant)

/** Later today (if there's enough of it left), tomorrow morning, and next Monday morning. */
fun snoozeOptions(
  now: Instant,
  zone: TimeZone = TimeZone.currentSystemDefault(),
): List<SnoozeOption> {
  val local = now.toLocalDateTime(zone)
  val morning = LocalTime(8, 0)
  val tomorrow = local.date.plus(DatePeriod(days = 1))
  val daysToMonday = (DayOfWeek.MONDAY.ordinal - local.date.dayOfWeek.ordinal + 7) % 7
  val monday = local.date.plus(DatePeriod(days = if (daysToMonday == 0) 7 else daysToMonday))
  return buildList {
    if (local.hour < 18) add(SnoozeOption("Later today", now + 3.hours))
    add(SnoozeOption("Tomorrow", tomorrow.atTime(morning).toInstant(zone)))
    add(SnoozeOption("Next week", monday.atTime(morning).toInstant(zone)))
  }
}
