package dev.sargunv.tongs.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sargunv.tongs.codeview.CodeView
import dev.sargunv.tongs.codeview.CodeViewColors
import dev.sargunv.tongs.codeview.CodeViewState
import dev.sargunv.tongs.engine.FetchOptions
import dev.sargunv.tongs.engine.FetchSession
import dev.sargunv.tongs.engine.ReviewLoader
import dev.sargunv.tongs.engine.ReviewSnapshot
import dev.sargunv.tongs.forge.github.GitHub
import dev.sargunv.tongs.forge.github.PullRequestRef
import dev.sargunv.tongs.forge.github.loadPullRequestReview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Fetches a GitHub PR's review in the background, for [PullRequestReview] to show as it loads.
 * [start] it before the UI exists: creating a window or activity takes about as long as the PR
 * metadata call.
 *
 * @param log timing and progress lines, from any thread.
 * @param openSession opens a fetch session over the repo's object store.
 */
class PullRequestLoad(
  private val github: GitHub,
  val ref: PullRequestRef,
  internal val log: (String) -> Unit = {},
  private val openSession: (FetchOptions) -> FetchSession,
) {
  internal val loader = MutableStateFlow<ReviewLoader?>(null)
  internal val status = MutableStateFlow("Loading $ref…")
  internal var job: Job? = null

  /** The view of the latest snapshot, once the first one is on screen. */
  var codeView by mutableStateOf<CodeViewState?>(null)
    internal set

  /** Whether loading has ended, in success or failure, and its last snapshot is on screen. */
  var settled by mutableStateOf(false)
    internal set

  /** Loads the review in [scope]; the job completes once every request has settled. */
  fun start(scope: CoroutineScope): Job =
    scope
      .launch(Dispatchers.Default) {
        try {
          loadPullRequestReview(
            github,
            ref,
            openSession = openSession,
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
      .also { job = it }
}

/**
 * Shows [load]'s review as it arrives: a status line until the headers are in, then the code view,
 * with each new snapshot swapped in under the reader and the files on screen fetched first.
 *
 * @param jumpTo scrolls to the file that far through the review as soon as the headers show, to
 *   watch it load first.
 */
@Composable
fun PullRequestReview(
  load: PullRequestLoad,
  modifier: Modifier = Modifier,
  colors: CodeViewColors = remember { CodeViewColors() },
  jumpTo: Double? = null,
) {
  val loader by load.loader.collectAsState()
  val status by load.status.collectAsState()
  var jumpedTo by remember { mutableStateOf<String?>(null) }

  // Swap each snapshot in on the UI thread, then free the one it replaced.
  LaunchedEffect(loader) {
    val loader =
      loader
        ?: run {
          // Failed before the fetch started.
          load.job?.join()
          if (load.loader.value == null) load.settled = true
          return@LaunchedEffect
        }
    for (snapshot in loader.snapshots) {
      val current = load.codeView
      if (current == null) {
        load.codeView = CodeViewState(snapshot)
        withFrameNanos {}
        load.log("on screen: first headers")
        if (jumpTo != null) {
          val file = (snapshot.fileCount * jumpTo).toInt().coerceIn(0, snapshot.fileCount - 1)
          jumpedTo = snapshot.filePath(file)
          load.codeView?.scrollToRow(snapshot.fileFirstRow(file))
          load.log("jumped to $jumpedTo")
        }
      } else {
        (current.swapRows(snapshot) as ReviewSnapshot).close()
      }
      jumpedTo?.let { path ->
        val file = snapshot.fileIndex(path)
        if (file >= 0 && snapshot.fileLoaded(file)) {
          withFrameNanos {}
          load.log("on screen: $path")
          jumpedTo = null
        }
      }
    }
    // The channel closes once loading ends, so this was the last snapshot.
    withFrameNanos {}
    (load.codeView?.rows as? ReviewSnapshot)?.let { rows ->
      val loaded = (0 until rows.fileCount).count(rows::fileLoaded)
      load.log("on screen: $loaded of ${rows.fileCount} files loaded, ${rows.rowCount} rows")
    }
    load.settled = true
  }

  // Fetch what's on screen first.
  val codeView = load.codeView
  LaunchedEffect(codeView, loader) {
    val state = codeView ?: return@LaunchedEffect
    val loader = loader ?: return@LaunchedEffect
    snapshotFlow { state.rows to state.visibleFiles() }
      .distinctUntilChanged()
      .collect { (rows, files) -> loader.prioritize(rows as ReviewSnapshot, files) }
  }

  DisposableEffect(codeView) { onDispose { (codeView?.rows as? ReviewSnapshot)?.close() } }

  if (codeView != null) {
    CodeView(codeView, modifier, colors)
  } else {
    Box(modifier.fillMaxSize().background(colors.background).padding(24.dp)) {
      BasicText(status, style = TextStyle(color = colors.text, fontSize = 14.sp))
    }
  }
}
