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
 * [pr], jumps to a thread from the side pane, drags across two line numbers, writes a comment, adds
 * it to a pending review, and opens Finish review. With [review] as well, it shows a PR whose
 * review is requested.
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
  width: Int = 1600,
  height: Int = 1000,
  scale: Float = 1.25f,
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
  fun open(link: String, block: Driver.() -> Unit) {
    val stack = runBlocking { checkNotNull(graph.changesBackStack(link)) { "Not a PR: $link" } }
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

  open(pr) {
    waitFor(90.seconds, "the review to load") { text(Regex("""\d+ of \d+ files viewed""")) != null }
    pump(2.seconds)
    shot("desktop-three-pane")
    // A thread from the side pane, then two lines above it.
    text(Regex(""".* on .*\.\w+:\d+.*"""))?.let { click(it.center) }
    pump(2.seconds)
    shot("desktop-thread")
    // Then a file with no threads, from the tree: its header, its first hunk, then code.
    text(Regex("url_rules.cpp"))?.let { click(it.center) }
    pump(1.seconds)
    val diff = checkNotNull(tag("diff")) { "no diff" }
    val row = density.codeRowHeight()
    val gutter = diff.left + row
    drag(Offset(gutter, diff.top + row * 4.5f), Offset(gutter, diff.top + row * 6.5f))
    waitFor(10.seconds, "the composer") { text(Regex("Lines? .* of .*")) != null }
    text(Regex("Suggest change"))?.let { click(it.center) }
    pump(1.seconds)
    editable(within = diff)?.let { field ->
      val suggestion = field.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
      setText(field, "This reads clearer as one call.\n$suggestion")
    }
    pump(1.seconds)
    shot("desktop-comment")
    text(Regex("Start review|Add to review"))?.let { click(it.center) }
    waitFor(10.seconds, "the pending review") { text(Regex("1 pending comment")) != null }
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

  /** A text field inside [within], like the composer in the diff. */
  fun editable(within: Rect): SemanticsNode? = node {
    it.config.getOrNull(SemanticsProperties.EditableText) != null &&
      within.contains(it.boundsInRoot.center)
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
