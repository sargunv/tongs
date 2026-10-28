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
import dev.sargunv.tongs.engine.Review
import dev.sargunv.tongs.forge.github.PullRequestRef
import java.io.File
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import org.jetbrains.skia.EncodedImageFormat

/**
 * Spike: opens a review from a pre-filled object store (see `tongs-spike`) and renders it.
 *
 * Usage: `[storeDir oldCommit newCommit] [--bench] [--screenshot=out.png@row]`. Defaults to
 * maplibre/maplibre-native-ffi#760. Or `--pr owner/repo#number [--store=dir] [--exit-when-loaded]
 * [--jump=fraction]` to fetch a PR in-app; see [runPullRequest].
 */
fun main(args: Array<String>) {
  val pr = args.indexOf("--pr").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
  if (pr != null) {
    val ref = requireNotNull(PullRequestRef.parse(pr)) { "expected owner/repo#number: $pr" }
    val store = args.firstNotNullOfOrNull { it.removePrefix("--store=").takeIf { s -> s != it } }
    val jump = args.firstNotNullOfOrNull { it.removePrefix("--jump=").takeIf { s -> s != it } }
    return runPullRequest(
      ref,
      store?.let(::File),
      exitWhenLoaded = "--exit-when-loaded" in args,
      jumpTo = jump?.toDouble(),
    )
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
  val review = Review.open(storeDir, oldCommit, newCommit)
  println(
    "Opened review: ${review.fileCount} files, ${review.rowCount} rows in ${opened.elapsedNow()}",
  )

  if (screenshot != null) {
    val (path, row) = screenshot.split("@")
    renderOffscreen(review, path, row.toInt())
    review.close()
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
          benchmark("fling: 200 px/frame for 3000 frames", state, frames = 3000) {
            state.scrollBy(200.0)
          }
          state.scrollToRow(0)
          benchmark("scrub: whole review in 600 frames", state, frames = 600) {
            state.scrollBy(state.maxOffsetY / 600)
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
}

/** Renders the view scrolled to [row] into a PNG, without opening a window. */
private fun renderOffscreen(review: Review, path: String, row: Int) {
  val state = CodeViewState(review)
  val scene = ImageComposeScene(1400, 900, density = Density(1.5f)) { CodeView(state) }
  scene.render()
  state.scrollToRow(row)
  val image = scene.render(1_000_000_000)
  File(path).writeBytes(checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
  scene.close()
  println("Wrote $path")
}
