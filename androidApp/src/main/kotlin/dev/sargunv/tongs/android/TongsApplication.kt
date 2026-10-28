package dev.sargunv.tongs.android

import android.app.Application
import dev.sargunv.tongs.app.AppGraph
import dev.sargunv.tongs.app.createAppGraph
import dev.sargunv.tongs.core.data.AppEnvironment
import dev.sargunv.tongs.core.data.KeystoreTokenStore

/** Holds the app graph, which lives as long as the process. */
class TongsApplication : Application() {
  lateinit var graph: AppGraph
    private set

  override fun onCreate() {
    super.onCreate()
    graph = createAppGraph(AndroidEnvironment(this))
  }
}

/**
 * The database and settings in the app's files dir, git object stores in its cache dir, and tokens
 * encrypted with a Keystore key in the no-backup dir, since the key doesn't move with a backup.
 */
private class AndroidEnvironment(app: Application) : AppEnvironment {
  override val dataDir: String = app.filesDir.path
  override val cacheDir: String = app.cacheDir.path
  override val tokenStore = KeystoreTokenStore(app.noBackupFilesDir.resolve("tokens"))
  override val isDesktop = false
}
