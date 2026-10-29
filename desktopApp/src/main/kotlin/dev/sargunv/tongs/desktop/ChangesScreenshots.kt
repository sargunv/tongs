package dev.sargunv.tongs.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import dev.sargunv.tongs.app.TongsApp
import dev.sargunv.tongs.app.changesBackStack
import dev.sargunv.tongs.app.createAppGraph
import dev.sargunv.tongs.codeview.codeRowHeight
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.forge.api.ForgeKind
import dev.sargunv.tongs.forge.api.Instance
import java.io.File
import javax.swing.SwingUtilities
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat

/**
 * Renders the PR changes screen offscreen into [dir], driving it like a reader would: it opens
 * [pr], jumps to a thread from the side pane, drags across two line numbers, writes a suggestion,
 * and adds it to a pending review, on desktop and at a folded and an unfolded phone's widths. Then
 * it opens [threads], a PR with review threads, and writes a reply to one, and [resolved], to show
 * a resolved thread folded inline. With [review] as well, it shows a PR whose review is requested.
 *
 * Signs in with the GitHub CLI's token if there's no account. With [apiUrl], the account's API goes
 * there instead of api.github.com: point it at a proxy that fakes mutations, and [mutate] also
 * marks a file viewed and submits the review. Without one it never sends a mutation.
 */
fun renderChangesScreenshots(
  dir: File,
  pr: String,
  review: String?,
  apiUrl: String?,
  mutate: Boolean,
  threads: String = "maplibre/maplibre-compose#1867",
  resolved: String = "maplibre/maplibre-native#4231",
  desktopWidth: Int = 1600,
  desktopHeight: Int = 1000,
  desktopScale: Float = 1.25f,
) {
  require(!mutate || apiUrl != null) { "--mutate needs --api-url, so nothing reaches GitHub" }
  dir.mkdirs()
  val environment = DesktopEnvironment()
  val graph = createAppGraph(environment)
  runBlocking {
    if (graph.accounts.accounts.first().isEmpty()) {
      val token = checkNotNull(environment.localGitHubToken()) { "Sign in, or sign in to `gh`" }
      val instance =
        apiUrl?.let { Instance(ForgeKind.GitHub, "https://github.com", it) }
          ?: Instance.GitHubDotCom
      graph.accounts.signIn(instance, token)
    }
  }
  fun open(
    link: String,
    width: Int = desktopWidth,
    height: Int = desktopHeight,
    scale: Float = desktopScale,
    block: Driver.() -> Unit,
  ) {
    val stack = runBlocking { checkNotNull(graph.changesBackStack(link)) { "Not a PR: $link" } }
    val route = stack.filterIsInstance<Route.PullRequestChanges>().single()
    val effective = runBlocking {
      checkNotNull(graph.accounts.account(route.accountId)).instance.apiUrl
    }
    requireScreenshotMutationEndpoint(mutate, apiUrl, effective)
    val density = Density(scale)
    val driver =
      Driver(
        onUi { ImageComposeScene(width, height, density) { TongsApp(graph, stack) } },
        density,
        dir,
      )
    try {
      driver.block()
    } finally {
      onUi { driver.scene.close() }
    }
  }

  /** Drags across two line numbers in [file], picked from the tree, and writes a suggestion. */
  fun Driver.comment(file: Regex, tree: Boolean = true) {
    if (tree) {
      val filter =
        checkNotNull(node { it.config.getOrNull(SemanticsProperties.EditableText) != null })
      setText(filter, "url_rules")
      pump(0.5.seconds)
      click(checkNotNull(text(file)).center)
      setText(filter, "")
    } else jumpFromSheet(file)
    pump(1.seconds)
    val diff = checkNotNull(tag("diff")) { "no diff" }
    val row = density.codeRowHeight()
    val gutter = diff.left + row
    drag(Offset(gutter, diff.top + row * 4.5f), Offset(gutter, diff.top + row * 6.5f))
    waitFor(10.seconds, "the composer") { text(Regex("Lines? .* of .*")) != null }
    editable()?.let { setText(it, "") }
    text(Regex("Suggest change"))?.let { click(it.center) }
    pump(1.seconds)
    editable()?.let { field ->
      val suggestion = field.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
      setText(field, "This reads clearer as one call.\n\n$suggestion")
    }
    pump(1.seconds)
  }

  open(pr) {
    waitFor(90.seconds, "the review to load") { text(Regex("""\d+ of \d+ files viewed""")) != null }
    pump(2.seconds)
    shot("desktop-three-pane")
    // A thread from the side pane, then two lines above it.
    text(Regex(""".* on .*\.\w+:\d+.*"""))?.let { click(it.center) }
    pump(2.seconds)
    shot("desktop-thread")
    // Then a file with no threads, from the tree: its header, its first hunk, then code.
    comment(Regex("url_rules.cpp"))
    shot("desktop-comment")
    text(Regex("Start review|Add to review"))?.let { click(it.center) }
    waitFor(10.seconds, "the pending review") { text(Regex("1 pending comment.*")) != null }
    pump(1.seconds)
    shot("desktop-pending")
    if (mutate) {
      // The fake server answers these; see the PR's notes.
      text(Regex("Submit review"))?.let { click(it.center) }
      waitFor(10.seconds, "the submission") {
        text(Regex("Review submitted|Couldn't submit.*")) != null
      }
      pump(1.seconds)
      shot("desktop-submitted")
    }
  }

  // A folded phone's width, about 360 dp, and an unfolded one's, about 700 dp.
  open(pr, width = 900, height = 1900, scale = 2.5f) {
    waitFor(90.seconds, "the review to load") { text(Regex(".* files · .*")) != null }
    pump(2.seconds)
    comment(Regex(".*url_rules.cpp.*"), tree = false)
    shot("folded-comment")
  }
  open(pr, width = 1750, height = 1950, scale = 2.5f) {
    waitFor(90.seconds, "the review to load") { text(Regex("""\d+ of \d+ files viewed""")) != null }
    pump(2.seconds)
    comment(Regex("url_rules.cpp"))
    shot("unfolded-comment")
  }

  open(threads) {
    waitFor(90.seconds, "the review to load") { text(Regex("""\d+ of \d+ files viewed""")) != null }
    waitFor(30.seconds, "its threads") { text(Regex("""\d+ threads?""")) != null }
    pump(3.seconds)
    // Open a discussion so its Markdown and actions are visible, even when offscreen in the diff.
    (text(Regex("grpetitle-apps on .*")) ?: text(Regex(".* on .*")))?.let { click(it.center) }
    pump(2.seconds)
    shot("desktop-markdown")
    node { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "Reply" } }
      ?.let { click(it.boundsInRoot.center) }
    waitFor(10.seconds, "the reply composer") { text(Regex("Reply on .*")) != null }
    editable()?.let {
      setText(it, "Good catch: I'll make it conditional, with an example for each.")
    }
    pump(1.seconds)
    shot("desktop-reply")
    if (mutate) {
      // Posts at once, with no review pending; the fake server answers.
      node { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Reply" } &&
          n.boundsInRoot.left > desktopWidth * 0.7f &&
          n.boundsInRoot.top > desktopHeight * 0.7f
      }
        ?.let { click(it.boundsInRoot.center) }
      waitFor(10.seconds, "the reply to send") { text(Regex("Reply on .*")) == null }
      pump(3.seconds)
      shot("desktop-replied")
      text(Regex("Resolve"))?.let { click(it.center) }
      pump(2.seconds)
      shot("desktop-resolved")
      shot("desktop-resolved-collapsed")
    }
  }

  open(resolved) {
    waitFor(120.seconds, "the review to load") {
      text(Regex("""\d+ of \d+ files viewed""")) != null
    }
    waitFor(30.seconds, "its threads") { text(Regex("""\d+ threads?""")) != null }
    pump(2.seconds)
    text(Regex(""".* on .* · resolved.*"""))?.let { click(it.center) }
    pump(3.seconds)
    shot("desktop-resolved-reference")
  }

  if (review != null) {
    open(review) {
      waitFor(120.seconds, "the review to load") {
        text(Regex("""\d+ of \d+ files viewed""")) != null
      }
      pump(2.seconds)
      if (mutate) {
        // Mark the first file in the tree viewed: it folds away, and the tree checks it.
        node { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
          ?.let { click(it.boundsInRoot.center) }
        pump(2.seconds)
      }
      shot("desktop-review-requested")
    }
  }
  System.exit(0)
}

/** Compose and lifecycle expect the UI thread, which on desktop is Swing's. */
private fun <T> onUi(block: () -> T): T {
  var result: Result<T>? = null
  SwingUtilities.invokeAndWait { result = runCatching(block) }
  return result!!.getOrThrow()
}

private class Driver(val scene: ImageComposeScene, val density: Density, val dir: File) {
  private val start = TimeSource.Monotonic.markNow()

  private fun now() = start.elapsedNow().inWholeNanoseconds

  /** Renders frames in real time, so loads and animations land. */
  fun pump(duration: Duration) {
    val until = start.elapsedNow() + duration
    while (start.elapsedNow() < until) {
      onUi { scene.render(now()) }
      Thread.sleep(32)
    }
  }

  fun waitFor(timeout: Duration, what: String, done: () -> Boolean) {
    val until = start.elapsedNow() + timeout
    while (
      !onUi {
        scene.render(now())
        done()
      }
    ) {
      check(start.elapsedNow() < until) { "Timed out waiting for $what" }
      Thread.sleep(50)
    }
  }

  private fun nodes(): List<SemanticsNode> {
    val out = mutableListOf<SemanticsNode>()
    fun walk(node: SemanticsNode) {
      out += node
      node.children.forEach(::walk)
    }
    scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
    return out
  }

  fun node(predicate: (SemanticsNode) -> Boolean): SemanticsNode? = nodes().firstOrNull(predicate)

  fun text(pattern: Regex): SemanticsNode? = node { node ->
    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { pattern.matches(it.text) }
  }

  fun tag(tag: String): Rect? = node {
    it.config.getOrNull(SemanticsProperties.TestTag) == tag
  }
    ?.boundsInRoot

  /** The composer's text field. */
  fun editable(): SemanticsNode? = node {
    it.config.getOrNull(SemanticsProperties.TestTag) == "composer-body"
  }

  /** Picks [file] from the tree's sheet, as when folded. */
  fun jumpFromSheet(file: Regex) {
    node { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Files") }
      ?.let { click(it.boundsInRoot.center) }
    pump(1.seconds)
    text(Regex("Filter.*"))
    node { it.config.getOrNull(SemanticsProperties.EditableText) != null }
      ?.let { setText(it, "url_rules") }
    pump(1.seconds)
    node { n ->
      n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { file.matches(it.text) } &&
        n.config.getOrNull(SemanticsProperties.EditableText) == null
    }
      ?.let { click(it.boundsInRoot.center) }
    pump(1.seconds)
  }

  fun setText(node: SemanticsNode, text: String) = onUi {
    node.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(text))
  }

  private fun mouse(type: PointerEventType, at: Offset, pressed: Boolean) = onUi {
    scene.sendPointerEvent(
      eventType = type,
      position = at,
      timeMillis = now() / 1_000_000,
      type = PointerType.Mouse,
      buttons = PointerButtons(isPrimaryPressed = pressed),
      button = PointerButton.Primary.takeIf { type != PointerEventType.Move },
    )
    scene.render(now())
  }

  fun click(at: Offset) {
    mouse(PointerEventType.Move, at, false)
    mouse(PointerEventType.Press, at, true)
    mouse(PointerEventType.Release, at, false)
    pump(0.3.seconds)
  }

  fun drag(from: Offset, to: Offset) {
    mouse(PointerEventType.Move, from, false)
    mouse(PointerEventType.Press, from, true)
    for (i in 1..6) mouse(PointerEventType.Move, from + (to - from) * (i / 6f), true)
    mouse(PointerEventType.Release, to, false)
    pump(0.5.seconds)
  }

  fun shot(name: String) {
    val image = onUi { scene.render(now()) }
    val file = dir.resolve("$name.png")
    file.writeBytes(checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
    println("Wrote $file")
  }
}

private val SemanticsNode.center: Offset
  get() = boundsInRoot.center

/** Validate the account actually picked by the navigation helper before it can send anything. */
internal fun requireScreenshotMutationEndpoint(
  mutate: Boolean,
  requested: String?,
  effective: String,
) {
  if (!mutate) return
  val endpoint = java.net.URI(effective)
  require(
    requested?.trimEnd('/') == effective.trimEnd('/') &&
      endpoint.host in setOf("127.0.0.1", "localhost", "[::1]", "::1"),
  ) {
    "Screenshot mutations require the selected account to use the requested loopback fake server"
  }
}
