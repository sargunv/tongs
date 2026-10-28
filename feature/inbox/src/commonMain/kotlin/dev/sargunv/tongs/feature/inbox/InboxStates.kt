package dev.sargunv.tongs.feature.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.sargunv.tongs.core.data.AccountSyncStatus
import dev.sargunv.tongs.core.data.SyncProblem
import dev.sargunv.tongs.core.ui.EmptyState
import dev.sargunv.tongs.core.ui.TongsIcons
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** What a sync problem says, and what the user can do about it. */
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
  showAccount: Boolean,
  tokenUrl: String?,
  actions: InboxActions,
): ProblemCopy {
  val uri = LocalUriHandler.current
  val who = if (showAccount) " for ${status.login}" else ""
  val synced = status.lastSyncedAt?.let { " Showing what synced ${syncedAgo(it, now)}." }.orEmpty()
  return when (problem.kind) {
    SyncProblem.Kind.Offline ->
      ProblemCopy(
        TongsIcons.CloudOff,
        "You're offline",
        "Tongs will sync$who when it can reach GitHub again.$synced",
        "Retry",
        actions::refresh,
      )
    SyncProblem.Kind.RateLimited -> {
      val until =
        problem.retryAt?.let {
          " until " +
            clockTime(
              Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()),
            )
        }
      ProblemCopy(
        TongsIcons.Schedule,
        "Rate limited${until.orEmpty()}",
        "GitHub's hourly API limit$who is used up. Tongs syncs again once it resets.$synced",
        null,
        null,
      )
    }
    SyncProblem.Kind.NoAccess ->
      ProblemCopy(
        TongsIcons.Key,
        "This token can't read notifications",
        "GitHub's notifications API takes a classic token or OAuth token with the " +
          "notifications or repo scope; fine-grained tokens can't read it. Create a token$who, " +
          "then sign in with it.",
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
        "Couldn't sync$who",
        problem.message + synced,
        "Retry",
        actions::refresh,
      )
  }
}

/**
 * A strip above the list for each account with a sync problem, or with threads hidden by SSO. The
 * list below still shows what's stored, so problems don't take over the screen.
 */
@Composable
internal fun SyncBanners(state: InboxUiState, now: Instant, actions: InboxActions) {
  val uri = LocalUriHandler.current
  for (status in state.sync) {
    val problem = status.problem
    // With nothing to list, the empty state explains it instead.
    val explainedByEmptyState = state.rows.isNullOrEmpty() && status.lastSyncedAt == null
    if (problem != null && !explainedByEmptyState) {
      val c =
        copy(
          status,
          problem,
          now,
          state.multipleAccounts,
          state.tokenUrls[status.accountId],
          actions,
        )
      Banner(
        icon = c.icon,
        text = "${c.title}. ${c.body}",
        serious = problem.kind.isSerious,
        action = c.action?.takeIf { c.onAction != null },
        onAction = c.onAction,
      )
    }
    for (notice in status.ssoHidden) {
      val org = notice.organization ?: "An organization"
      Banner(
        icon = TongsIcons.Lock,
        text = "$org hides its notifications until you authorize this token for SAML SSO.",
        serious = false,
        action = "Authorize",
        onAction = { uri.openUri(notice.url) },
      )
    }
  }
}

@Composable
private fun Banner(
  icon: ImageVector,
  text: String,
  serious: Boolean,
  action: String?,
  onAction: (() -> Unit)?,
) {
  val colors = MaterialTheme.colorScheme
  val container = colors.surfaceContainerHigh
  val content = colors.onSurface
  Surface(
    color = container,
    contentColor = content,
    shape = MaterialTheme.shapes.large,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
      Icon(icon, null, Modifier.size(20.dp), tint = if (serious) colors.error else colors.primary)
      Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
      if (action != null && onAction != null) {
        TextButton(onClick = onAction) { Text(action) }
      }
    }
  }
}

/**
 * The whole list area when there's nothing to list: a problem that kept the inbox from ever
 * syncing, a filter that matches nothing, or inbox zero.
 */
@Composable
internal fun EmptyInbox(state: InboxUiState, now: Instant, actions: InboxActions) {
  // A problem explains an empty inbox only if nothing ever synced; otherwise it's a banner.
  val blocking = state.sync.firstOrNull { it.problem != null && it.lastSyncedAt == null }
  val problem = blocking?.problem
  when {
    problem != null -> {
      val c =
        copy(
          blocking,
          problem,
          now,
          state.multipleAccounts,
          state.tokenUrls[blocking.accountId],
          actions,
        )
      EmptyState(
        c.icon,
        c.title,
        body = c.body,
        action = {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c.secondary != null && c.onSecondary != null) {
              OutlinedButton(onClick = c.onSecondary) { Text(c.secondary) }
            }
            if (c.action != null && c.onAction != null) {
              Button(onClick = c.onAction) { Text(c.action) }
            }
          }
        },
      )
    }
    state.query.wantsSnoozed && state.query.text.trim() == "is:snoozed" ->
      EmptyState(
        TongsIcons.Snooze,
        "Nothing snoozed",
        body = "Snoozed threads wait here, then come back to the inbox unread.",
      )
    state.view != null ->
      EmptyState(
        TongsIcons.DoneAll,
        "All clear",
        body =
          "Nothing in ${state.view!!.name} right now: no notifications match ${state.query.text.trim()}.",
      )
    !state.query.isEmpty ->
      EmptyState(
        TongsIcons.FilterList,
        "Nothing matches",
        body = "No notifications match ${state.query.text.trim()}.",
        action = {
          if (state.view == null) {
            OutlinedButton(onClick = { actions.setQuery("") }) { Text("Clear filter") }
          }
        },
      )
    else -> {
      val synced = state.sync.mapNotNull { it.lastSyncedAt }.maxOrNull()
      EmptyState(
        TongsIcons.DoneAll,
        "Inbox zero",
        body =
          "Nothing left to act on. New notifications show up here while Tongs is open" +
            (synced?.let { "; last checked ${syncedAgo(it, now)}." } ?: "."),
      )
    }
  }
}

/** Whether a problem needs the user, rather than passing by itself. */
private val SyncProblem.Kind.isSerious: Boolean
  get() = this != SyncProblem.Kind.Offline && this != SyncProblem.Kind.RateLimited
