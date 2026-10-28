package dev.sargunv.tongs.feature.thread

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.sargunv.tongs.core.composer.rememberPreviewComposerState
import dev.sargunv.tongs.core.data.DraftKey
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.core.ui.TongsTheme
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test

/** Desktop screenshots of the thread and its composer, from made-up data. */
@OptIn(ExperimentalTestApi::class)
class ThreadScreenshotTest {
  private val prRoute = Route.Thread(1, Fixtures.repo, "PullRequest", 412)
  private val issueRoute = Route.Thread(1, Fixtures.repo, "Issue", 409)

  private fun capture(
    name: String,
    route: Route.Thread,
    state: ThreadUiState,
    width: Int = 420,
    height: Int = 1800,
    dark: Boolean = false,
  ) =
    runDesktopComposeUiTest(width = width, height = height) {
      setContent {
        TongsTheme(darkTheme = dark) {
          ThreadContent(
            route = route,
            state = state,
            now = Fixtures.now,
            actions = ThreadActions.None,
            onBack = {},
            onOpenChanges = {},
            onOpenLink = {},
          )
        }
      }
      waitForIdle()
      onRoot().captureRoboImage("src/jvmTest/snapshots/$name.png")
    }

  @Test
  fun pullRequest() = capture("thread-pr", prRoute, ThreadUiState(Fixtures.pullRequestSnapshot))

  @Test
  fun pullRequestDark() =
    capture("thread-pr-dark", prRoute, ThreadUiState(Fixtures.pullRequestSnapshot), dark = true)

  @Test
  fun pullRequestWide() =
    capture(
      "thread-pr-wide",
      prRoute,
      ThreadUiState(Fixtures.pullRequestSnapshot),
      width = 1000,
      height = 1500,
    )

  @Test
  fun pullRequestWithGapAndDraft() =
    capture(
      "thread-pr-gap",
      prRoute,
      ThreadUiState(Fixtures.pullRequestWithGap, hasDraft = true),
    )

  @Test
  fun issue() =
    capture("thread-issue", issueRoute, ThreadUiState(Fixtures.issueSnapshot), height = 1300)

  @Test
  fun loading() =
    capture(
      "thread-loading",
      prRoute,
      ThreadUiState(notificationTitle = "Stream packs into the object store as they arrive"),
      height = 600,
    )

  @Test fun composer() = composer("composer", dark = false)

  @Test fun composerDark() = composer("composer-dark", dark = true)

  private fun composer(name: String, dark: Boolean) =
    runDesktopComposeUiTest(width = 420, height = 640) {
      setContent {
        TongsTheme(darkTheme = dark) {
          val state = rememberPreviewComposerState(REPLY_TEXT, DraftKey(1, Fixtures.repo, 412))
          ReplyContent(
            state = state,
            detail = Fixtures.pullRequest,
            number = 412,
            repo = Fixtures.repo.toString(),
            webUrl = "https://github.com",
            onSend = { _, _, _ -> },
            onClose = {},
          )
        }
      }
      waitForIdle()
      onRoot().captureRoboImage("src/jvmTest/snapshots/$name.png")
    }
}

private const val REPLY_TEXT =
  "@lin-yu wrote:\n\n> Does this change the store layout?\n\nNo, same layout. Tested against a store from **v0.2**; see #398."
