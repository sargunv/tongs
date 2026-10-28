package dev.sargunv.tongs.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.sargunv.tongs.app.AppGraph
import dev.sargunv.tongs.app.TongsApp
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.forge.api.RepoRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** The app. */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val graph = (application as TongsApplication).graph
    setContent { TongsApp(graph, initialBackStack = threadExtra(graph)) }
  }
}

/**
 * For development: `--es thread owner/repo#123` opens that issue or PR on the first account, over
 * the inbox.
 */
private fun ComponentActivity.threadExtra(graph: AppGraph): List<Route>? {
  val match =
    Regex("""([\w.-]+)/([\w.-]+)#(\d+)""")
      .matchEntire(intent.getStringExtra("thread") ?: return null) ?: return null
  val (owner, name, number) = match.destructured
  val account = runBlocking { graph.accounts.accounts.first().firstOrNull() } ?: return null
  val thread = Route.Thread(account.account.id.value, RepoRef(owner, name), "Issue", number.toInt())
  return listOf(Route.Inbox, thread)
}
