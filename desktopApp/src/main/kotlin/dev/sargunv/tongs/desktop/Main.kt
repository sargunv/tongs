package dev.sargunv.tongs.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.sargunv.tongs.codeview.CodeView
import dev.sargunv.tongs.codeview.CodeViewState
import dev.sargunv.tongs.codeview.rememberCodeViewState
import dev.sargunv.tongs.engine.Highlighter
import dev.sargunv.tongs.engine.Review
import dev.sargunv.tongs.forge.github.PullRequestRef
import java.io.File
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import org.jetbrains.skia.EncodedImageFormat

/**
 * Runs the app; see [runApp]. The spike modes are still here, behind flags:
 * - `--spike [storeDir oldCommit newCommit] [--bench] [--screenshot=out.png@row] [--no-highlight]`
 *   opens a review from a pre-filled object store (see `tongs-spike`) and renders it. Defaults to
 *   maplibre/maplibre-native-ffi#760. `--bench` and `--screenshot` imply `--spike`.
 * - `--pr owner/repo#number [--store=dir] [--exit-when-loaded] [--jump=fraction] [--no-highlight]`
 *   fetches a PR in-app without an account; see [runPullRequest].
 * - `--app-screenshots=dir` renders the app's main screens offscreen; see [renderAppScreenshots].
 * - `--changes-screenshots=dir [--open=owner/repo#N] [--review=owner/repo#N] [--api-url=url]
 *   [--mutate]` drives the PR changes screen offscreen; see [renderChangesScreenshots].
 * - `--open=owner/repo#N` (or a PR link) runs the app with that PR's changes open.
 * - `--thread-screenshots=dir owner/repo#number…` renders public threads and the composer
 *   offscreen; see [renderThreadScreenshots].
 */
fun main(args: Array<String>) {
  val spike =
    "--spike" in args ||
      "--bench" in args ||
      "--pr" in args ||
      args.any { it.startsWith("--screenshot=") }
  fun flag(name: String) = args.firstNotNullOfOrNull {
    it.removePrefix("--$name=").takeIf { s -> s != it }
  }
  if (!spike) {
    val shots = flag("app-screenshots")
    val changes = flag("changes-screenshots")
    val threadShots = flag("thread-screenshots")
    when {
      threadShots != null ->
        renderThreadScreenshots(File(threadShots), args.filterNot { it.startsWith("--") })
      shots != null -> renderAppScreenshots(File(shots))
      changes != null ->
        renderChangesScreenshots(
          File(changes),
          pr = flag("open") ?: "maplibre/maplibre-native-ffi#760",
          review = flag("review"),
          apiUrl = flag("api-url"),
          mutate = "--mutate" in args,
        )
      else -> runApp(args, open = flag("open"))
    }
    return
  }
  val highlighter = if ("--no-highlight" in args) null else Highlighter()
  val pr = args.indexOf("--pr").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
  if (pr != null) {
    val ref = requireNotNull(PullRequestRef.parse(pr)) { "expected owner/repo#number: $pr" }
    val store = args.firstNotNullOfOrNull { it.removePrefix("--store=").takeIf { s -> s != it } }
    val jump = args.firstNotNullOfOrNull { it.removePrefix("--jump=").takeIf { s -> s != it } }
    runPullRequest(
      ref,
      store?.let(::File),
      exitWhenLoaded = "--exit-when-loaded" in args,
      jumpTo = jump?.toDouble(),
      highlighter = highlighter,
    )
    highlighter?.close()
    return
  }
  val bench = "--bench" in args
  val screenshot = args.firstNotNullOfOrNull {
    it.removePrefix("--screenshot=").takeIf { s -> s != it }
  }
  val positional = args.filterNot { it.startsWith("--") }
  val (storeDir, oldCommit, newCommit) =
    positional.takeIf { it.size == 3 }
      ?: listOf(
        "/tmp/tongs-spike-store",
        "d269e1bdf76667607c2783521d4d2eb119cd76c0",
        "3b56ef91641085ad555def7a4b408c87f131fd8d",
      )

  val opened = TimeSource.Monotonic.markNow()
  val review = Review.open(storeDir, oldCommit, newCommit, highlighter)
  println(
    "Opened review: ${review.fileCount} files, ${review.rowCount} rows in ${opened.elapsedNow()}",
  )

  if (screenshot != null) {
    val (path, row) = screenshot.split("@")
    renderOffscreen(review, path, row.toInt())
    review.close()
    highlighter?.close()
    return
  }

  application {
    Window(
      onCloseRequest = ::exitApplication,
      title = "Tongs spike: ${review.fileCount} files, ${review.rowCount} rows",
      state = rememberWindowState(width = 1400.dp, height = 1000.dp),
    ) {
      val state = rememberCodeViewState(review)
      LaunchedEffect(Unit) { println("Render API: ${window.renderApi}") }
      CodeView(state)
      if (bench) {
        LaunchedEffect(state) {
          delay(1500)
          // With highlighting, the first pass of each runs cold (highlights land mid-scroll) and
          // the second warm (from the cache).
          for (pass in if (highlighter != null) listOf("cold", "warm") else listOf("plain")) {
            state.scrollToRow(0)
            benchmark("fling ($pass): 200 px/frame for 3000 frames", state, frames = 3000) {
              state.scrollBy(200.0)
            }
            highlighter?.let { println("  highlight cache  ${it.cacheBytes / 1024} KiB") }
          }
          for (pass in if (highlighter != null) listOf("cold", "warm") else listOf("plain")) {
            state.scrollToRow(0)
            benchmark("scrub ($pass): whole review in 600 frames", state, frames = 600) {
              state.scrollBy(state.maxOffsetY / 600)
            }
            highlighter?.let { println("  highlight cache  ${it.cacheBytes / 1024} KiB") }
          }
          val runtime = Runtime.getRuntime()
          System.gc()
          println(
            "JVM heap used: ${(runtime.totalMemory() - runtime.freeMemory()) / (1 shl 20)} MiB",
          )
          exitApplication()
        }
      }
    }
  }
  review.close()
  highlighter?.close()
}

private suspend fun benchmark(name: String, state: CodeViewState, frames: Int, step: () -> Unit) {
  state.stats.clear()
  val intervals = ArrayList<Long>(frames)
  var last = withFrameNanos { it }
  repeat(frames) {
    step()
    val now = withFrameNanos { it }
    intervals += now - last
    last = now
  }
  fun summary(nanos: List<Long>): String {
    val sorted = nanos.sorted()
    fun ms(q: Double) = "%.2f".format(sorted[((sorted.size - 1) * q).toInt()] / 1e6)
    return "p50 ${ms(0.5)}  p95 ${ms(0.95)}  p99 ${ms(0.99)}  max ${ms(1.0)} ms"
  }
  val fetches = state.stats.fetchNanos.filter { it > 0 }
  println("== $name")
  println("  frame interval  ${summary(intervals)}")
  println("  draw CPU        ${summary(state.stats.drawNanos)}")
  if (fetches.isNotEmpty()) println("  row fetch (${fetches.size}x)  ${summary(fetches)}")
  val stats = state.stats
  if (stats.rowsPending > 0) {
    println(
      "  rows drawn plain while highlighting  %.1f%%"
        .format(100.0 * stats.rowsPending / stats.rowsDrawn),
    )
  }
}

/**
 * Renders the view scrolled to [row] into a PNG, without opening a window. Waits up to 5 seconds
 * for the visible rows' highlights.
 */
private fun renderOffscreen(review: Review, path: String, row: Int) {
  val state = CodeViewState(review)
  val scene = ImageComposeScene(1400, 900, density = Density(1.5f)) { CodeView(state) }
  scene.render()
  state.scrollToRow(row)
  scene.render(1_000_000_000)
  val waited = TimeSource.Monotonic.markNow()
  while (
    review.rows(row, 60).any { it.highlightPending } && waited.elapsedNow().inWholeSeconds < 5
  ) {
    Thread.sleep(10)
  }
  val image = scene.render(2_000_000_000)
  File(path).writeBytes(checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
  scene.close()
  println("Wrote $path")
}
