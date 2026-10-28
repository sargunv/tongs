package dev.sargunv.tongs.feature.inbox

import androidx.compose.ui.graphics.vector.ImageVector
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.forge.api.NotificationReason
import dev.sargunv.tongs.forge.api.SubjectType
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
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

/** Reasons that ask something of you, which the row sets apart. */
fun isActionableReason(raw: String): Boolean =
  when (NotificationReason.of(raw)) {
    NotificationReason.ReviewRequested,
    NotificationReason.ApprovalRequested,
    NotificationReason.Mention,
    NotificationReason.TeamMention,
    NotificationReason.Assign,
    NotificationReason.SecurityAlert -> true
    else -> false
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

/** A subject type for a screen reader: "Pull request", "Issue". */
fun subjectLabel(raw: String): String =
  when (SubjectType.of(raw)) {
    SubjectType.PullRequest -> "Pull request"
    SubjectType.CheckSuite -> "Check suite"
    is SubjectType.Other -> raw
    else -> raw
  }

data class SnoozeOption(val label: String, val until: Instant, val time: String)

/**
 * Snooze presets, each with the time it ends: later today (if there's enough of it left), this
 * evening, tomorrow morning, this weekend (on weekdays before Friday), and next Monday morning.
 */
fun snoozeOptions(
  now: Instant,
  zone: TimeZone = TimeZone.currentSystemDefault(),
): List<SnoozeOption> {
  val local = now.toLocalDateTime(zone)
  val today = local.date
  val tomorrow = today.plus(DatePeriod(days = 1))
  val dow = today.dayOfWeek.ordinal // Monday is 0
  val daysToSaturday = (DayOfWeek.SATURDAY.ordinal - dow + 7) % 7
  val daysToMonday = (DayOfWeek.MONDAY.ordinal - dow + 7) % 7
  val monday = today.plus(DatePeriod(days = if (daysToMonday == 0) 7 else daysToMonday))
  fun at(date: kotlinx.datetime.LocalDate, hour: Int) =
    date.atTime(LocalTime(hour, 0)).toInstant(zone)
  // Presets name the day, so their times needn't: "Tomorrow" shows "Thu 8:00 AM".
  fun option(label: String, until: Instant): SnoozeOption {
    val at = until.toLocalDateTime(zone)
    val time =
      if (at.date == today) clockTime(at) else "${DAYS[at.dayOfWeek.ordinal]} ${clockTime(at)}"
    return SnoozeOption(label, until, time)
  }
  return buildList {
    // Three hours on, at the top of the hour, while that's still before the evening.
    if (local.hour < 14) add(option("Later today", at(today, local.hour + 3)))
    if (local.hour < 17) add(option("This evening", at(today, 18)))
    add(option("Tomorrow", at(tomorrow, 8)))
    if (daysToSaturday in 2..5)
      add(option("This weekend", at(today.plus(DatePeriod(days = daysToSaturday)), 9)))
    add(option("Next week", at(monday, 8)))
  }
}

private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MONTHS =
  listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "3:00 PM" today, "Tomorrow 8:00 AM", "Sat 9:00 AM" this week, then "Nov 12, 8:00 AM". */
fun snoozeTime(
  until: Instant,
  now: Instant,
  zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
  val at = until.toLocalDateTime(zone)
  val today = now.toLocalDateTime(zone).date
  val time = clockTime(at)
  return when {
    at.date == today -> time
    at.date == today.plus(DatePeriod(days = 1)) -> "Tomorrow $time"
    until - now < 6.days -> "${DAYS[at.dayOfWeek.ordinal]} $time"
    else -> "${MONTHS[at.month.ordinal]} ${at.day}, $time"
  }
}

/** "8:00 AM", "12:30 PM". */
fun clockTime(at: LocalDateTime): String {
  val hour12 = (at.hour + 11) % 12 + 1
  val minute = at.minute.toString().padStart(2, '0')
  return "$hour12:$minute ${if (at.hour < 12) "AM" else "PM"}"
}

/** A short, relative "synced" time: "just now", "5 min ago", "3 h ago". */
fun syncedAgo(epochMillis: Long, now: Instant): String {
  val age = now - Instant.fromEpochMilliseconds(epochMillis)
  return when {
    age < 1.hours / 60 -> "just now"
    age < 1.hours -> "${age.inWholeMinutes} min ago"
    age < 1.days -> "${age.inWholeHours} h ago"
    else -> "${age.inWholeDays} d ago"
  }
}
