package dev.sargunv.tongs.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.DecoratedWindowScope
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBarPlacement
import dev.nucleusframework.window.WindowAppearance
import dev.nucleusframework.window.WindowAppearanceMode
import dev.nucleusframework.window.WindowBackground
import dev.nucleusframework.window.WindowControls
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.utils.linux.rememberLinuxButtonLayout
import dev.nucleusframework.window.windowDragArea
import dev.sargunv.tongs.app.TongsApp
import dev.sargunv.tongs.app.changesBackStack
import dev.sargunv.tongs.app.createAppGraph
import dev.sargunv.tongs.core.data.AppEnvironment
import dev.sargunv.tongs.core.data.KeychainTokenStore
import dev.sargunv.tongs.core.ui.TongsTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The app in a Nucleus window, with [open]'s changes open if it's a PR (`owner/repo#N` or a link).
 *
 * Nucleus's Tao backend draws natively on Wayland, so the window is sharp at fractional scales,
 * where an AWT window goes through XWayland and is scaled up blurry. The title bar overlays the
 * app; its height is added to the safe area so content and controls stay clear of it.
 */
fun runApp(args: Array<String>, open: String? = null) {
  val graph = createAppGraph(DesktopEnvironment())
  val stack = open?.let { runBlocking { graph.changesBackStack(it) } }
  nucleusApplication(args) {
    // The same theme as the app's, for the window's own chrome.
    TongsTheme {
      val surface = MaterialTheme.colorScheme.surface
      val isDark = surface.luminance() < DARK_LUMINANCE
      val back = remember { EscapeBackInput() }
      NucleusDecoratedWindowTheme(isDark = isDark) {
        DecoratedWindow(
          onCloseRequest = ::exitApplication,
          title = "Tongs",
          state = rememberWindowState(size = DpSize(1280.dp, 860.dp)),
          minimumSize = DpSize(360.dp, 480.dp),
          // Escape is back in Compose's own window, but Tao doesn't map it, so do as Compose does:
          // keys nothing in the app took go to the back dispatcher.
          onKeyEvent = back::onKeyEvent,
        ) {
          WindowAppearance(if (isDark) WindowAppearanceMode.Dark else WindowAppearanceMode.Light)
          WindowBackground(surface)
          WindowScaffold(
            titleBar = { TitleBar() },
            titleBarPlacement = TitleBarPlacement.Overlay(),
          ) { chromePadding ->
            BackInput(back)
            // Tao reports no window insets, so add the title bar to the safe area.
            val safeInsets =
              WindowInsets.safeDrawing.union(
                WindowInsets(top = chromePadding.calculateTopPadding()),
              )
            Box(Modifier.fillMaxSize().windowInsetsPadding(safeInsets)) { TongsApp(graph, stack) }
          }
        }
      }
    }
  }
}

/** Escape as back, as Compose's own window has it. */
private class EscapeBackInput : NavigationEventInput() {
  fun onKeyEvent(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown || event.key != Key.Escape) return false
    dispatchOnBackCompleted()
    return true
  }
}

/** Sends [input]'s back gestures to the window's dispatcher, which `NavDisplay` listens to. */
@Composable
private fun BackInput(input: EscapeBackInput) {
  val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher ?: return
  DisposableEffect(dispatcher, input) {
    dispatcher.addInput(input)
    onDispose { dispatcher.removeInput(input) }
  }
}

private val osName = System.getProperty("os.name").orEmpty()
private val isMacOs = osName.startsWith("Mac")
private val isLinux = osName.startsWith("Linux")

// A surface darker than this is a dark theme's, as Nucleus's Material windows judge it.
private const val DARK_LUMINANCE = 0.5f

private val TitleBarHeight = 32.dp

/** A strip to drag the window by, with its controls on the side the desktop puts them. */
@Composable
private fun DecoratedWindowScope.TitleBar() {
  Box(Modifier.fillMaxWidth().height(TitleBarHeight).windowDragArea()) {
    // macOS draws its own traffic lights.
    if (isMacOs) return@Box
    // GNOME's button layout can change while the app runs.
    val onRight = if (isLinux) rememberLinuxButtonLayout().controlsOnRight else true
    WindowControls(
      Modifier.align(if (onRight) AbsoluteAlignment.TopRight else AbsoluteAlignment.TopLeft),
    )
  }
}

/**
 * Files under the XDG base directories: data in `~/.local/share/tongs`, and caches, including the
 * per-repo git object stores, in `~/.cache/tongs`, where `--pr` keeps them too.
 */
class DesktopEnvironment : AppEnvironment {
  override val dataDir = xdgDir("XDG_DATA_HOME", ".local/share").resolve("tongs").ensure().path
  override val cacheDir = xdgDir("XDG_CACHE_HOME", ".cache").resolve("tongs").ensure().path
  override val tokenStore = KeychainTokenStore(File(dataDir))
  override val isDesktop = true

  /** `GH_TOKEN`, or the GitHub CLI's token if it's signed in. */
  override suspend fun localGitHubToken(): String? =
    withContext(Dispatchers.IO) {
      System.getenv("GH_TOKEN")?.takeIf { it.isNotBlank() }
        ?: runCatching {
          val process = ProcessBuilder("gh", "auth", "token").start()
          process.inputStream.bufferedReader().readText().trim().takeIf { process.waitFor() == 0 }
        }
          .getOrNull()
          ?.takeIf { it.isNotBlank() }
    }
}

private fun xdgDir(variable: String, fallback: String): File =
  System.getenv(variable)?.takeIf { it.isNotBlank() }?.let(::File)
    ?: File(System.getProperty("user.home"), fallback)

private fun File.ensure() = apply { mkdirs() }
