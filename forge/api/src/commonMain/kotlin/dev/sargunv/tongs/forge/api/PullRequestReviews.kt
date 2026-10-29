package dev.sargunv.tongs.forge.api

import dev.sargunv.tongs.engine.DiffSide
import kotlin.time.Instant

/**
 * Where a review comment points, in forge-neutral terms: lines [startLine] to [endLine] of [path]
 * at [commitOid], numbered on the given sides as the local diff numbers them. Most ranges sit on
 * one side; one that starts on removed lines and ends on added ones has [startSide] old and [side]
 * new. Each adapter lowers this to its forge's shape: GitHub's
 * `line`/`side`/`start_line`/`start_side`, Forgejo's `new_position`/`old_position`, GitLab's
 * `position`.
 */
data class ReviewAnchor(
  val commitOid: String,
  val path: String,
  val side: DiffSide,
  val startLine: Int,
  val endLine: Int,
  val startSide: DiffSide = side,
) {
  init {
    require(startLine in 1..endLine || startSide != side) { "bad range $startLine..$endLine" }
  }

  val isMultiLine: Boolean
    get() = startLine != endLine || startSide != side
}

/** How a review ends. */
enum class ReviewEvent {
  Comment,
  Approve,
  RequestChanges,
}

data class DraftReviewComment(val anchor: ReviewAnchor, val body: String)

/**
 * A review to submit in one go: its summary, its verdict, and every line comment drafted for it.
 */
data class ReviewSubmission(
  val commitOid: String,
  val event: ReviewEvent,
  val body: String,
  val comments: List<DraftReviewComment>,
)

/** What the reader has marked viewed on the forge, per path. */
enum class FileViewedState {
  Viewed,
  Unviewed,
  /** Was viewed, but the file changed since. */
  Dismissed,
}

/** What the changes screen needs to know about a PR beyond its diff. */
data class PullRequestOverview(
  /** The forge's node ID, which some mutations take. */
  val id: String,
  val title: String,
  /** `open`, `closed`, or `merged`, lowercase. */
  val state: String,
  val headOid: String,
  val authorLogin: String?,
  val webUrl: String,
  val viewerDidAuthor: Boolean,
  /** The viewer's review is requested, directly or through a team. */
  val viewerReviewRequested: Boolean,
  /** The viewer's review left pending on the forge itself, as by another client. */
  val pendingReview: PendingReview? = null,
)

/**
 * A review the viewer started on the forge and hasn't submitted, as from the web. A forge allows
 * one per PR, so a new review has to continue it or discard it first.
 */
data class PendingReview(val id: String, val body: String, val commentCount: Int)

/** An existing comment thread on a PR's diff. */
data class DiffReviewThread(
  val id: String,
  val path: String,
  val side: DiffSide,
  val startSide: DiffSide?,
  /** Where it sits on the PR's current diff; null when outdated. */
  val line: Int?,
  val startLine: Int?,
  val originalLine: Int?,
  val isResolved: Boolean,
  val isOutdated: Boolean,
  /** On the file as a whole, not a line. */
  val isFileLevel: Boolean,
  val comments: List<ReviewComment>,
  val viewerCanReply: Boolean = false,
  val viewerCanResolve: Boolean = false,
  val viewerCanUnresolve: Boolean = false,
  val resolvedBy: String? = null,
)

data class ReviewComment(
  val id: String,
  val authorLogin: String?,
  val authorAvatarUrl: String?,
  val body: String,
  val createdAt: Instant,
  /** Part of the viewer's [PendingReview], so only they see it. */
  val pending: Boolean = false,
)

/**
 * Reading and reviewing a PR's changes on the forge: everything the changes screen needs that the
 * git objects don't carry.
 */
interface PullRequestReviews {
  suspend fun overview(repo: RepoRef, number: Int): PullRequestOverview

  suspend fun threads(repo: RepoRef, number: Int): List<DiffReviewThread>

  /** Null if the forge has no viewed state. */
  suspend fun viewedFiles(repo: RepoRef, number: Int): Map<String, FileViewedState>?

  /** Marks [path] viewed or not on the forge. [pullRequestId] is [PullRequestOverview.id]. */
  suspend fun setViewed(pullRequestId: String, path: String, viewed: Boolean)

  /** Submits a whole review at once: summary, verdict, and line comments. */
  suspend fun submitReview(repo: RepoRef, number: Int, submission: ReviewSubmission)

  /**
   * Replies to thread [threadId]: into the pending review [pendingReviewId] if given, otherwise
   * posted at once. Returns the comment as the forge has it.
   */
  suspend fun reply(threadId: String, body: String, pendingReviewId: String? = null): ReviewComment

  suspend fun setResolved(threadId: String, resolved: Boolean)

  /**
   * Starts a pending review on PR [pullRequestId] at [commitOid] with [comments], for replies to
   * join before it's submitted. Returns its id.
   */
  suspend fun startPendingReview(
    pullRequestId: String,
    commitOid: String,
    comments: List<DraftReviewComment>,
  ): String

  /** Adds a new line comment to pending review [reviewId]. */
  suspend fun addToPendingReview(reviewId: String, comment: DraftReviewComment)

  suspend fun submitPendingReview(reviewId: String, event: ReviewEvent, body: String)

  /** Discards pending review [reviewId] and every comment in it. */
  suspend fun deletePendingReview(reviewId: String)
}
