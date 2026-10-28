package dev.sargunv.tongs.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.sargunv.tongs.codeview.CodeView
import dev.sargunv.tongs.codeview.CodeViewState
import dev.sargunv.tongs.engine.NativeFetchSession
import dev.sargunv.tongs.engine.ReviewLoader
import dev.sargunv.tongs.engine.ReviewSnapshot
import dev.sargunv.tongs.forge.github.GitHub
import dev.sargunv.tongs.forge.github.PullRequestRef
import dev.sargunv.tongs.forge.github.loadPullRequestReview
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Dispatcher

/**
 * Opens a GitHub PR, fetching it in-app and showing the review as it loads. Objects persist in a
 * per-repo store under the user cache directory, so reopening it, or another PR on the same repo,
 * fetches only what's new.
 *
 * For timing runs: [exitWhenLoaded] exits once the whole review is on screen, and [jumpTo] scrolls
 * to the file that far through the review as soon as the headers show, to watch it load first.
 */
fun runPullRequest(
  ref: PullRequestRef,
  storeDir: File?,
  exitWhenLoaded: Boolean,
  jumpTo: Double? = null,
) {
  val started = TimeSource.Monotonic.markNow()
  val log = { message: String ->
    println("%6d ms  %s".format(started.elapsedNow().inWholeMilliseconds, message))
  }
  val http =
    HttpClient(OkHttp) {
      // Blob batches run in parallel; OkHttp would otherwise queue past 5 per host.
      engine { config { dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 }) } }
    }
  val github = GitHub(http, githubToken())
  val objects = storeDir ?: defaultStoreDir(ref)
  log("store: $objects")

  // Start fetching before the window exists: creating it takes about as long as the metadata.
  val loader = MutableStateFlow<ReviewLoader?>(null)
  val status = MutableStateFlow("Loading $ref…")
  val loading =
    CoroutineScope(Dispatchers.Default).launch {
      try {
        loadPullRequestReview(
          github,
          ref,
          openSession = { NativeFetchSession.open(objects.path, it) },
          onLoader = { loader.value = it },
          log = log,
        )
      } catch (e: Exception) {
        status.value = "Failed to load $ref: ${e.message}"
        log(status.value)
        e.printStackTrace()
      } finally {
        // Every request has settled by now; the last snapshots stay in the channel.
        loader.value?.close()
      }
    }

  application {
    Window(
      onCloseRequest = ::exitApplication,
      title = "Tongs: $ref",
      state = rememberWindowState(width = 1400.dp, height = 1000.dp),
    ) {
      val loader by loader.collectAsState()
      val status by status.collectAsState()
      var state by remember { mutableStateOf<CodeViewState?>(null) }
      var drained by remember { mutableStateOf(false) }
      var jumpedTo by remember { mutableStateOf<String?>(null) }

      if (exitWhenLoaded) {
        LaunchedEffect(Unit) {
          loading.join()
          snapshotFlow { drained }.first { it }
          withFrameNanos {}
          log("on screen: all ${state?.rows?.fileCount} files, ${state?.rows?.rowCount} rows")
          exitApplication()
        }
      }

      // Swap each snapshot in on the UI thread, then free the one it replaced.
      LaunchedEffect(loader) {
        val loader = loader ?: return@LaunchedEffect
        for (snapshot in loader.snapshots) {
          val current = state
          if (current == null) {
            state = CodeViewState(snapshot)
            withFrameNanos {}
            log("on screen: first headers")
            if (jumpTo != null) {
              val file = (snapshot.fileCount * jumpTo).toInt().coerceIn(0, snapshot.fileCount - 1)
              jumpedTo = snapshot.filePath(file)
              state?.scrollToRow(snapshot.fileFirstRow(file))
              log("jumped to $jumpedTo")
            }
          } else {
            (current.swapRows(snapshot) as ReviewSnapshot).close()
          }
          jumpedTo?.let { path ->
            val file = snapshot.fileIndex(path)
            if (file >= 0 && snapshot.fileLoaded(file)) {
              withFrameNanos {}
              log("on screen: $path")
              jumpedTo = null
            }
          }
          window.title = "Tongs: $ref (${snapshot.fileCount} files)"
        }
        drained = true
      }

      // Fetch what's on screen first.
      LaunchedEffect(state, loader) {
        val state = state ?: return@LaunchedEffect
        val loader = loader ?: return@LaunchedEffect
        snapshotFlow { state.rows to state.visibleFiles() }
          .distinctUntilChanged()
          .collect { (rows, files) -> loader.prioritize(rows as ReviewSnapshot, files) }
      }

      val current = state
      if (current != null) {
        CodeView(current)
      } else {
        Box(Modifier.fillMaxSize().background(Color(0xFF16181D)).padding(24.dp)) {
          BasicText(status, style = TextStyle(color = Color(0xFFD5D8DE), fontSize = 14.sp))
        }
      }
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

/** `GH_TOKEN`, or the GitHub CLI's token. There's no sign-in UI yet. */
private fun githubToken(): String =
  System.getenv("GH_TOKEN")?.takeIf { it.isNotBlank() }
    ?: ProcessBuilder("gh", "auth", "token").start().inputStream.bufferedReader().readText().trim()
