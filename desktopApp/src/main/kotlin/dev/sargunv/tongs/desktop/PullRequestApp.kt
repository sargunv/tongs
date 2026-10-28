package dev.sargunv.tongs.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.sargunv.tongs.engine.Highlighter
import dev.sargunv.tongs.engine.NativeFetchSession
import dev.sargunv.tongs.feature.review.PullRequestLoad
import dev.sargunv.tongs.feature.review.PullRequestReview
import dev.sargunv.tongs.forge.github.GitHub
import dev.sargunv.tongs.forge.github.PullRequestRef
import dev.sargunv.tongs.forge.github.gitHubHttpClient
import java.io.File
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

/**
 * Opens a GitHub PR, fetching it in-app and showing the review as it loads. Objects persist in a
 * per-repo store under the user cache directory, so reopening it, or another PR on the same repo,
 * fetches only what's new.
 *
 * For timing runs: [exitWhenLoaded] exits once loading has ended and the review is on screen, and
 * [jumpTo] scrolls to the file that far through the review as soon as the headers show, to watch it
 * load first.
 */
fun runPullRequest(
  ref: PullRequestRef,
  storeDir: File?,
  exitWhenLoaded: Boolean,
  jumpTo: Double? = null,
  highlighter: Highlighter? = null,
) {
  val started = TimeSource.Monotonic.markNow()
  val log = { message: String ->
    println("%6d ms  %s".format(started.elapsedNow().inWholeMilliseconds, message))
  }
  val http = gitHubHttpClient()
  val objects = storeDir ?: defaultStoreDir(ref)
  log("store: $objects")

  val load =
    PullRequestLoad(GitHub(http, githubToken()), ref, log = log) {
      NativeFetchSession.open(objects.path, it, highlighter)
    }
  val loading = load.start(CoroutineScope(Dispatchers.Default))

  application {
    val files = load.codeView?.rows?.fileCount
    Window(
      onCloseRequest = ::exitApplication,
      title = if (files == null) "Tongs: $ref" else "Tongs: $ref ($files files)",
      state = rememberWindowState(width = 1400.dp, height = 1000.dp),
    ) {
      if (exitWhenLoaded) {
        LaunchedEffect(Unit) {
          snapshotFlow { load.settled }.first { it }
          exitApplication()
        }
      }
      PullRequestReview(load, jumpTo = jumpTo)
    }
  }
  loading.cancel()
  http.close()
}

/** `$XDG_CACHE_HOME/tongs/github.com/<owner>/<repo>/objects`, one git object store per repo. */
private fun defaultStoreDir(ref: PullRequestRef): File {
  val cache =
    System.getenv("XDG_CACHE_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".cache")
  return cache.resolve("tongs/github.com/${ref.owner}/${ref.repo}/objects")
}

/** `GH_TOKEN`, or the GitHub CLI's token if it's signed in. There's no sign-in UI yet. */
private fun githubToken(): String? =
  System.getenv("GH_TOKEN")?.takeIf { it.isNotBlank() }
    ?: runCatching {
      ProcessBuilder("gh", "auth", "token").start().inputStream.bufferedReader().readText().trim()
    }
      .getOrNull()
      ?.takeIf { it.isNotBlank() }
