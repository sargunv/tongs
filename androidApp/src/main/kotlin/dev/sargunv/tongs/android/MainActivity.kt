package dev.sargunv.tongs.android

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import dev.sargunv.tongs.codeview.AndroidTextPath
import dev.sargunv.tongs.codeview.CodeView
import dev.sargunv.tongs.codeview.CodeViewColors
import dev.sargunv.tongs.codeview.androidTextPath
import dev.sargunv.tongs.codeview.rememberCodeViewState
import dev.sargunv.tongs.engine.Review
import java.io.File
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val TAG = "Tongs"

/**
 * Spike: opens a review from an object store pushed with adb (see AGENTS.md) and renders it.
 *
 * Intent extras: `store`, `old`, `new` (defaults to maplibre/maplibre-native-ffi#760 in the app's
 * external files dir), `row` to start scrolled to a row, `textPath` (`GlyphGrid` or `DrawText`),
 * and `bench` to run the text and scrolling benchmarks, log timings, and exit.
 */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // The code view is always dark, so ask for light system bar icons.
    enableEdgeToEdge(
      SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )
    val storeDir =
      intent.getStringExtra("store") ?: File(getExternalFilesDir(null), "tongs-spike-store").path
    val oldCommit = intent.getStringExtra("old") ?: "d269e1bdf76667607c2783521d4d2eb119cd76c0"
    val newCommit = intent.getStringExtra("new") ?: "3b56ef91641085ad555def7a4b408c87f131fd8d"
    val startRow = intent.getIntExtra("row", 0)
    val bench = intent.getBooleanExtra("bench", false)
    intent.getStringExtra("textPath")?.let { androidTextPath = AndroidTextPath.valueOf(it) }
    if (Build.VERSION.SDK_INT < 31) androidTextPath = AndroidTextPath.DrawText
    Log.i(TAG, "Text path: $androidTextPath")

    setContent {
      var review by remember { mutableStateOf<Result<Review>?>(null) }
      LaunchedEffect(Unit) {
        val opened = TimeSource.Monotonic.markNow()
        review =
          withContext(Dispatchers.IO) {
            runCatching { Review.open(storeDir, oldCommit, newCommit) }
          }
        review
          ?.onSuccess {
            Log.i(
              TAG,
              "Opened review: ${it.fileCount} files, ${it.rowCount} rows in ${opened.elapsedNow()}",
            )
          }
          ?.onFailure { Log.e(TAG, "Couldn't open $storeDir", it) }
      }
      val opened = review?.getOrNull()
      DisposableEffect(opened) { onDispose { opened?.close() } }

      val colors = remember { CodeViewColors() }
      // CodeView's font size.
      val fontSizePx = with(LocalDensity.current) { 13.sp.toPx() }
      Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
        review
          ?.onFailure {
            BasicText("Couldn't open $storeDir: ${it.message}", style = TextStyle(colors.text))
          }
          ?.onSuccess {
            val state = rememberCodeViewState(it)
            // CodeView draws a partial row past its bottom edge; keep it off the nav bar.
            CodeView(state, colors = colors)
            LaunchedEffect(state) {
              state.scrollToRow(startRow)
              if (bench) {
                runBenchmarks(state, window, fontSizePx)
                finish()
              }
            }
          }
      }
    }
  }
}
