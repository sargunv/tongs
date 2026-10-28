package dev.sargunv.tongs.core.data

/** What the platform shell hands the app graph: where files go, and where secrets go. */
interface AppEnvironment {
  /** Persistent app data: the database and settings. */
  val dataDir: String

  /** Data the app can rebuild, like git object stores. */
  val cacheDir: String

  val tokenStore: TokenStore

  /** True on desktop, where the UI is denser and keyboard-driven. */
  val isDesktop: Boolean

  /**
   * A GitHub token the machine already has, from the GitHub CLI, offered at sign-in as a developer
   * shortcut. Null where there's no CLI.
   */
  suspend fun localGitHubToken(): String? = null
}

/**
 * Account tokens, kept out of the database. Android encrypts them with a Keystore key; desktop uses
 * the OS keychain. Calls may block on IPC, so they're `suspend`.
 */
interface TokenStore {
  suspend fun get(accountId: Long): String?

  suspend fun put(accountId: Long, token: String)

  suspend fun remove(accountId: Long)
}

/** Keeps tokens in memory, for tests. */
class InMemoryTokenStore : TokenStore {
  private val tokens = mutableMapOf<Long, String>()

  override suspend fun get(accountId: Long) = tokens[accountId]

  override suspend fun put(accountId: Long, token: String) {
    tokens[accountId] = token
  }

  override suspend fun remove(accountId: Long) {
    tokens.remove(accountId)
  }
}
