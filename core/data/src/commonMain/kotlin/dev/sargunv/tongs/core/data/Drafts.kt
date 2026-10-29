package dev.sargunv.tongs.core.data

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.withWriteTransaction
import dev.sargunv.tongs.engine.DiffSide
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.ReviewAnchor
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A stable identity within an account and conversation; the destination never changes. */
data class DraftKey(
  val accountId: Long,
  val repo: RepoRef,
  val number: Int,
  val slot: String = REPLY,
  val destination: DraftDestination = DraftDestination.ThreadReply,
  val anchor: ReviewAnchor? = null,
  val threadId: String? = null,
) {
  val value: String
    get() = "${repo.owner}/${repo.name}#$number/$slot"

  companion object {
    const val REPLY = "reply"

    fun summary(key: PullRequestKey) =
      DraftKey(
        key.accountId,
        key.repo,
        key.number,
        "review/summary",
        DraftDestination.Summary,
      )
  }
}

enum class DraftDestination {
  ThreadReply,
  LineComment,
  ReviewReply,
  Summary,
}

data class DraftThread(val accountId: Long, val repo: RepoRef, val number: Int)

/** The only editable copy of a piece of text. Null commit means the anchor is unknown. */
@Entity(
  tableName = "draft",
  foreignKeys =
    [
      ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE,
      ),
    ],
  indices =
    [
      Index("accountId", "repoOwner", "repoName", "number"),
      Index(value = ["accountId", "key"], unique = true),
    ],
)
data class DraftEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val accountId: Long,
  val key: String,
  val repoOwner: String,
  val repoName: String,
  val number: Int,
  val slot: String,
  val body: String,
  val updatedAt: Long,
  val destination: DraftDestination,
  val commitOid: String? = null,
  val path: String = "",
  val side: String = "New",
  val startSide: String = "New",
  val startLine: Int = 0,
  val endLine: Int = 0,
  val inReplyTo: String? = null,
  val inReview: Boolean = false,
  val submitting: Boolean = false,
  val mayHaveBeenSent: Boolean = false,
  val continuedReviewId: String? = null,
) {
  val anchor: ReviewAnchor?
    get() =
      if (destination == DraftDestination.LineComment && startLine > 0 && endLine >= startLine)
        ReviewAnchor(
          commitOid.orEmpty(),
          path,
          DiffSide.valueOf(side),
          startLine,
          endLine,
          DiffSide.valueOf(startSide),
        )
      else null

  val draftKey: DraftKey
    get() =
      DraftKey(
        accountId,
        RepoRef(repoOwner, repoName),
        number,
        slot,
        destination,
        anchor,
        inReplyTo,
      )
}

@Dao
interface DraftDao {
  @Query("SELECT * FROM draft WHERE accountId = :accountId AND `key` = :key")
  suspend fun get(accountId: Long, key: String): DraftEntity?

  @Query("SELECT * FROM draft WHERE id = :id") suspend fun byId(id: Long): DraftEntity?

  @Query("SELECT * FROM draft WHERE accountId = :accountId AND `key` = :key")
  fun observe(accountId: Long, key: String): Flow<DraftEntity?>

  @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(draft: DraftEntity): Long

  @Query(
    "UPDATE draft SET body = :body, updatedAt = :at WHERE accountId = :accountId AND `key` = :key AND submitting = 0",
  )
  suspend fun write(accountId: Long, key: String, body: String, at: Long)

  @Query("UPDATE draft SET inReview = :member WHERE id = :id AND submitting = 0")
  suspend fun membership(id: Long, member: Boolean)

  @Query(
    "UPDATE draft SET continuedReviewId = :reviewId, inReview = 1 WHERE accountId = :accountId AND `key` = :key AND submitting = 0",
  )
  suspend fun continueReview(accountId: Long, key: String, reviewId: String)

  @Query("UPDATE draft SET submitting = :value WHERE id IN (:ids)")
  suspend fun submitting(ids: List<Long>, value: Boolean)

  @Query("UPDATE draft SET submitting = 0, mayHaveBeenSent = 1 WHERE submitting = 1")
  suspend fun recover()

  @Query("DELETE FROM draft WHERE id IN (:ids)") suspend fun deleteSubmitted(ids: List<Long>)

  @Query("DELETE FROM draft WHERE accountId = :accountId AND `key` = :key AND submitting = 0")
  suspend fun delete(accountId: Long, key: String)

  @Query(
    "SELECT * FROM draft WHERE accountId = :accountId AND repoOwner = :owner AND repoName = :name AND number = :number ORDER BY id",
  )
  fun observeAll(accountId: Long, owner: String, name: String, number: Int): Flow<List<DraftEntity>>

  @Query(
    "SELECT * FROM draft WHERE accountId = :accountId AND repoOwner = :owner AND repoName = :name AND number = :number ORDER BY id",
  )
  suspend fun all(accountId: Long, owner: String, name: String, number: Int): List<DraftEntity>

  @Query(
    "SELECT COUNT(*) > 0 FROM draft WHERE accountId = :accountId AND repoOwner = :owner AND repoName = :name AND number = :number AND (TRIM(body) != '' OR inReview = 1)",
  )
  fun observeHasDraft(accountId: Long, owner: String, name: String, number: Int): Flow<Boolean>

  @Query(
    "SELECT DISTINCT accountId, repoOwner, repoName, number FROM draft WHERE TRIM(body) != '' OR inReview = 1",
  )
  fun observeDraftThreads(): Flow<List<DraftThreadRow>>
}

data class DraftThreadRow(
  val accountId: Long,
  val repoOwner: String,
  val repoName: String,
  val number: Int,
)

/** One ordered, app-owned writer. Input queues a write synchronously, even if Room is busy. */
class DraftWriter
internal constructor(
  val key: DraftKey,
  private val dao: DraftDao,
  private val scope: CoroutineScope,
  private val clock: Clock,
  recovery: Deferred<Unit>,
) {
  val body = MutableStateFlow("")
  val ready = MutableStateFlow(false)
  val locked = MutableStateFlow(false)
  val inReview = MutableStateFlow(false)
  val warning = MutableStateFlow(false)
  val error = MutableStateFlow<String?>(null)
  private var writeFailure: Exception? = null
  private var membershipOverride: Boolean? = null
  private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)

  init {
    scope.launch {
      recovery.await()
      val row = dao.get(key.accountId, key.value)
      body.value = row?.body.orEmpty()
      locked.value = locked.value || row?.submitting == true
      inReview.value = membershipOverride ?: (row?.inReview == true)
      warning.value = row?.mayHaveBeenSent == true
      ready.value = true
      for (command in commands) {
        try {
          command()
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          writeFailure = e
          error.value = "Couldn't save your draft: ${e.message}"
        }
      }
    }
  }

  internal suspend fun ensure() {
    val a = key.anchor
    dao.insert(
      DraftEntity(
        accountId = key.accountId,
        key = key.value,
        repoOwner = key.repo.owner,
        repoName = key.repo.name,
        number = key.number,
        slot = key.slot,
        body = body.value,
        updatedAt = clock.now().toEpochMilliseconds(),
        destination = key.destination,
        commitOid = a?.commitOid?.ifEmpty { null },
        path = a?.path.orEmpty(),
        side = a?.side?.name ?: "New",
        startSide = a?.startSide?.name ?: "New",
        startLine = a?.startLine ?: 0,
        endLine = a?.endLine ?: 0,
        inReplyTo = key.threadId,
      ),
    )
  }

  fun write(text: String): Boolean =
    synchronized(this) {
      if (!ready.value || locked.value) return false
      body.value = text
      if (key.destination == DraftDestination.Summary) inReview.value = true
      commands
        .trySend {
          ensure()
          dao.write(key.accountId, key.value, text, clock.now().toEpochMilliseconds())
          if (key.destination == DraftDestination.Summary)
            dao.get(key.accountId, key.value)?.let { dao.membership(it.id, true) }
          writeFailure = null
          error.value = null
        }
        .getOrThrow()
      return true
    }

  internal fun membership(member: Boolean) =
    synchronized(this) {
      if (locked.value) return
      membershipOverride = member
      inReview.value = member
      enqueue {
        ensure()
        dao.get(key.accountId, key.value)?.let { dao.membership(it.id, member) }
      }
    }

  internal fun delete() =
    synchronized(this) {
      if (locked.value) return
      clear()
      enqueue { dao.delete(key.accountId, key.value) }
    }

  internal fun enqueue(command: suspend () -> Unit) {
    commands.trySend(command).getOrThrow()
  }

  suspend fun flush() {
    val done = CompletableDeferred<Unit>()
    enqueue { done.complete(Unit) }
    done.await()
    writeFailure?.let { throw it }
  }

  /** Reserves before the first suspension; input and repeated submits now stop. */
  internal fun reserve(): Boolean = synchronized(this) { locked.compareAndSet(false, true) }

  internal fun clear() =
    synchronized(this) {
      body.value = ""
      inReview.value = false
      membershipOverride = false
      warning.value = false
      error.value = null
    }
}

@SingleIn(AppScope::class)
@Inject
class DraftRepository(
  private val db: TongsDatabase,
  private val scope: CoroutineScope,
  private val clock: Clock,
) {
  private val dao = db.drafts()
  private val recovery = scope.async { dao.recover() }
  private val writers = mutableMapOf<Pair<Long, String>, DraftWriter>()

  fun writer(key: DraftKey): DraftWriter =
    synchronized(writers) {
      writers.getOrPut(key.accountId to key.value) { DraftWriter(key, dao, scope, clock, recovery) }
    }

  fun observe(key: DraftKey): Flow<String?> =
    dao.observe(key.accountId, key.value).map { it?.body }.distinctUntilChanged()

  suspend fun get(key: DraftKey): String? = dao.get(key.accountId, key.value)?.body

  suspend fun save(key: DraftKey, body: String) {
    val writer = writer(key)
    writer.ready.first { it }
    if (writer.write(body)) writer.flush()
  }

  suspend fun append(key: DraftKey, text: String) {
    val writer = writer(key)
    writer.ready.first { it }
    val old = writer.body.value.trimEnd()
    save(key, if (old.isEmpty()) text else "$old\n\n$text")
  }

  fun membership(key: DraftKey, member: Boolean) {
    val writer = writer(key)
    writer.membership(member)
  }

  suspend fun delete(key: DraftKey) {
    val writer = writer(key)
    writer.ready.first { it }
    writer.delete()
    writer.flush()
  }

  fun all(key: PullRequestKey): Flow<List<DraftEntity>> =
    dao.observeAll(key.accountId, key.repo.owner, key.repo.name, key.number)

  /** Retire rows as soon as the forge accepts them, including during a partial submission. */
  internal suspend fun accepted(rows: List<DraftEntity>) {
    dao.deleteSubmitted(rows.map { it.id })
    rows.forEach { writer(it.draftKey).clear() }
  }

  /** Preparation, claim, delivery and settlement all outlive the screen. No replay on restart. */
  fun submitReview(
    key: PullRequestKey,
    deliver: suspend (List<DraftEntity>) -> Unit,
  ): Deferred<Boolean> {
    val keys =
      synchronized(writers) {
        writers.values
          .filter {
            it.key.accountId == key.accountId &&
              it.key.repo == key.repo &&
              it.key.number == key.number &&
              it.inReview.value
          }
          .map { it.key }
      }
    return submit(keys + DraftKey.summary(key), key, deliver)
  }

  fun submit(
    keys: List<DraftKey>,
    review: PullRequestKey? = null,
    deliver: suspend (List<DraftEntity>) -> Unit,
  ): Deferred<Boolean> {
    val editors = keys.distinctBy { it.accountId to it.value }.map(::writer).toMutableList()
    val reserved = mutableListOf<DraftWriter>()
    for (editor in editors) {
      if (!editor.reserve()) {
        reserved.forEach { it.locked.value = false }
        return CompletableDeferred(false)
      }
      reserved += editor
    }
    return scope.async {
      var ids = emptyList<Long>()
      try {
        recovery.await()
        editors.forEach { it.flush() }
        if (review != null) {
          val otherWriters =
            synchronized(writers) {
              writers.values
                .filter {
                  it.key.accountId == review.accountId &&
                    it.key.repo == review.repo &&
                    it.key.number == review.number
                }
                .toList()
            }
          otherWriters.forEach { it.flush() }
          val rows = dao.all(review.accountId, review.repo.owner, review.repo.name, review.number)
          for (row in rows.filter { it.inReview }) {
            val editor = writer(row.draftKey)
            if (editor in editors) continue
            check(editor.reserve()) { "Already submitting" }
            editors += editor
          }
          editors.forEach { it.flush() }
        }
        val rows = db.withWriteTransaction {
          val rows = editors.map {
            it.ensure()
            checkNotNull(dao.get(it.key.accountId, it.key.value))
          }
          check(rows.none { it.submitting }) { "Already submitting" }
          ids = rows.map { it.id }
          dao.submitting(ids, true)
          rows
        }
        deliver(rows)
        accepted(rows)
        true
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        dao.submitting(ids, false)
        editors.forEach {
          if (dao.get(it.key.accountId, it.key.value) != null)
            it.error.value = e.message ?: "Couldn't submit"
        }
        false
      } finally {
        editors.forEach { it.locked.value = false }
      }
    }
  }

  fun hasDraft(accountId: Long, repo: RepoRef, number: Int): Flow<Boolean> =
    dao.observeHasDraft(accountId, repo.owner, repo.name, number).distinctUntilChanged()

  fun threadsWithDrafts(): Flow<Set<DraftThread>> =
    dao.observeDraftThreads().map { rows ->
      rows.map { DraftThread(it.accountId, RepoRef(it.repoOwner, it.repoName), it.number) }.toSet()
    }
}
