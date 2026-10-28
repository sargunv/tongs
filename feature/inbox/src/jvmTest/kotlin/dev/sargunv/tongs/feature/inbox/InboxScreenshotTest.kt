package dev.sargunv.tongs.feature.inbox

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.sargunv.tongs.core.data.InboxRow
import dev.sargunv.tongs.core.data.InboxSettings
import dev.sargunv.tongs.core.ui.TongsTheme
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Desktop screenshots of the inbox, from made-up data. */
@OptIn(ExperimentalTestApi::class)
class InboxScreenshotTest {
  private val now = Instant.parse("2026-10-28T12:00:00Z")

  private fun row(
    id: String,
    title: String,
    reason: String,
    type: String = "PullRequest",
    number: Int? = id.toInt(),
    unread: Boolean = true,
    ago: kotlin.time.Duration,
    whatsNew: String? = null,
  ) =
    InboxRow(
      accountId = 1,
      accountLogin = "octocat",
      id = id,
      repoOwner = "octo-org",
      repoName = "hello-world",
      title = title,
      subjectType = type,
      subjectNumber = number,
      webUrl = null,
      reason = reason,
      isUnread = unread,
      updatedAt = (now - ago).toEpochMilliseconds(),
      sortAt = (now - ago).toEpochMilliseconds(),
      subjectState = null,
      whatsNew = whatsNew,
    )

  private val rows =
    listOf(
      row(
        "412",
        "Stream packs into the object store as they arrive",
        "review_requested",
        ago = 4.minutes,
      ),
      row(
        "409",
        "Crash when a grammar fails to extract on a full disk",
        "mention",
        "Issue",
        ago = 38.minutes,
        whatsNew = "2 new comments",
      ),
      row(
        "405",
        "Bump tree-house to 0.4",
        "author",
        ago = 2.hours,
        unread = false,
        whatsNew = "Checks failed",
      ),
      row("1", "v0.3.0", "subscribed", "Release", number = null, ago = 5.hours, unread = false),
      row(
        "398",
        "Draw wide characters on the column grid",
        "comment",
        ago = 26.hours,
        unread = false,
      ),
    )

  private val noActions =
    object : InboxActions {
      override fun open(row: InboxRow) {}

      override fun refresh() {}

      override fun markDone(row: InboxRow) {}

      override fun toggleRead(row: InboxRow) {}

      override fun snooze(row: InboxRow, until: Instant) {}
    }

  private fun capture(name: String, state: InboxUiState, dark: Boolean = false) =
    runDesktopComposeUiTest(width = 480, height = 560) {
      setContent {
        TongsTheme(darkTheme = dark) { InboxContent(state, now, { it.id == "409" }, noActions) }
      }
      onRoot().captureRoboImage("src/jvmTest/snapshots/$name.png")
    }

  private val state =
    InboxUiState(
      rows,
      InboxSettings(),
      refreshing = false,
      syncError = null,
      multipleAccounts = false,
    )

  @Test fun inbox() = capture("inbox", state)

  @Test fun inboxDark() = capture("inbox-dark", state, dark = true)

  @Test fun empty() = capture("inbox-empty", state.copy(rows = emptyList()))

  @Test fun syncError() = capture("inbox-sync-error", state.copy(syncError = "401 Bad credentials"))
}
