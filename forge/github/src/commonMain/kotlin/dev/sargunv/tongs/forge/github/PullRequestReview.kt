package dev.sargunv.tongs.forge.github

import dev.sargunv.tongs.engine.FetchOptions
import dev.sargunv.tongs.engine.FetchSession
import dev.sargunv.tongs.engine.ReviewLoader
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Below this repo size, fetch every tree with the commits in one round trip rather than walking a
 * level at a time. Measured on 2026-10-28, all of a revision's trees cost 145 KiB for
 * maplibre-native-ffi (20 MB repo), 2.1 MiB and 0.6 s for rust-lang/rust (1 GB), 3 MiB and 1.6 s
 * for torvalds/linux (6.4 GB), and 17 MiB and 37 s for chromium/chromium (68 GB).
 */
private const val WHOLE_TREES_MAX_REPO_KIB = 2_000_000L

/**
 * Loads a PR's review from git objects. The PR's base and head come from the REST API; the fetch
 * then starts at once on the assumption that the base is the merge base, and the review is shown
 * when GitHub's compare API confirms it, or restarted from the real merge base if not.
 *
 * [onLoader] receives the loader as soon as it exists, so the UI can collect its snapshots. The
 * caller closes it.
 */
suspend fun loadPullRequestReview(
  github: GitHub,
  ref: PullRequestRef,
  openSession: (FetchOptions) -> FetchSession,
  onLoader: (ReviewLoader) -> Unit,
  log: (String) -> Unit = {},
) = coroutineScope {
  log("requesting PR metadata")
  // Open the git connection meanwhile, so the first fetch doesn't pay for the handshake.
  launch { github.warmUp(ref.owner, ref.repo) }
  val pr = github.pullRequest(ref)
  log(
    "PR metadata: +${pr.additions} -${pr.deletions} in ${pr.changedFiles} files, " +
      "repo ${pr.repoSizeKib / 1024} MiB",
  )
  val wholeTrees = pr.repoSizeKib < WHOLE_TREES_MAX_REPO_KIB
  val session = openSession(FetchOptions(wholeTreesFromDepth = if (wholeTrees) 0 else null))
  val loader = ReviewLoader(session, github.git(ref.owner, ref.repo), log)
  onLoader(loader)
  loader.startReview(pr.baseSha, pr.headSha, speculative = true)
  val compare = launch {
    val mergeBase = github.mergeBase(ref, pr.baseSha, pr.headSha)
    log("compare API: merge base is ${if (mergeBase == pr.baseSha) "the base" else mergeBase}")
    if (mergeBase == pr.baseSha) loader.confirm() else loader.startReview(mergeBase, pr.headSha)
  }
  loader.run()
  // The review is confirmed by now, maybe from the fetched history alone.
  compare.cancel()
}
