package dev.sargunv.tongs.android

import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.withFrameNanos
import dev.sargunv.tongs.codeview.CodeViewState
import kotlinx.coroutines.delay

/** The same passes as `desktopApp --bench`. */
internal suspend fun runBenchmarks(state: CodeViewState, window: Window) {
  delay(1500)
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
    Log.i(TAG, "  GPU             ${summary(hwui.gpu)}")
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
      gpu += metrics.getMetric(FrameMetrics.GPU_DURATION)
    }
  }
}
