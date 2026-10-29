package dev.sargunv.tongs.core.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import dev.sargunv.tongs.forge.api.FeedCommit
import dev.sargunv.tongs.forge.api.FeedEventKind
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One activity feed event, as the forge reported it (see `forge.api.FeedEvent`), plus a push's
 * hydrated commits. Events never change on the forge, so sync inserts them once and only hydration
 * updates them. Kept beyond what the forge keeps (GitHub: 300 events, 30 days), up to
 * [FeedSync.KEEP_EVENTS] an account. Times are epoch milliseconds.
 */
@Entity(
  tableName = "feed_event",
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
  indices = [Index(value = ["accountId", "createdAt"]), Index(value = ["createdAt"])],
)
data class FeedEventEntity(
  val accountId: Long,
  /** The forge's event ID. */
  val id: String,
  /** A [FeedEventKind] name. */
  val kind: String,
  /** The forge's own kind: `PushEvent`. */
  val kindRaw: String,
  /** A `FeedAction` name, or null. */
  val action: String?,
  val actionRaw: String?,
  val actorLogin: String,
  val actorAvatarUrl: String?,
  val repoOwner: String,
  val repoName: String,
  val createdAt: Long,
  val isPublic: Boolean?,
  /** The raw subject type, `Issue` or `PullRequest`, for events on one. */
  val subjectType: String?,
  val subjectNumber: Int?,
  /** The subject's title and state as the event told them, if it did. */
  val subjectTitle: String?,
  val subjectState: String?,
  val ref: String?,
  /** A `RefType` name. */
  val refType: String?,
  val before: String?,
  val head: String?,
  /** A push's commits, newest first: JSON, a list of [StoredCommit]. Null until hydrated. */
  val commits: String?,
  val commitCount: Int?,
  /** Whether hydration has looked at this push, whatever it found. */
  val pushHydrated: Boolean,
  val title: String?,
  val excerpt: String?,
  val reviewState: String?,
  val target: String?,
  val webUrl: String?,
) {
  val eventKind: FeedEventKind
    get() = FeedEventKind.entries.find { it.name == kind } ?: FeedEventKind.Other

  val commitList: List<FeedCommit>?
    get() = decodeCommits(commits)
}

/**
 * An issue or PR some feed event is on, with its current title and state from hydration. Events on
 * it mark it stale, and hydration refreshes it once per batch of new events.
 */
@Entity(
  tableName = "feed_subject",
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
data class FeedSubjectEntity(
  val accountId: Long,
  val repoOwner: String,
  val repoName: String,
  val number: Int,
  /** The raw subject type, once known. */
  val type: String?,
  val title: String?,
  /** Raw from hydration, `OPEN`, `CLOSED`, `MERGED`; or the event's lowercase state until then. */
  val state: String?,
  val stateReason: String?,
  val isDraft: Boolean,
  /** False when an event came after the last hydration. */
  val hydrated: Boolean,
  val lastEventAt: Long,
)

/** Per-account feed poll state, like [InboxSyncEntity]. */
@Entity(
  tableName = "feed_sync",
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
data class FeedSyncEntity(
  @PrimaryKey val accountId: Long,
  /** The forge's opaque cursor; for GitHub, each stream's `ETag` and newest event IDs. */
  val cursor: String?,
  val pollIntervalSeconds: Long?,
  val lastPolledAt: Long?,
  val lastError: String?,
  /** A [SyncProblem.Kind] name. */
  val errorKind: String?,
  val retryAt: Long?,
  val lastSyncedAt: Long?,
  /** Organizations hiding their activity until SSO authorization: JSON, a list of [SsoNotice]. */
  val sso: String?,
)

/**
 * A repo or person whose activity the feed hides, on this device only: GitHub has no mute for its
 * feed. [value] is lowercase, `owner/name` or a login.
 */
@Entity(
  tableName = "feed_mute",
  primaryKeys = ["accountId", "kind", "value"],
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
data class FeedMuteEntity(
  val accountId: Long,
  /** A [MuteKind] name. */
  val kind: String,
  val value: String,
  @ColumnInfo(defaultValue = "0") val createdAt: Long,
)

enum class MuteKind {
  Repo,
  Actor,
}

@Dao
interface FeedDao {
  /** Inserts events not already stored; returns each one's row ID, or -1 if it was. */
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertEvents(events: List<FeedEventEntity>): List<Long>

  /** The newest [limit] events across accounts. */
  @Query("SELECT * FROM feed_event ORDER BY createdAt DESC, id DESC LIMIT :limit")
  fun observeEvents(limit: Int): Flow<List<FeedEventEntity>>

  @Query("SELECT COUNT(*) FROM feed_event") fun observeEventCount(): Flow<Int>

  @Query("SELECT * FROM feed_event WHERE accountId = :accountId ORDER BY createdAt DESC")
  suspend fun events(accountId: Long): List<FeedEventEntity>

  @Query(
    "SELECT * FROM feed_event WHERE accountId = :accountId AND kind = 'Push' AND " +
      "pushHydrated = 0 AND head IS NOT NULL ORDER BY createdAt DESC LIMIT :limit",
  )
  suspend fun unhydratedPushes(accountId: Long, limit: Int): List<FeedEventEntity>

  @Query(
    "UPDATE feed_event SET commits = :commits, commitCount = :count, pushHydrated = 1 " +
      "WHERE accountId = :accountId AND kind = 'Push' AND repoOwner = :owner AND " +
      "repoName = :name AND head = :head AND before IS :before",
  )
  suspend fun setPushCommits(
    accountId: Long,
    owner: String,
    name: String,
    before: String?,
    head: String,
    commits: String?,
    count: Int?,
  )

  /** Drops all but the newest [keep] events of the account, and subjects no event is on. */
  @Query(
    "DELETE FROM feed_event WHERE accountId = :accountId AND id NOT IN (" +
      "SELECT id FROM feed_event WHERE accountId = :accountId " +
      "ORDER BY createdAt DESC, id DESC LIMIT :keep)",
  )
  suspend fun pruneEvents(accountId: Long, keep: Int)

  @Query(
    "DELETE FROM feed_subject WHERE accountId = :accountId AND NOT EXISTS (" +
      "SELECT 1 FROM feed_event e WHERE e.accountId = feed_subject.accountId AND " +
      "e.repoOwner = feed_subject.repoOwner AND e.repoName = feed_subject.repoName AND " +
      "e.subjectNumber = feed_subject.number)",
  )
  suspend fun pruneSubjects(accountId: Long)

  @Query("SELECT * FROM feed_subject") fun observeSubjects(): Flow<List<FeedSubjectEntity>>

  @Query(
    "SELECT * FROM feed_subject WHERE accountId = :accountId AND repoOwner = :owner AND " +
      "repoName = :name AND number = :number",
  )
  suspend fun subject(accountId: Long, owner: String, name: String, number: Int): FeedSubjectEntity?

  @Upsert suspend fun upsertSubject(subject: FeedSubjectEntity)

  @Query(
    "SELECT * FROM feed_subject WHERE accountId = :accountId AND hydrated = 0 " +
      "ORDER BY lastEventAt DESC LIMIT :limit",
  )
  suspend fun staleSubjects(accountId: Long, limit: Int): List<FeedSubjectEntity>

  @Query(
    "SELECT * FROM feed_subject WHERE accountId = :accountId ORDER BY lastEventAt DESC LIMIT :limit",
  )
  suspend fun recentSubjects(accountId: Long, limit: Int): List<FeedSubjectEntity>

  @Query(
    "UPDATE feed_subject SET type = COALESCE(:type, type), title = COALESCE(:title, title), " +
      "state = COALESCE(:state, state), stateReason = :stateReason, " +
      "isDraft = COALESCE(:isDraft, isDraft), hydrated = 1 " +
      "WHERE accountId = :accountId AND repoOwner = :owner AND repoName = :name AND number = :number",
  )
  suspend fun setSubjectState(
    accountId: Long,
    owner: String,
    name: String,
    number: Int,
    type: String?,
    title: String?,
    state: String?,
    stateReason: String?,
    isDraft: Boolean?,
  )

  @Query("SELECT * FROM feed_sync WHERE accountId = :accountId")
  suspend fun syncState(accountId: Long): FeedSyncEntity?

  @Upsert suspend fun upsertSyncState(state: FeedSyncEntity)

  @Query("SELECT * FROM feed_sync") fun observeSyncStates(): Flow<List<FeedSyncEntity>>

  @Query("SELECT * FROM feed_mute ORDER BY createdAt DESC")
  fun observeMutes(): Flow<List<FeedMuteEntity>>

  @Upsert suspend fun upsertMute(mute: FeedMuteEntity)

  @Query("DELETE FROM feed_mute WHERE accountId = :accountId AND kind = :kind AND value = :value")
  suspend fun deleteMute(accountId: Long, kind: String, value: String)
}

/** A commit as stored in [FeedEventEntity.commits]. */
@Serializable internal data class StoredCommit(val sha: String, val title: String)

private val commitsSerializer = ListSerializer(StoredCommit.serializer())

internal fun encodeCommits(commits: List<FeedCommit>?): String? = commits?.let { list ->
  Json.encodeToString(commitsSerializer, list.map { StoredCommit(it.sha, it.title) })
}

internal fun decodeCommits(json: String?): List<FeedCommit>? = json?.let {
  runCatching { Json.decodeFromString(commitsSerializer, it) }
    .getOrNull()
    ?.map { c -> FeedCommit(c.sha, c.title) }
}
