package dev.sargunv.tongs.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.sargunv.tongs.app.TongsApp
import dev.sargunv.tongs.app.createAppGraph
import dev.sargunv.tongs.core.data.AppEnvironment
import dev.sargunv.tongs.core.data.KeychainTokenStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The app in a window. */
fun runApp() {
  val graph = createAppGraph(DesktopEnvironment())
  application {
    Window(
      onCloseRequest = ::exitApplication,
      title = "Tongs",
      state = rememberWindowState(width = 1280.dp, height = 860.dp),
    ) {
      TongsApp(graph)
    }
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
