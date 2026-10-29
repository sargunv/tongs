package dev.sargunv.tongs.core.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Supplemental review state in an internal Room slot. Keeping this JSON document in the existing
 * key/value store lets the cache evolve without a database migration. It is excluded from draft
 * badges and composer slots; the ordinary changes tables still own anchors and comment bodies.
 */
@Serializable
internal data class ReviewCache(
  val pendingId: String? = null,
  val pendingBody: String? = null,
  val pendingCount: Int = 0,
  val permissions: Map<String, ThreadPermissions> = emptyMap(),
  val pendingComments: Set<String> = emptySet(),
  val replies: Map<Long, String> = emptyMap(),
) {
  fun overview(row: PullRequestOverviewEntity) =
    row.copy(
      pendingReviewId = pendingId,
      pendingReviewBody = pendingBody,
      pendingReviewComments = pendingCount,
    )

  fun draft(row: DraftCommentEntity) = row.copy(inReplyTo = replies[row.id])

  fun thread(row: ChangesThreadEntity): ChangesThreadEntity {
    val p = permissions[row.id] ?: return row
    return row.copy(
      viewerCanReply = p.reply,
      viewerCanResolve = p.resolve,
      viewerCanUnresolve = p.unresolve,
      resolvedBy = p.resolvedBy,
    )
  }

  fun comment(row: ChangesThreadCommentEntity) =
    row.copy(
      state =
        when {
          row.id.startsWith("sending:") -> CommentState.Sending
          row.id in pendingComments -> CommentState.Pending
          else -> CommentState.Published
        },
    )
}

@Serializable
internal data class ThreadPermissions(
  val reply: Boolean,
  val resolve: Boolean,
  val unresolve: Boolean,
  val resolvedBy: String?,
)

internal const val REVIEW_CACHE_SLOT = "cache/review"
private val reviewJson = Json { ignoreUnknownKeys = true }

internal fun decodeReviewCache(body: String?) =
  body?.let { runCatching { reviewJson.decodeFromString<ReviewCache>(it) }.getOrNull() }
    ?: ReviewCache()

internal fun ReviewCache.encode() = reviewJson.encodeToString(this)
