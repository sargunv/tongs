package dev.sargunv.tongs.forge.api

import dev.sargunv.tongs.engine.FetchOptions
import dev.sargunv.tongs.engine.FetchSession
import dev.sargunv.tongs.engine.ReviewLoader
import dev.zacsweers.metro.MapKey

/**
 * Supports one [ForgeKind]. Adapters are registered in a map keyed by kind, so the data layer never
 * names a forge.
 */
interface ForgeAdapter {
  val kind: ForgeKind
  val auth: AuthProvider

  /** A client for [account], which signs requests with [token]. Close it when done. */
  fun client(account: Account, token: String): ForgeClient
}

/** One account's view of its forge. Parts the forge or account lacks are null. */
interface ForgeClient : AutoCloseable {
  val inbox: InboxSource?
  val inboxHydrator: InboxHydrator?
    get() = null

  val reviews: ReviewSource?

  /** Issue and PR conversations. */
  val threads: ThreadSource?
    get() = null
}

/** Loads PR changes from git objects; see `docs/design.md`'s code engine pipeline. */
interface ReviewSource {
  /**
   * Fetches a PR's review into a session opened with [openSession], and returns when it's fully
   * loaded. [onLoader] receives the loader as soon as it exists, so the UI can collect its
   * snapshots; the caller closes it.
   */
  suspend fun loadPullRequest(
    repo: RepoRef,
    number: Int,
    openSession: (FetchOptions) -> FetchSession,
    onLoader: (ReviewLoader) -> Unit,
    log: (String) -> Unit = {},
  )
}

/** Keys a [ForgeAdapter] in the adapter map: `@ContributesIntoMap(AppScope::class)`. */
@MapKey annotation class ForgeKindKey(val value: ForgeKind)
