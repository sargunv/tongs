package dev.sargunv.tongs.feature.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.sargunv.tongs.core.data.AccountSyncStatus
import dev.sargunv.tongs.core.data.SyncProblem
import dev.sargunv.tongs.core.ui.Banner
import dev.sargunv.tongs.core.ui.EmptyState
import dev.sargunv.tongs.core.ui.TongsIcons
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** What a sync problem says, and what the user can do about it; the inbox's, for activity. */
private data class ProblemCopy(
  val icon: ImageVector,
  val title: String,
  val body: String,
  val action: String?,
  val onAction: (() -> Unit)?,
  val secondary: String? = null,
  val onSecondary: (() -> Unit)? = null,
)

@Composable
private fun copy(
  status: AccountSyncStatus,
  problem: SyncProblem,
  now: Instant,
  state: FeedUiState,
  actions: FeedActions,
): ProblemCopy {
  val uri = LocalUriHandler.current
  val who = if (state.multipleAccounts) " for ${status.login}" else ""
  val synced = status.lastSyncedAt?.let { " Showing what synced ${ago(it, now)}." }.orEmpty()
  val tokenUrl = state.accounts.find { it.id == status.accountId }?.tokenUrl
  return when (problem.kind) {
    SyncProblem.Kind.Offline ->
      ProblemCopy(
        TongsIcons.CloudOff,
        "You're offline",
        "Tongs will sync activity$who when it can reach GitHub again.$synced",
        "Retry",
        actions::refresh,
      )
    SyncProblem.Kind.RateLimited -> {
      val until = problem.retryAt?.let { " until ${clock(it)}" }.orEmpty()
      ProblemCopy(
        TongsIcons.Schedule,
        "Rate limited$until",
        "GitHub's hourly API limit$who is used up. Tongs syncs again once it resets.$synced",
        null,
        null,
      )
    }
    SyncProblem.Kind.NoAccess ->
      ProblemCopy(
        TongsIcons.Key,
        "This token can't read activity",
        "GitHub's events API refused the token$who. A classic token with the repo and read:org " +
          "scopes reads the feed of every repo and organization you can see. Create one, then " +
          "sign in with it.",
        "Create a token",
        tokenUrl?.let { { uri.openUri(it) } },
        "Sign in again",
        actions::signIn,
      )
    SyncProblem.Kind.Unauthorized ->
      ProblemCopy(
        TongsIcons.Key,
        "GitHub no longer accepts this token",
        "The token$who was revoked or expired. Sign in with a new one.$synced",
        "Sign in again",
        actions::signIn,
      )
    SyncProblem.Kind.SsoRequired -> {
      val org = problem.sso?.organization
      ProblemCopy(
        TongsIcons.Lock,
        if (org != null) "$org requires single sign-on" else "Single sign-on required",
        "Authorize the token$who for SAML SSO, then refresh.",
        "Authorize",
        problem.sso?.url?.let { { uri.openUri(it) } },
        "Refresh",
        actions::refresh,
      )
    }
    SyncProblem.Kind.Other ->
      ProblemCopy(
        TongsIcons.Warning,
        "Couldn't sync activity$who",
        problem.message + synced,
        "Retry",
        actions::refresh,
      )
  }
}

/**
 * Strips above the list: each account's sync problem, organizations hiding their activity until SSO
 * authorization, and a token that can't see private repos. The list below keeps showing what's
 * stored.
 */
@Composable
internal fun FeedBanners(state: FeedUiState, now: Instant, actions: FeedActions) {
  val uri = LocalUriHandler.current
  val empty = state.data?.items.isNullOrEmpty()
  for (status in state.sync) {
    val problem = status.problem
    if (problem != null && !(empty && status.lastSyncedAt == null)) {
      val c = copy(status, problem, now, state, actions)
      Banner(
        icon = c.icon,
        text = "${c.title}. ${c.body}",
        serious =
          problem.kind != SyncProblem.Kind.Offline && problem.kind != SyncProblem.Kind.RateLimited,
        action = c.action?.takeIf { c.onAction != null },
        onAction = c.onAction,
      )
    }
    for (notice in status.ssoHidden) {
      Banner(
        icon = TongsIcons.Lock,
        text =
          "${notice.organization ?: "An organization"} hides its activity until you authorize " +
            "this token for SAML SSO.",
        serious = false,
        action = "Authorize",
        onAction = { uri.openUri(notice.url) },
      )
    }
  }
  for (account in state.accounts) {
    if ("repo" !in account.missingScopes) continue
    val who = if (state.multipleAccounts) " for ${account.login}" else ""
    Banner(
      icon = TongsIcons.Lock,
      text = "Activity in private repos is hidden$who: the token lacks the repo scope.",
      serious = false,
      action = "Create a token".takeIf { account.tokenUrl != null },
      onAction = account.tokenUrl?.let { { uri.openUri(it) } },
    )
  }
}

/**
 * The whole list area when there's nothing to show: a problem that kept the feed from ever syncing,
 * everything muted, or a quiet feed.
 */
@Composable
internal fun EmptyFeed(
  state: FeedUiState,
  now: Instant,
  actions: FeedActions,
  onShowMuted: () -> Unit,
) {
  val blocking = state.sync.firstOrNull { it.problem != null && it.lastSyncedAt == null }
  val problem = blocking?.problem
  val muted = state.data?.mutedEvents ?: 0
  when {
    problem != null -> {
      val c = copy(blocking, problem, now, state, actions)
      EmptyState(
        c.icon,
        c.title,
        body = c.body,
        action = {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c.secondary != null && c.onSecondary != null) {
              OutlinedButton(onClick = c.onSecondary) { Text(c.secondary) }
            }
            if (c.action != null && c.onAction != null)
              Button(onClick = c.onAction) { Text(c.action) }
          }
        },
      )
    }
    muted > 0 ->
      EmptyState(
        TongsIcons.Activity,
        "Everything here is muted",
        body = "The $muted latest events are all from repos or people you muted.",
        action = {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.data?.canLoadMore == true) {
              Button(onClick = actions::loadMore) { Text("Show older activity") }
            }
            OutlinedButton(onClick = onShowMuted) { Text("Show muted") }
          }
        },
      )
    else -> {
      val synced = state.sync.mapNotNull { it.lastSyncedAt }.maxOrNull()
      EmptyState(
        TongsIcons.Activity,
        "No activity yet",
        body =
          "Pushes, pull requests, issues, and releases from the repos you watch, the people you " +
            "follow, and your organizations show up here" +
            (synced?.let { "; last checked ${ago(it, now)}." } ?: "."),
      )
    }
  }
}

private fun ago(epochMillis: Long, now: Instant): String {
  val age = (now.toEpochMilliseconds() - epochMillis).milliseconds
  return when {
    age.inWholeMinutes < 1 -> "just now"
    age.inWholeHours < 1 -> "${age.inWholeMinutes} min ago"
    age.inWholeDays < 1 -> "${age.inWholeHours} h ago"
    else -> "${age.inWholeDays} days ago"
  }
}

private fun clock(epochMillis: Long): String {
  val t =
    Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
  return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}
