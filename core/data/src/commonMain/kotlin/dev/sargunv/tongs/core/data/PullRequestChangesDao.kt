package dev.sargunv.tongs.core.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

// The PR changes screen's tables: the PR's review metadata, viewed files, the pending review
// drafted here, and the forge's existing review threads. Times are epoch milliseconds; sides are
// `Old` or `New`.

/** What the changes screen knows about a PR beyond its diff, as the forge last said. */
@Entity(
  tableName = "changes_pr",
  primaryKeys = ["accountId", "owner", "repo", "number"],
  foreignKeys =
    [
      ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE,
      ),
    ],
)
data class PullRequestOverviewEntity(
  val accountId: Long,
  val owner: String,
  val repo: String,
  val number: Int,
  val nodeId: String,
  val title: String,
  val state: String,
  val headOid: String,
  val authorLogin: String?,
  val webUrl: String,
  val viewerDidAuthor: Boolean,
  val viewerReviewRequested: Boolean,
  val viewerHasPendingReview: Boolean = false,
  /** The viewer's review left pending on the forge, as from the web, if there is one. */
  val pendingReviewId: String? = null,
  val pendingReviewBody: String? = null,
  @ColumnInfo(defaultValue = "0") val pendingReviewComments: Int = 0,
  val syncedAt: Long,
)

/**
 * A file marked viewed, keyed by path and blob, so a file that changes is unviewed again by itself.
 * [pending] rows wait on the forge: a viewed one to be marked, an unviewed one to be unmarked and
 * then deleted. Sync leaves pending rows alone.
 */
@Entity(
  tableName = "changes_viewed_file",
  primaryKeys = ["accountId", "owner", "repo", "number", "path", "blobOid"],
  foreignKeys =
    [
      ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE,
      ),
    ],
)
data class ViewedFileEntity(
  val accountId: Long,
  val owner: String,
  val repo: String,
  val number: Int,
  val path: String,
  val blobOid: String,
  val viewed: Boolean,
  val pending: Boolean,
  val updatedAt: Long,
)

/**
 * A comment in the pending review, drafted here until the review is submitted: a line comment, with
 * the forge-neutral `ReviewAnchor`, or a reply to thread [inReplyTo], whose anchor is the thread's.
 */
@Entity(
  tableName = "changes_draft_comment",
  foreignKeys =
    [
      ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE,
      ),
    ],
  indices = [Index("accountId", "owner", "repo", "number")],
)
data class DraftCommentEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val accountId: Long,
  val owner: String,
  val repo: String,
  val number: Int,
  val commitOid: String,
  val path: String,
  val side: String,
  val startSide: String,
  val startLine: Int,
  val endLine: Int,
  val body: String,
  val createdAt: Long,
  val updatedAt: Long,
  val inReplyTo: String? = null,
)

/** The pending review's summary, kept as it's typed. */
@Entity(
  tableName = "changes_draft_review",
  primaryKeys = ["accountId", "owner", "repo", "number"],
  foreignKeys =
    [
      ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE,
      ),
    ],
)
data class DraftReviewEntity(
  val accountId: Long,
  val owner: String,
  val repo: String,
  val number: Int,
  val summary: String,
  val updatedAt: Long,
)

/** An existing review thread on the PR's diff, as the forge last said. */
@Entity(
  tableName = "changes_thread",
  primaryKeys = ["accountId", "id"],
  foreignKeys =
    [
      ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE,
      ),
    ],
  indices = [Index("accountId", "owner", "repo", "number")],
)
data class ChangesThreadEntity(
  val accountId: Long,
  val id: String,
  val owner: String,
  val repo: String,
  val number: Int,
  val path: String,
  val side: String,
  val startSide: String?,
  val line: Int?,
  val startLine: Int?,
  val originalLine: Int?,
  val isResolved: Boolean,
  val isOutdated: Boolean,
  val isFileLevel: Boolean,
  /** The forge's order. */
  val position: Int,
  @ColumnInfo(defaultValue = "0") val viewerCanReply: Boolean = false,
  @ColumnInfo(defaultValue = "0") val viewerCanResolve: Boolean = false,
  @ColumnInfo(defaultValue = "0") val viewerCanUnresolve: Boolean = false,
  val resolvedBy: String? = null,
)

@Entity(
  tableName = "changes_thread_comment",
  primaryKeys = ["accountId", "id"],
  foreignKeys =
    [
      ForeignKey(
        entity = ChangesThreadEntity::class,
        parentColumns = ["accountId", "id"],
        childColumns = ["accountId", "threadId"],
        onDelete = ForeignKey.CASCADE,
      ),
    ],
  indices = [Index("accountId", "threadId")],
)
data class ChangesThreadCommentEntity(
  val accountId: Long,
  val id: String,
  val threadId: String,
  val authorLogin: String?,
  val authorAvatarUrl: String?,
  val body: String,
  val createdAt: Long,
  val position: Int,
  @ColumnInfo(defaultValue = "'Published'") val state: CommentState = CommentState.Published,
)

/** Where a thread's comment stands. */
enum class CommentState {
  /** Everyone sees it. */
  Published,
  /** In the viewer's review left pending on the forge, so only they see it. */
  Pending,
  /** Posted from here and not yet confirmed: its id is a placeholder until the forge answers. */
  Sending,
}

@Dao
interface PullRequestChangesDao {
  @Query(
    """
    SELECT * FROM changes_pr
    WHERE accountId = :accountId AND owner = :owner AND repo = :repo AND number = :number
    """,
  )
  fun observeOverview(
    accountId: Long,
    owner: String,
    repo: String,
    number: Int,
  ): Flow<PullRequestOverviewEntity?>

  @Query(
    """
    SELECT * FROM changes_pr
    WHERE accountId = :accountId AND owner = :owner AND repo = :repo AND number = :number
    """,
  )
  suspend fun overview(
    accountId: Long,
    owner: String,
    repo: String,
    number: Int,
  ): PullRequestOverviewEntity?

  @Upsert suspend fun upsertOverview(overview: PullRequestOverviewEntity)

  @Query(
    """
    SELECT * FROM changes_viewed_file
    WHERE accountId = :accountId AND owner = :owner AND repo = :repo AND number = :number
    """,
  )
  fun observeViewed(
    accountId: Long,
    owner: String,
    repo: String,
    number: Int,
  ): Flow<List<ViewedFileEntity>>

  @Query(
    """
    SELECT * FROM changes_viewed_file
    WHERE accountId = :accountId AND owner = :owner AND repo = :repo AND number = :number
    """,
  )
  suspend fun viewed(
    accountId: Long,
    owner: String,
    repo: String,
    number: Int,
  ): List<ViewedFileEntity>

  @Query(
    """
    SELECT * FROM changes_viewed_file
    WHERE accountId = :accountId AND owner = :owner AND repo = :repo AND number = :number
      AND path = :path AND blobOid = :blobOid
    """,
  )
  suspend fun viewedFile(
    accountId: Long,
    owner: String,
    repo: String,
    number: Int,
    path: String,
    blobOid: String,
  ): ViewedFileEntity?

  @Upsert suspend fun upsertViewed(files: List<ViewedFileEntity>)

  @Query(
    """
    DELETE FROM changes_viewed_file
    WHERE accountId = :accountId AND owner = :owner AND repo = :repo AND number = :number
      AND path = :path AND blobOid = :blobOid
    """,
  )
  suspend fun deleteViewed(
    accountId: Long,
    owner: String,
    repo: String,
    number: Int,
    path: String,
    blobOid: String,
  )

  @Query(
    """
    SELECT * FROM changes_draft_comment
    WHERE accountId = :accountId AND owner = :owner AND repo = :repo AND number = :number
    ORDER BY path, endLine, id
    """,
  )
  fun observeDrafts(
    accountId: Long,
    owner: String,
    repo: String,
    number: Int,
  ): Flow<List<DraftCommentEntity>>

  @Query(
    """
    SELECT * FROM changes_draft_comment
    WHERE accountId = :accountId AND owner = :owner AND repo = :repo AND number = :number
    ORDER BY path, endLine, id
    """,
  )
  suspend fun drafts(
    accountId: Long,
    owner: String,
    repo: String,
    number: Int,
  ): List<DraftCommentEntity>

  @Upsert suspend fun upsertDraft(draft: DraftCommentEntity): Long

  @Query("SELECT * FROM changes_draft_comment WHERE id = :id")
  suspend fun draft(id: Long): DraftCommentEntity?

  @Query("DELETE FROM changes_draft_comment WHERE id = :id") suspend fun deleteDraft(id: Long)

  @Query(
    """
    DELETE FROM changes_draft_comment WHERE id = :id AND updatedAt = :updatedAt AND body = :body
    """,
  )
  suspend fun deleteDraftIfUnchanged(id: Long, updatedAt: Long, body: String)

  @Query(
    """
    SELECT * FROM changes_draft_review
    WHERE accountId = :accountId AND owner = :owner AND repo = :repo AND number = :number
    """,
  )
  fun observeDraftReview(
    accountId: Long,
    owner: String,
    repo: String,
    number: Int,
  ): Flow<DraftReviewEntity?>

  @Query(
    """
    SELECT * FROM changes_draft_review
    WHERE accountId = :accountId AND owner = :owner AND repo = :repo AND number = :number
    """,
  )
  suspend fun draftReview(
    accountId: Long,
    owner: String,
    repo: String,
    number: Int,
  ): DraftReviewEntity?

  @Upsert suspend fun upsertDraftReview(review: DraftReviewEntity)

  @Query(
    """
    DELETE FROM changes_draft_review
    WHERE accountId = :accountId AND owner = :owner AND repo = :repo AND number = :number
    """,
  )
  suspend fun deleteDraftReview(accountId: Long, owner: String, repo: String, number: Int)

  @Query(
    """
    SELECT * FROM changes_thread
    WHERE accountId = :accountId AND owner = :owner AND repo = :repo AND number = :number
    ORDER BY position
    """,
  )
  fun observeThreads(
    accountId: Long,
    owner: String,
    repo: String,
    number: Int,
  ): Flow<List<ChangesThreadEntity>>

  @Query(
    """
    SELECT c.* FROM changes_thread_comment c
    JOIN changes_thread t ON t.accountId = c.accountId AND t.id = c.threadId
    WHERE t.accountId = :accountId AND t.owner = :owner AND t.repo = :repo AND t.number = :number
    ORDER BY c.threadId, c.position
    """,
  )
  fun observeThreadComments(
    accountId: Long,
    owner: String,
    repo: String,
    number: Int,
  ): Flow<List<ChangesThreadCommentEntity>>

  @Query(
    """
    DELETE FROM changes_thread
    WHERE accountId = :accountId AND owner = :owner AND repo = :repo AND number = :number
    """,
  )
  suspend fun deleteThreads(accountId: Long, owner: String, repo: String, number: Int)

  @Upsert suspend fun upsertThreads(threads: List<ChangesThreadEntity>)

  @Upsert suspend fun upsertThreadComments(comments: List<ChangesThreadCommentEntity>)

  @Query("SELECT * FROM changes_thread WHERE accountId = :accountId AND id = :id")
  suspend fun thread(accountId: Long, id: String): ChangesThreadEntity?

  @Query(
    """
    UPDATE changes_thread SET isResolved = :resolved, viewerCanResolve = :canResolve,
      viewerCanUnresolve = :canUnresolve WHERE accountId = :accountId AND id = :id
    """,
  )
  suspend fun setThreadResolved(
    accountId: Long,
    id: String,
    resolved: Boolean,
    canResolve: Boolean,
    canUnresolve: Boolean,
  )

  @Query(
    """
    SELECT COALESCE(MAX(position), -1) + 1 FROM changes_thread_comment
    WHERE accountId = :accountId AND threadId = :threadId
    """,
  )
  suspend fun nextCommentPosition(accountId: Long, threadId: String): Int

  @Query(
    """
    SELECT c.* FROM changes_thread_comment c
    JOIN changes_thread t ON t.accountId = c.accountId AND t.id = c.threadId
    WHERE t.accountId = :accountId AND t.owner = :owner AND t.repo = :repo AND t.number = :number
      AND c.state = 'Sending'
    """,
  )
  suspend fun sendingComments(
    accountId: Long,
    owner: String,
    repo: String,
    number: Int,
  ): List<ChangesThreadCommentEntity>

  @Query("DELETE FROM changes_thread_comment WHERE accountId = :accountId AND id = :id")
  suspend fun deleteThreadComment(accountId: Long, id: String)

  @Query(
    """
    UPDATE changes_pr SET viewerHasPendingReview = 0, pendingReviewId = NULL,
      pendingReviewBody = NULL, pendingReviewComments = 0
    WHERE accountId = :accountId AND owner = :owner AND repo = :repo AND number = :number
    """,
  )
  suspend fun clearPendingReview(accountId: Long, owner: String, repo: String, number: Int)

  @Query(
    """
    DELETE FROM changes_thread_comment
    WHERE accountId = :accountId AND state = 'Pending' AND threadId IN (
      SELECT id FROM changes_thread
      WHERE accountId = :accountId AND owner = :owner AND repo = :repo AND number = :number)
    """,
  )
  suspend fun deletePendingComments(
    accountId: Long,
    owner: String,
    repo: String,
    number: Int,
  )

  /** Threads left with no comments, as one the forge's pending review started. */
  @Query(
    """
    DELETE FROM changes_thread
    WHERE accountId = :accountId AND owner = :owner AND repo = :repo AND number = :number
      AND NOT EXISTS (
        SELECT 1 FROM changes_thread_comment c
        WHERE c.accountId = changes_thread.accountId AND c.threadId = changes_thread.id)
    """,
  )
  suspend fun deleteEmptyThreads(accountId: Long, owner: String, repo: String, number: Int)
}
