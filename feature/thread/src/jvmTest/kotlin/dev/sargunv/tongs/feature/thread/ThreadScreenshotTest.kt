package dev.sargunv.tongs.feature.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sargunv.tongs.core.composer.rememberPreviewComposerState
import dev.sargunv.tongs.core.data.DraftKey
import dev.sargunv.tongs.core.markdown.CodeHighlighter
import dev.sargunv.tongs.core.markdown.LocalCodeHighlighter
import dev.sargunv.tongs.core.markdown.MarkdownContext
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.core.ui.TongsTheme
import dev.sargunv.tongs.forge.api.Actor
import dev.sargunv.tongs.forge.api.ReviewState
import dev.sargunv.tongs.forge.api.TimelineItem
import io.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlin.test.Test
import org.jetbrains.skia.Image

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
          // The golden uses plain fences; don't race the engine's background highlighting.
          CompositionLocalProvider(LocalCodeHighlighter provides CodeHighlighter.Plain) {
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

  @Test fun emptyReviewVerdictsFolded() = emptyReviewVerdicts(360)

  @Test fun emptyReviewVerdictsUnfolded() = emptyReviewVerdicts(700)

  @Test fun emptyReviewVerdictsDesktop() = emptyReviewVerdicts(1000)

  private fun emptyReviewVerdicts(width: Int) =
    runDesktopComposeUiTest(width = width, height = 420) {
      setContent {
        TongsTheme(darkTheme = false) {
          Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
              val env =
                TimelineEnv(
                  context = MarkdownContext("https://github.com", Fixtures.repo),
                  now = Fixtures.now,
                  actions = ThreadActions.None,
                  canReact = false,
                  canComment = false,
                  isPullRequest = true,
                  openInBrowser = {},
                )
              for (state in
                listOf(
                  ReviewState.Approved,
                  ReviewState.ChangesRequested,
                  ReviewState.Dismissed,
                  ReviewState.Commented,
                )) {
                TimelineRowView(
                  TimelineRow.Review(
                    TimelineItem.Review(state.raw, Fixtures.now, Actor("mona"), state, body = ""),
                    emptyList(),
                  ),
                  env,
                )
              }
            }
          }
        }
      }
      waitForIdle()
      onNodeWithText("approved").assertIsDisplayed()
      onNodeWithText("requested changes").assertIsDisplayed()
      onRoot().captureRoboImage("src/jvmTest/snapshots/empty-review-verdicts-$width.png")
    }

  @Test fun composer() = composer("composer", dark = false)

  @Test fun composerDark() = composer("composer-dark", dark = true)

  @Test fun composerFolded() = composer("composer-360", dark = false, width = 360, golden = false)

  @Test fun composerUnfolded() = composer("composer-700", dark = false, width = 700, golden = false)

  @Test
  fun composerDesktop() = composer("composer-1200", dark = false, width = 1200, golden = false)

  private fun composer(name: String, dark: Boolean, width: Int = 420, golden: Boolean = true) =
    runDesktopComposeUiTest(width = width, height = 640) {
      setContent {
        TongsTheme(darkTheme = dark) {
          val state = rememberPreviewComposerState(REPLY_TEXT, DraftKey(1, Fixtures.repo, 412))
          ReplyContent(
            state = state,
            detail = Fixtures.pullRequest,
            number = 412,
            repo = Fixtures.repo.toString(),
            webUrl = "https://github.com",
            onSend = { _, _ -> },
            onClose = {},
          )
        }
      }
      waitForIdle()
      onNodeWithText("Comment").assertIsDisplayed()
      if (golden) onRoot().captureRoboImage("src/jvmTest/snapshots/$name.png")
      else {
        val output = File("build/reports/drafts/thread-$name.png")
        output.parentFile.mkdirs()
        output.writeBytes(
          checkNotNull(
              Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(),
            )
            .bytes,
        )
      }
    }
}

private const val REPLY_TEXT =
  "@lin-yu wrote:\n\n> Does this change the store layout?\n\nNo, same layout. Tested against a store from **v0.2**; see #398."
