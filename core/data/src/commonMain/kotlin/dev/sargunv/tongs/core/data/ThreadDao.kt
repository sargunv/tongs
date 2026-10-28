package dev.sargunv.tongs.core.data

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * An issue or PR conversation as last fetched. The forge-neutral [dev.sargunv.tongs.forge.api]
 * model is stored as JSON rather than in columns: it's a cache the next refresh rewrites, its shape
 * grows with every event type we render, and nothing queries inside it. If a stored document no
 * longer decodes, the thread is simply refetched.
 */
@Entity(
  tableName = "thread",
  primaryKeys = ["accountId", "repoOwner", "repoName", "number"],
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
data class ThreadEntity(
  val accountId: Long,
  val repoOwner: String,
  val repoName: String,
  val number: Int,
  /** The raw kind: `Issue` or `PullRequest`. */
  val kind: String,
  /** A [dev.sargunv.tongs.forge.api.ThreadDetail], as JSON. */
  val detail: String,
  /** Epoch milliseconds. */
  val fetchedAt: Long,
  /** Timeline items on the forge, counting only the kinds we fetch. */
  val timelineCount: Int,
  /**
   * Where the unloaded middle of the timeline starts: the cursor after the last item loaded from
   * the start. Null once the whole timeline is loaded.
   */
  val gapCursor: String?,
)

/**
 * One timeline item. [position] keeps the forge's order: items from the start of the timeline count
 * up from 0, items from its end count up from [TAIL_POSITION], and comments posted here count up
 * from [LOCAL_POSITION], so the unloaded middle always sits between the first two groups.
 */
@Entity(
  tableName = "thread_item",
  primaryKeys = ["accountId", "repoOwner", "repoName", "number", "id"],
  foreignKeys =
    [
      ForeignKey(
        entity = ThreadEntity::class,
        parentColumns = ["accountId", "repoOwner", "repoName", "number"],
        childColumns = ["accountId", "repoOwner", "repoName", "number"],
        onDelete = ForeignKey.CASCADE,
      ),
    ],
  indices = [Index("accountId", "repoOwner", "repoName", "number", "position")],
)
data class ThreadItemEntity(
  val accountId: Long,
  val repoOwner: String,
  val repoName: String,
  val number: Int,
  /** The forge's ID, or a local one starting with [LOCAL_ID_PREFIX] until the forge confirms it. */
  val id: String,
  val position: Long,
  /** A [dev.sargunv.tongs.forge.api.TimelineItem], as JSON. */
  val item: String,
  /** Posted here and not yet confirmed by the forge. */
  val pending: Boolean = false,
)

/** A PR review comment thread, shown under the review that started it. */
@Entity(
  tableName = "thread_review_thread",
  primaryKeys = ["accountId", "repoOwner", "repoName", "number", "id"],
  foreignKeys =
    [
      ForeignKey(
        entity = ThreadEntity::class,
        parentColumns = ["accountId", "repoOwner", "repoName", "number"],
        childColumns = ["accountId", "repoOwner", "repoName", "number"],
        onDelete = ForeignKey.CASCADE,
      ),
    ],
)
data class ReviewThreadEntity(
  val accountId: Long,
  val repoOwner: String,
  val repoName: String,
  val number: Int,
  val id: String,
  /** A [dev.sargunv.tongs.forge.api.ReviewThread], as JSON. */
  val thread: String,
)

const val TAIL_POSITION = 1_000_000_000L
const val LOCAL_POSITION = 2_000_000_000L
const val LOCAL_ID_PREFIX = "local:"

@Dao
interface ThreadDao {
  @Query(
    """
    SELECT * FROM thread
    WHERE accountId = :accountId AND repoOwner = :owner AND repoName = :name AND number = :number
    """,
  )
  fun observeThread(accountId: Long, owner: String, name: String, number: Int): Flow<ThreadEntity?>

  @Query(
    """
    SELECT * FROM thread
    WHERE accountId = :accountId AND repoOwner = :owner AND repoName = :name AND number = :number
    """,
  )
  suspend fun thread(accountId: Long, owner: String, name: String, number: Int): ThreadEntity?

  @Query(
    """
    SELECT * FROM thread_item
    WHERE accountId = :accountId AND repoOwner = :owner AND repoName = :name AND number = :number
    ORDER BY position
    """,
  )
  fun observeItems(
    accountId: Long,
    owner: String,
    name: String,
    number: Int,
  ): Flow<List<ThreadItemEntity>>

  @Query(
    """
    SELECT * FROM thread_item
    WHERE accountId = :accountId AND repoOwner = :owner AND repoName = :name AND number = :number
    ORDER BY position
    """,
  )
  suspend fun items(
    accountId: Long,
    owner: String,
    name: String,
    number: Int,
  ): List<ThreadItemEntity>

  @Query(
    """
    SELECT * FROM thread_review_thread
    WHERE accountId = :accountId AND repoOwner = :owner AND repoName = :name AND number = :number
    """,
  )
  fun observeReviewThreads(
    accountId: Long,
    owner: String,
    name: String,
    number: Int,
  ): Flow<List<ReviewThreadEntity>>

  @Query(
    """
    SELECT * FROM thread_review_thread
    WHERE accountId = :accountId AND repoOwner = :owner AND repoName = :name AND number = :number
    """,
  )
  suspend fun reviewThreads(
    accountId: Long,
    owner: String,
    name: String,
    number: Int,
  ): List<ReviewThreadEntity>

  @Upsert suspend fun upsertThread(thread: ThreadEntity)

  @Upsert suspend fun upsertItems(items: List<ThreadItemEntity>)

  /** Adds items, keeping any already stored with the same ID where they are. */
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertNewItems(items: List<ThreadItemEntity>)

  @Upsert suspend fun upsertReviewThreads(threads: List<ReviewThreadEntity>)

  @Query(
    """
    DELETE FROM thread_item
    WHERE accountId = :accountId AND repoOwner = :owner AND repoName = :name AND number = :number
      AND NOT pending
    """,
  )
  suspend fun deleteConfirmedItems(accountId: Long, owner: String, name: String, number: Int)

  @Query(
    """
    DELETE FROM thread_item
    WHERE accountId = :accountId AND repoOwner = :owner AND repoName = :name AND number = :number
      AND id = :id
    """,
  )
  suspend fun deleteItem(accountId: Long, owner: String, name: String, number: Int, id: String)

  @Query(
    """
    DELETE FROM thread_review_thread
    WHERE accountId = :accountId AND repoOwner = :owner AND repoName = :name AND number = :number
    """,
  )
  suspend fun deleteReviewThreads(accountId: Long, owner: String, name: String, number: Int)

  @Query(
    """
    SELECT COALESCE(MAX(position), -1) FROM thread_item
    WHERE accountId = :accountId AND repoOwner = :owner AND repoName = :name AND number = :number
      AND position >= :from AND position < :until
    """,
  )
  suspend fun maxPosition(
    accountId: Long,
    owner: String,
    name: String,
    number: Int,
    from: Long,
    until: Long,
  ): Long
}
