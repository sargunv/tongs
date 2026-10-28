package dev.sargunv.tongs.android

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dev.sargunv.tongs.codeview.CodeViewColors
import dev.sargunv.tongs.engine.Highlighter
import dev.sargunv.tongs.engine.NativeFetchSession
import dev.sargunv.tongs.feature.review.PullRequestLoad
import dev.sargunv.tongs.feature.review.PullRequestReview
import dev.sargunv.tongs.forge.github.GitHub
import dev.sargunv.tongs.forge.github.PullRequestRef
import dev.sargunv.tongs.forge.github.gitHubHttpClient
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.first

internal const val TAG = "Tongs"

/** One per process: its cache is keyed by blob, so it serves every PR. */
private val highlighter by lazy { Highlighter() }

/**
 * Opens a GitHub PR, fetching it in-app and showing the review as it loads, like `desktopApp --pr`.
 * Objects persist in a per-repo store in the app's cache dir.
 *
 * The PR is a github.com PR link as the intent's data, or the `pr` extra (`owner/repo#N`), and
 * defaults to maplibre/maplibre-native-ffi#760. Other extras, for timing runs: `cold` to empty the
 * repo's store first, `jump` to scroll that fraction of the way in as soon as the headers show,
 * `highlight` (default true), `exitWhenLoaded`, and `bench` to run the scrolling benchmarks once
 * loaded, log timings under [TAG], and exit. Debuggable builds also take a GitHub `token`; without
 * one, public repos work within REST's unauthenticated limit.
 */
class MainActivity : ComponentActivity() {
  private val http = gitHubHttpClient()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val started = TimeSource.Monotonic.markNow()
    val log: (String) -> Unit = { message ->
      Log.i(TAG, "%6d ms  %s".format(started.elapsedNow().inWholeMilliseconds, message))
    }
    log("process started ${SystemClock.uptimeMillis() - Process.getStartUptimeMillis()} ms ago")
    // The code view is always dark, so ask for light system bar icons.
    enableEdgeToEdge(
      SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )
    val pr = intent.dataString ?: intent.getStringExtra("pr") ?: "maplibre/maplibre-native-ffi#760"
    val ref = requireNotNull(PullRequestRef.parse(pr)) { "expected owner/repo#number: $pr" }
    val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val token = intent.getStringExtra("token")?.takeIf { debuggable }
    val highlighter = highlighter.takeIf { intent.getBooleanExtra("highlight", true) }
    val bench = intent.getBooleanExtra("bench", false)
    val exitWhenLoaded = intent.getBooleanExtra("exitWhenLoaded", false)
    val jumpTo = intent.getFloatExtra("jump", -1f).takeIf { it >= 0 }?.toDouble()

    val objects = cacheDir.resolve("github.com/${ref.owner}/${ref.repo}/objects")
    if (intent.getBooleanExtra("cold", false)) objects.deleteRecursively()
    log("store: $objects, ${if (token == null) "no token" else "with token"}")
    val load =
      PullRequestLoad(GitHub(http, token), ref, log = log) {
        NativeFetchSession.open(objects.path, it, highlighter)
      }
    load.start(lifecycleScope)

    setContent {
      val colors = remember { CodeViewColors() }
      Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
        // CodeView draws a partial row past its bottom edge; the padding keeps it off the nav bar.
        PullRequestReview(load, colors = colors, jumpTo = jumpTo)
      }
      if (bench || exitWhenLoaded) {
        LaunchedEffect(Unit) {
          snapshotFlow { load.settled }.first { it }
          load.codeView?.takeIf { bench }?.let { runBenchmarks(it, window, highlighter) }
          finish()
        }
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    http.close()
  }
}
