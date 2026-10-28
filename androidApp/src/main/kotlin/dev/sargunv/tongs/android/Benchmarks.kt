package dev.sargunv.tongs.android

import android.graphics.RenderNode
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.sargunv.tongs.codeview.AndroidTextPath
import dev.sargunv.tongs.codeview.CodeViewState
import dev.sargunv.tongs.codeview.TextPainter
import dev.sargunv.tongs.codeview.androidTextPath
import kotlinx.coroutines.delay

/** The same passes as `desktopApp --bench`, after a text-drawing comparison. */
internal suspend fun runBenchmarks(state: CodeViewState, window: Window, fontSizePx: Float) {
  delay(1500)
  if (Build.VERSION.SDK_INT >= 29) benchmarkTextPaths(state, fontSizePx)
  benchmark("fling: 200 px/frame for 3000 frames", state, window, frames = 3000) {
    state.scrollBy(200.0)
  }
  state.scrollToRow(0)
  benchmark("scrub: whole review in 600 frames", state, window, frames = 600) {
    state.scrollBy(state.maxOffsetY / 600)
  }
  val runtime = Runtime.getRuntime()
  System.gc()
  Log.i(
    TAG,
    "JVM heap used: ${(runtime.totalMemory() - runtime.freeMemory()) shr 20} MiB, " +
      "native heap: ${Debug.getNativeHeapAllocatedSize() shr 20} MiB",
  )
}

private fun summary(nanos: List<Long>): String {
  val sorted = nanos.sorted()
  fun ms(q: Double) = "%.2f".format(sorted[((sorted.size - 1) * q).toInt()] / 1e6)
  return "p50 ${ms(0.5)}  p95 ${ms(0.95)}  p99 ${ms(0.99)}  max ${ms(1.0)} ms"
}

private suspend fun benchmark(
  name: String,
  state: CodeViewState,
  window: Window,
  frames: Int,
  step: () -> Unit,
) {
  val hwui = HwuiFrames()
  val thread = HandlerThread("frame-metrics").apply { start() }
  window.addOnFrameMetricsAvailableListener(hwui, Handler(thread.looper))
  state.stats.clear()
  val intervals = ArrayList<Long>(frames)
  var last = withFrameNanos { it }
  repeat(frames) {
    step()
    val now = withFrameNanos { it }
    intervals += now - last
    last = now
  }
  window.removeOnFrameMetricsAvailableListener(hwui)
  thread.quitSafely()

  val vsync = 1e9 / window.decorView.display.refreshRate
  val missed = intervals.sumOf { (it / vsync - 0.5).toInt().coerceAtLeast(0) }
  val fetches = state.stats.fetchNanos.filter { it > 0 }
  Log.i(TAG, "== $name")
  Log.i(TAG, "  frame interval  ${summary(intervals)}, $missed vsyncs missed")
  Log.i(TAG, "  draw CPU        ${summary(state.stats.drawNanos)}")
  if (fetches.isNotEmpty()) Log.i(TAG, "  row fetch (${fetches.size}x)  ${summary(fetches)}")
  synchronized(hwui) {
    Log.i(TAG, "  UI thread       ${summary(hwui.uiThread)}")
    Log.i(TAG, "  RenderThread    ${summary(hwui.renderThread)}")
    Log.i(TAG, "  swap buffers    ${summary(hwui.swap)}")
    if (hwui.gpu.isNotEmpty()) Log.i(TAG, "  GPU             ${summary(hwui.gpu)}")
  }
}

/** Collects HWUI's per-frame stage durations. */
private class HwuiFrames : Window.OnFrameMetricsAvailableListener {
  /** Input, animation, layout, and recording the display list. */
  val uiThread = ArrayList<Long>()
  /** Syncing the display list and issuing GL commands. */
  val renderThread = ArrayList<Long>()
  val swap = ArrayList<Long>()
  val gpu = ArrayList<Long>()

  override fun onFrameMetricsAvailable(window: Window, metrics: FrameMetrics, dropped: Int) {
    fun sum(vararg ids: Int) = ids.sumOf { metrics.getMetric(it) }
    synchronized(this) {
      uiThread +=
        sum(
          FrameMetrics.INPUT_HANDLING_DURATION,
          FrameMetrics.ANIMATION_DURATION,
          FrameMetrics.LAYOUT_MEASURE_DURATION,
          FrameMetrics.DRAW_DURATION,
        )
      renderThread += sum(FrameMetrics.SYNC_DURATION, FrameMetrics.COMMAND_ISSUE_DURATION)
      swap += metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
      if (Build.VERSION.SDK_INT >= 31) gpu += metrics.getMetric(FrameMetrics.GPU_DURATION)
    }
  }
}

/**
 * Records real review lines into a display list with each text path, the way `CodeView` does: 50
 * lines per frame, cut to [COLUMNS] columns. "Repeat" redraws the same lines every frame, so
 * Minikin's layout cache hits; "fresh" draws new lines every frame, as when scrubbing.
 */
@RequiresApi(29)
private fun benchmarkTextPaths(state: CodeViewState, fontSizePx: Float) {
  val rows = state.rows
  val step = rows.rowCount / FRAMES
  val fresh =
    List(FRAMES) { frame ->
      rows.rows(frame * step + step / 2, LINES).map { it.text.take(COLUMNS) }
    }
  val node = RenderNode("text-bench").apply { setPosition(0, 0, 1080, 2400) }
  val scope = CanvasDrawScope()
  val saved = androidTextPath
  // DrawText first, so the fresh pass can't reuse layouts the glyph pass left in Minikin's cache.
  for (path in listOf(AndroidTextPath.DrawText, AndroidTextPath.GlyphGrid)) {
    androidTextPath = path
    val painter = TextPainter(fontSizePx)
    for ((name, frames) in listOf("fresh" to fresh, "repeat" to List(FRAMES) { fresh[0] })) {
      val nanos = frames.map { lines ->
        val start = System.nanoTime()
        val canvas = node.beginRecording()
        scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(canvas), Size(1080f, 2400f)) {
          with(painter) {
            lines.forEachIndexed { i, line ->
              drawText(line, 0f, i * painter.lineHeight, Color.White)
            }
          }
        }
        node.endRecording()
        System.nanoTime() - start
      }
      Log.i(TAG, "== text: $path, $name lines, $LINES per frame")
      Log.i(TAG, "  record CPU      ${summary(nanos)}")
    }
  }
  androidTextPath = saved
}

private const val FRAMES = 600
private const val LINES = 50
private const val COLUMNS = 40
