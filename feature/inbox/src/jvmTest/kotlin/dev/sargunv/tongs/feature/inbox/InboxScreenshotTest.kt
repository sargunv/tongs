package dev.sargunv.tongs.feature.inbox

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.sargunv.tongs.core.data.AccountSyncStatus
import dev.sargunv.tongs.core.data.InboxQuery
import dev.sargunv.tongs.core.data.InboxRow
import dev.sargunv.tongs.core.data.InboxSettings
import dev.sargunv.tongs.core.data.InboxViewEntity
import dev.sargunv.tongs.core.data.SsoNotice
import dev.sargunv.tongs.core.data.SyncProblem
import dev.sargunv.tongs.core.ui.TongsTheme
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Desktop screenshots of the inbox, from made-up data. */
@OptIn(ExperimentalTestApi::class)
class InboxScreenshotTest {
  private val now = Instant.parse("2026-10-28T19:00:00Z")

  private fun row(
    id: String,
    title: String,
    reason: String,
    type: String = "PullRequest",
    number: Int? = id.toInt(),
    unread: Boolean = true,
    ago: Duration,
    whatsNew: String? = null,
    state: String? = "OPEN",
    stateReason: String? = null,
    draft: Boolean = false,
    checks: String? = null,
    review: String? = null,
    repo: String = "hello-world",
    snoozedFor: Duration? = null,
    replyDraft: String? = null,
  ) =
    InboxRow(
      accountId = 1,
      accountLogin = "octocat",
      id = id,
      repoOwner = "octo-org",
      repoName = repo,
      title = title,
      subjectType = type,
      subjectNumber = number,
      webUrl = null,
      reason = reason,
      isUnread = unread,
      updatedAt = (now - ago).toEpochMilliseconds(),
      sortAt = (now - ago).toEpochMilliseconds(),
      subjectState = state,
      whatsNew = whatsNew,
      stateReason = stateReason,
      isDraft = draft,
      checks = checks,
      reviewDecision = review,
      snoozedUntil = snoozedFor?.let { (now + it).toEpochMilliseconds() },
      draft = replyDraft,
    )

  private val rows =
    listOf(
      row(
        "412",
        "Stream packs into the object store as they arrive",
        "review_requested",
        ago = 4.minutes,
        whatsNew = "Review requested from you, 3 new commits",
        checks = "PENDING",
      ),
      row(
        "409",
        "Crash when a grammar fails to extract on a full disk",
        "mention",
        "Issue",
        ago = 38.minutes,
        whatsNew = "2 new comments from alice and bob",
      ),
      row(
        "405",
        "Bump tree-house to 0.4",
        "author",
        ago = 2.hours,
        whatsNew = "Changes requested by alice, checks failed",
        checks = "FAILURE",
        review = "CHANGES_REQUESTED",
        replyDraft = "Good catch, the query cache needs the new key too…",
      ),
      row(
        "401",
        "Draw wide characters on the column grid",
        "author",
        ago = 3.hours,
        unread = false,
        whatsNew = "Merged by alice",
        state = "MERGED",
        repo = "codeview",
      ),
      row(
        "1",
        "v0.3.0",
        "subscribed",
        "Release",
        number = null,
        ago = 5.hours,
        unread = false,
        state = null,
      ),
      row(
        "398",
        "Support Forgejo's pinned notifications",
        "comment",
        "Issue",
        ago = 26.hours,
        unread = false,
        whatsNew = "Closed by bob",
        state = "CLOSED",
        stateReason = "COMPLETED",
        repo = "codeview",
      ),
      row(
        "396",
        "Spike: TextMate grammars as a fallback",
        "author",
        ago = 2.days,
        unread = false,
        draft = true,
        checks = "SUCCESS",
        review = "APPROVED",
      ),
    )

  private fun capture(
    name: String,
    state: InboxUiState,
    dark: Boolean = false,
    width: Int = 480,
    height: Int = 640,
    filterOpen: Boolean = false,
  ) =
    runDesktopComposeUiTest(width = width, height = height) {
      setContent {
        TongsTheme(darkTheme = dark) {
          InboxContent(
            state,
            now,
            { it.id == "409" },
            object : InboxActions {},
            filterOpen = filterOpen,
          )
        }
      }
      onRoot().captureRoboImage("src/jvmTest/snapshots/$name.png")
    }

  private val synced =
    AccountSyncStatus(1, "octocat", null, emptyList(), (now - 2.minutes).toEpochMilliseconds())

  private val views =
    listOf(
      InboxViewEntity(1, "Reviews", "reason:review-requested is:open", false, 0),
      InboxViewEntity(2, "Mentions", "reason:mention,team-mention", false, 1),
    )

  private val state =
    InboxUiState(
      rows,
      InboxSettings(),
      refreshing = false,
      sync = listOf(synced),
      views = views,
    )

  private fun problem(kind: SyncProblem.Kind, message: String = "", sso: SsoNotice? = null) =
    synced.copy(
      problem =
        SyncProblem(kind, message, retryAt = (now + 23.minutes).toEpochMilliseconds(), sso = sso),
    )

  @Test fun inbox() = capture("inbox", state)

  @Test fun inboxDark() = capture("inbox-dark", state, dark = true)

  @Test fun empty() = capture("inbox-empty", state.copy(rows = emptyList()))

  @Test
  fun filtered() {
    val query = InboxQuery.parse("is:pr is:open")
    capture(
      "inbox-filtered",
      state.copy(rows = rows.filter(query::matches), query = query),
      filterOpen = true,
    )
  }

  @Test
  fun nothingMatches() {
    val query = InboxQuery.parse("repo:octo-org/nope")
    capture("inbox-no-match", state.copy(rows = emptyList(), query = query), filterOpen = true)
  }

  @Test
  fun savedView() {
    val view = views.first()
    val query = InboxQuery.parse(view.query)
    capture(
      "inbox-view",
      state.copy(rows = rows.filter(query::matches), viewId = view.id, query = query),
    )
  }

  @Test fun grouped() = capture("inbox-grouped", state.copy(groupByRepo = true), height = 760)

  @Test
  fun snoozed() {
    val snoozed =
      listOf(
        rows[0].copy(snoozedUntil = (now + 13.hours).toEpochMilliseconds()),
        rows[2].copy(snoozedUntil = (now + 4.days).toEpochMilliseconds()),
      )
    capture(
      "inbox-snoozed",
      state.copy(rows = snoozed, query = InboxQuery.parse("is:snoozed")),
      height = 400,
    )
  }

  @Test
  fun offline() =
    capture("inbox-offline", state.copy(sync = listOf(problem(SyncProblem.Kind.Offline))))

  @Test
  fun rateLimited() =
    capture(
      "inbox-rate-limited",
      state.copy(sync = listOf(problem(SyncProblem.Kind.RateLimited)), rows = rows.take(3)),
      height = 480,
    )

  @Test
  fun ssoHidden() =
    capture(
      "inbox-sso",
      state.copy(
        sync =
          listOf(
            synced.copy(
              ssoHidden = listOf(SsoNotice("octo-org", "https://github.com/orgs/octo-org/sso")),
            ),
          ),
        rows = rows.take(3),
      ),
      height = 480,
    )

  @Test
  fun noAccess() =
    capture(
      "inbox-no-access",
      state.copy(
        rows = emptyList(),
        sync = listOf(problem(SyncProblem.Kind.NoAccess).copy(lastSyncedAt = null)),
        tokenUrls = mapOf(1L to "https://github.com/settings/tokens/new"),
      ),
    )

  @Test
  fun ssoRequired() =
    capture(
      "inbox-sso-required",
      state.copy(
        rows = emptyList(),
        sync =
          listOf(
            problem(
                SyncProblem.Kind.SsoRequired,
                sso = SsoNotice("octo-org", "https://github.com/orgs/octo-org/sso"),
              )
              .copy(lastSyncedAt = null),
          ),
      ),
    )
}
