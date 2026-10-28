package dev.sargunv.tongs.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dev.sargunv.tongs.app.TongsApp
import dev.sargunv.tongs.app.changesBackStack
import kotlinx.coroutines.launch

/**
 * The app. An `open` extra (`owner/repo#N`, or a PR link) starts on that PR's changes, until links
 * resolve to their own routes: `adb shell am start -n dev.sargunv.tongs/.android.MainActivity --es
 * open owner/repo#N`.
 */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val graph = (application as TongsApplication).graph
    val open = intent.getStringExtra("open")?.takeIf { savedInstanceState == null }
    if (open == null) {
      setContent { TongsApp(graph) }
      return
    }
    lifecycleScope.launch {
      val stack = graph.changesBackStack(open)
      setContent { TongsApp(graph, stack) }
    }
  }
}
