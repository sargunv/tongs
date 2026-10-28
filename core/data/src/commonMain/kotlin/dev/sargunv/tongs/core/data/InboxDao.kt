package dev.sargunv.tongs.core.data

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A notification thread: what the forge last said, plus local state the forge doesn't know about.
 * Times are epoch milliseconds.
 */
@Entity(
  tableName = "inbox_thread",
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
  indices = [Index("updatedAt")],
)
data class InboxThreadEntity(
  val accountId: Long,
  /** The forge's thread ID. */
  val id: String,
  val repoOwner: String,
  val repoName: String,
  val title: String,
  /** The raw subject type: `PullRequest`, `Issue`, and so on. */
  val subjectType: String,
  val subjectNumber: Int?,
  val webUrl: String?,
  /** The raw reason: `review_requested`, `author`, and so on. */
  val reason: String,
  /** Whether the forge says it's unread, or false once marked read here. */
  val unread: Boolean,
  val updatedAt: Long,
  val lastReadAt: Long?,

  // Local state. Sync keeps these when it rewrites the forge's fields.

  /** Marked read here; the forge hasn't confirmed it yet, so sync mustn't undo it. */
  val pendingRead: Boolean = false,
  /** Marked done here, so hidden; the row goes once the forge confirms. */
  val pendingDone: Boolean = false,
  /**
   * Unread on this device only: marked unread where the forge can't, or back from a snooze. Cleared
   * when it's read again.
   */
  val localUnread: Boolean = false,
  /** Hidden until this time, then shown again as unread. */
  val snoozedUntil: Long? = null,

  // Hydration; see InboxHydrator.

  val subjectState: String? = null,
  val whatsNew: String? = null,
)

/** Per-account poll state: the forge's cursor and how often it asks to be polled. */
@Entity(
  tableName = "inbox_sync",
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
data class InboxSyncEntity(
  @PrimaryKey val accountId: Long,
  val cursor: String?,
  val pollIntervalSeconds: Long?,
  val lastPolledAt: Long?,
  val lastError: String?,
)

/** A row of the inbox list, joined with its account. */
data class InboxRow(
  val accountId: Long,
  val accountLogin: String,
  val id: String,
  val repoOwner: String,
  val repoName: String,
  val title: String,
  val subjectType: String,
  val subjectNumber: Int?,
  val webUrl: String?,
  val reason: String,
  val isUnread: Boolean,
  val updatedAt: Long,
  /** When it was last updated, or came back from a snooze, whichever is later. */
  val sortAt: Long,
  val subjectState: String?,
  val whatsNew: String?,
)

@Dao
interface InboxDao {
  /** Visible threads across every account, latest first. Snoozed ones show from [now] on. */
  @Query(
    """
    SELECT t.accountId, a.login AS accountLogin, t.id, t.repoOwner, t.repoName, t.title,
      t.subjectType, t.subjectNumber, t.webUrl, t.reason, (t.unread OR t.localUnread) AS isUnread,
      t.updatedAt, MAX(t.updatedAt, COALESCE(t.snoozedUntil, 0)) AS sortAt, t.subjectState,
      t.whatsNew
    FROM inbox_thread t JOIN account a ON a.id = t.accountId
    WHERE NOT t.pendingDone AND (t.snoozedUntil IS NULL OR t.snoozedUntil <= :now)
    ORDER BY sortAt DESC
    """,
  )
  fun observeInbox(now: Long): Flow<List<InboxRow>>

  @Query("SELECT * FROM inbox_thread WHERE accountId = :accountId AND id = :id")
  fun observeThread(accountId: Long, id: String): Flow<InboxThreadEntity?>

  @Query("SELECT * FROM inbox_thread WHERE accountId = :accountId AND id = :id")
  suspend fun thread(accountId: Long, id: String): InboxThreadEntity?

  @Query("SELECT * FROM inbox_thread WHERE accountId = :accountId")
  suspend fun threads(accountId: Long): List<InboxThreadEntity>

  /** The next time a snoozed thread comes back after [now], if any. */
  @Query("SELECT MIN(snoozedUntil) FROM inbox_thread WHERE snoozedUntil > :now")
  fun observeNextWake(now: Long): Flow<Long?>

  @Upsert suspend fun upsert(threads: List<InboxThreadEntity>)

  @Query("DELETE FROM inbox_thread WHERE accountId = :accountId AND id IN (:ids)")
  suspend fun delete(accountId: Long, ids: List<String>)

  @Query(
    """
    UPDATE inbox_thread SET unread = 0, localUnread = 0, snoozedUntil = NULL, pendingRead = 1
    WHERE accountId = :accountId AND id = :id
    """,
  )
  suspend fun markReadPending(accountId: Long, id: String)

  @Query("UPDATE inbox_thread SET pendingRead = 0 WHERE accountId = :accountId AND id = :id")
  suspend fun confirmRead(accountId: Long, id: String)

  @Query(
    "UPDATE inbox_thread SET unread = 1, pendingRead = 0 WHERE accountId = :accountId AND id = :id",
  )
  suspend fun revertRead(accountId: Long, id: String)

  @Query("UPDATE inbox_thread SET localUnread = 1 WHERE accountId = :accountId AND id = :id")
  suspend fun markUnreadLocally(accountId: Long, id: String)

  @Query("UPDATE inbox_thread SET pendingDone = :pending WHERE accountId = :accountId AND id = :id")
  suspend fun setPendingDone(accountId: Long, id: String, pending: Boolean)

  @Query(
    """
    UPDATE inbox_thread SET snoozedUntil = :until, localUnread = 1
    WHERE accountId = :accountId AND id = :id
    """,
  )
  suspend fun snooze(accountId: Long, id: String, until: Long)

  @Query(
    """
    UPDATE inbox_thread SET subjectState = :subjectState, whatsNew = :whatsNew
    WHERE accountId = :accountId AND id = :id
    """,
  )
  suspend fun setHydration(accountId: Long, id: String, subjectState: String?, whatsNew: String?)

  @Query("SELECT * FROM inbox_sync WHERE accountId = :accountId")
  suspend fun syncState(accountId: Long): InboxSyncEntity?

  @Upsert suspend fun upsertSyncState(state: InboxSyncEntity)

  @Query("SELECT * FROM inbox_sync") fun observeSyncStates(): Flow<List<InboxSyncEntity>>
}
