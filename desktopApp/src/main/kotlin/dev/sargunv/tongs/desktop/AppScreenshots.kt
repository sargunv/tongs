package dev.sargunv.tongs.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.sargunv.tongs.app.TongsApp
import dev.sargunv.tongs.app.createAppGraph
import dev.sargunv.tongs.core.data.InboxRow
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.RepoRef
import java.io.File
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat

/**
 * Renders the app's main screens offscreen into [dir], with the signed-in accounts and real data:
 * the inbox, the inbox with its first thread open, settings, and the first PR's changes. Syncs
 * first, and signs in with the GitHub CLI's token if there's no account. For reviewing UI changes
 * without clicking through a window.
 */
fun renderAppScreenshots(dir: File, width: Int = 1400, height: Int = 900, scale: Float = 1.25f) {
  dir.mkdirs()
  val environment = DesktopEnvironment()
  val graph = createAppGraph(environment)
  val rows = runBlocking {
    if (graph.accounts.accounts.first().isEmpty()) {
      val token = checkNotNull(environment.localGitHubToken()) { "Sign in, or sign in to `gh`" }
      graph.accounts.signIn(Instance.GitHubDotCom, token)
    }
    graph.inboxSync.refresh()
    graph.inbox.inbox().first()
  }
  val first = rows.firstOrNull()
  val pr = rows.firstOrNull { it.subjectType == "PullRequest" && it.subjectNumber != null }
  val shots = buildMap {
    put("inbox", listOf(Route.Inbox) to 3.seconds)
    if (first != null) put("inbox-thread", listOf(Route.Inbox, first.thread()) to 3.seconds)
    put("settings", listOf(Route.Settings) to 3.seconds)
    if (pr != null) {
      val changes =
        Route.PullRequestChanges(
          pr.accountId,
          RepoRef(pr.repoOwner, pr.repoName),
          pr.subjectNumber!!,
        )
      put("pr-changes", listOf(Route.Inbox, pr.thread(), changes) to 15.seconds)
    }
  }
  for ((name, shot) in shots) {
    val (stack, wait) = shot
    // Compose and lifecycle expect the UI thread, which on desktop is Swing's.
    fun <T> onUi(block: () -> T): T {
      var result: T? = null
      SwingUtilities.invokeAndWait { result = block() }
      @Suppress("UNCHECKED_CAST")
      return result as T
    }
    val scene = onUi {
      ImageComposeScene(width, height, density = Density(scale)) { TongsApp(graph, stack) }
    }
    val start = TimeSource.Monotonic.markNow()
    // Pump frames in real time, so database reads and network loads land.
    while (start.elapsedNow() < wait) {
      onUi { scene.render(start.elapsedNow().inWholeNanoseconds) }
      Thread.sleep(50)
    }
    val image = onUi { scene.render(start.elapsedNow().inWholeNanoseconds) }
    val file = dir.resolve("desktop-$name.png")
    file.writeBytes(checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
    onUi { scene.close() }
    println("Wrote $file")
  }
  System.exit(0)
}

private fun InboxRow.thread() =
  Route.Thread(accountId, RepoRef(repoOwner, repoName), subjectType, subjectNumber, id)
