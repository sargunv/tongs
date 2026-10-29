package dev.sargunv.tongs.core.data

import dev.sargunv.tongs.engine.Highlighter
import dev.sargunv.tongs.engine.NativeFetchSession
import dev.sargunv.tongs.engine.ReviewLoader
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Loads PR diffs from git objects, through the account's forge, into a per-repo object store under
 * the app's cache (`<cache>/<host>/<owner>/<repo>/objects`), the one the spike entry points use.
 * Objects persist there, so a PR opened again loads from disk.
 */
@SingleIn(AppScope::class)
@Inject
class PullRequestDiffs(
  private val accounts: AccountRepository,
  private val clients: ForgeClients,
  private val environment: AppEnvironment,
  private val highlighter: Highlighter,
) {
  /**
   * Loads [key]'s diff, passing its loader to [onLoader] as soon as it exists, and returns once
   * every request has settled. The caller closes the loader.
   */
  suspend fun load(
    key: PullRequestKey,
    onLoader: (ReviewLoader) -> Unit,
    log: (String) -> Unit = {},
  ) {
    val account = accounts.account(key.accountId) ?: error("This account is signed out")
    val reviews = clients.client(key.accountId)?.reviews ?: error("This forge can't load reviews")
    val store =
      "${environment.cacheDir}/${account.instance.host}/${key.repo.owner}/${key.repo.name}/objects"
    reviews.loadPullRequest(
      key.repo,
      key.number,
      openSession = { NativeFetchSession.open(store, it, highlighter) },
      onLoader = onLoader,
      log = log,
    )
  }
}
