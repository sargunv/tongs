package dev.sargunv.tongs.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.sargunv.tongs.app.TongsApp
import dev.sargunv.tongs.app.createAppGraph
import dev.sargunv.tongs.core.data.AppEnvironment
import dev.sargunv.tongs.core.data.DraftEntity
import dev.sargunv.tongs.core.data.DraftKey
import dev.sargunv.tongs.core.data.InMemoryTokenStore
import dev.sargunv.tongs.core.data.openDatabase
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.RepoRef
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking

/**
 * Renders threads offscreen into [dir] with real data, for reviewing the thread screen: each of
 * [threads] (`owner/repo#number`) beside the inbox, and the first with its composer open. Runs in a
 * throwaway data dir with the token in memory, so it touches neither the app's data nor the
 * keychain. Notifications from private repos are dropped before rendering, so the shots are safe to
 * share; pass only public threads.
 */
fun renderThreadScreenshots(
  dir: File,
  threads: List<String>,
  width: Int = 1400,
  height: Int = 900,
  scale: Float = 1.25f,
) {
  dir.mkdirs()
  val data = Files.createTempDirectory("tongs-thread-shots").toFile()
  val desktop = DesktopEnvironment()
  val environment =
    object : AppEnvironment {
      override val dataDir = data.path
      override val cacheDir = desktop.cacheDir
      override val tokenStore = InMemoryTokenStore()
      override val isDesktop = true
    }
  val graph = createAppGraph(environment)
  val db = openDatabase("${data.path}/tongs.db")
  val account = runBlocking {
    val token = checkNotNull(desktop.localGitHubToken()) { "Sign in to `gh`, or set GH_TOKEN" }
    val account = graph.accounts.signIn(Instance.GitHubDotCom, token).account.id.value
    graph.inboxSync.refresh()
    // Keep only public repos' notifications in the list beside the thread.
    val rows = db.inbox().threads(account)
    val private =
      rows
        .map { RepoRef(it.repoOwner, it.repoName) }
        .distinct()
        .filter { repo ->
          val body =
            graph.httpClient
              .get("https://api.github.com/repos/$repo") {
                header("Authorization", "Bearer $token")
              }
              .bodyAsText()
          !body.contains("\"private\":false")
        }
        .toSet()
    db
      .inbox()
      .delete(account, rows.filter { RepoRef(it.repoOwner, it.repoName) in private }.map { it.id })
    println("Dropped notifications from ${private.size} private or unknown repos")
    account
  }
  val routes = threads.map { ref ->
    val match = requireNotNull(Regex("""([\w.-]+)/([\w.-]+)#(\d+)""").matchEntire(ref)) { ref }
    val (owner, name, number) = match.destructured
    val kind = runBlocking {
      val body =
        graph.httpClient
          .get("https://api.github.com/repos/$owner/$name/issues/$number") {
            header("Authorization", "Bearer ${desktop.localGitHubToken()}")
          }
          .bodyAsText()
      if (body.contains("\"pull_request\"")) "PullRequest" else "Issue"
    }
    Route.Thread(account, RepoRef(owner, name), kind, number.toInt())
  }
  val shots = buildList {
    for (route in routes) {
      val slug = "${route.repo.name}-${route.number}"
      add(Triple("desktop-thread-$slug", listOf(Route.Inbox, route), 12.seconds))
    }
    routes.firstOrNull()?.let { route ->
      runBlocking {
        val key = DraftKey(account, route.repo, route.number!!)
        db
          .drafts()
          .insert(
            DraftEntity(
              accountId = account,
              key = key.value,
              repoOwner = route.repo.owner,
              repoName = route.repo.name,
              number = route.number!!,
              slot = key.slot,
              body = DRAFT,
              updatedAt = 0,
              destination = dev.sargunv.tongs.core.data.DraftDestination.ThreadReply,
            ),
          )
      }
      add(
        Triple("desktop-composer", listOf(Route.Inbox, route, Route.ThreadReply(route)), 8.seconds),
      )
    }
  }
  for ((name, stack, wait) in shots) render(dir.resolve("$name.png"), width, height, scale, wait) {
    TongsApp(graph, stack)
  }
  db.close()
  data.deleteRecursively()
  System.exit(0)
}

private const val DRAFT =
  "Thanks for tracking this down! Two thoughts:\n\n" +
    "- Could we cover the **no heading** case in the iOS test too?\n" +
    "- `HeadingCondition.Unavailable` reads well; let's mention it in the changelog.\n\n" +
    "Otherwise this looks good to me."

private fun render(
  file: File,
  width: Int,
  height: Int,
  scale: Float,
  wait: Duration,
  content: @androidx.compose.runtime.Composable () -> Unit,
) {
  fun <T> onUi(block: () -> T): T {
    var result: T? = null
    SwingUtilities.invokeAndWait { result = block() }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }
  val scene = onUi { ImageComposeScene(width, height, density = Density(scale), content = content) }
  val start = TimeSource.Monotonic.markNow()
  while (start.elapsedNow() < wait) {
    onUi { scene.render(start.elapsedNow().inWholeNanoseconds) }
    Thread.sleep(50)
  }
  val image = onUi { scene.render(start.elapsedNow().inWholeNanoseconds) }
  file.writeBytes(checkNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)).bytes)
  onUi { scene.close() }
  println("Wrote $file")
}
