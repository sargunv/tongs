package dev.sargunv.tongs.core.data

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Query
import androidx.room3.Upsert
import dev.sargunv.tongs.forge.api.RepoRef
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Where a draft belongs: a thread, and a [slot] within it. A thread's reply is the `reply` slot;
 * other composers, like a line comment in a PR's changes, pick their own slot, such as
 * `review/src/main.rs:42`. Drafts are kept per account, since each account replies as itself.
 */
data class DraftKey(
  val accountId: Long,
  val repo: RepoRef,
  val number: Int,
  val slot: String = REPLY,
) {
  /** The key stored in the database. */
  val value: String
    get() = "${repo.owner}/${repo.name}#$number/$slot"

  companion object {
    const val REPLY = "reply"
  }
}

/** An issue or PR that has at least one draft. */
data class DraftThread(val accountId: Long, val repo: RepoRef, val number: Int)

/** Text written but not yet sent, like an email draft. Survives restarts. */
@Entity(
  tableName = "draft",
  primaryKeys = ["accountId", "key"],
  foreignKeys =
    [
      ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE,
      ),
    ],
  indices = [Index("accountId", "repoOwner", "repoName", "number")],
)
data class DraftEntity(
  val accountId: Long,
  /** [DraftKey.value]. */
  val key: String,
  val repoOwner: String,
  val repoName: String,
  val number: Int,
  val slot: String,
  val body: String,
  /** Epoch milliseconds. */
  val updatedAt: Long,
)

@Dao
interface DraftDao {
  @Query("SELECT body FROM draft WHERE accountId = :accountId AND `key` = :key")
  fun observeBody(accountId: Long, key: String): Flow<String?>

  @Query("SELECT body FROM draft WHERE accountId = :accountId AND `key` = :key")
  suspend fun body(accountId: Long, key: String): String?

  @Upsert suspend fun upsert(draft: DraftEntity)

  @Query("DELETE FROM draft WHERE accountId = :accountId AND `key` = :key")
  suspend fun delete(accountId: Long, key: String)

  @Query(
    """
    SELECT COUNT(*) > 0 FROM draft
    WHERE accountId = :accountId AND repoOwner = :owner AND repoName = :name AND number = :number AND slot != 'cache/review'
    """,
  )
  fun observeHasDraft(accountId: Long, owner: String, name: String, number: Int): Flow<Boolean>

  @Query(
    "SELECT DISTINCT accountId, repoOwner, repoName, number FROM draft WHERE slot != 'cache/review'",
  )
  fun observeDraftThreads(): Flow<List<DraftThreadRow>>

  @Query(
    """
    SELECT slot, body FROM draft
    WHERE accountId = :accountId AND repoOwner = :owner AND repoName = :name AND number = :number
      AND substr(slot, 1, length(:prefix)) = :prefix
    """,
  )
  fun observeSlots(
    accountId: Long,
    owner: String,
    name: String,
    number: Int,
    prefix: String,
  ): Flow<List<DraftSlotRow>>
}

data class DraftSlotRow(val slot: String, val body: String)

data class DraftThreadRow(
  val accountId: Long,
  val repoOwner: String,
  val repoName: String,
  val number: Int,
)

/**
 * Drafts for every composer. Saving blank text deletes the draft, so an emptied composer leaves
 * nothing behind. The inbox reads [threadsWithDrafts] to mark rows, like an email client.
 */
@SingleIn(AppScope::class)
@Inject
class DraftRepository(
  db: TongsDatabase,
  private val scope: CoroutineScope,
  private val clock: Clock,
) {
  private val dao = db.drafts()

  fun observe(key: DraftKey): Flow<String?> =
    dao.observeBody(key.accountId, key.value).distinctUntilChanged()

  suspend fun get(key: DraftKey): String? = dao.body(key.accountId, key.value)

  suspend fun save(key: DraftKey, body: String) {
    if (body.isBlank()) {
      dao.delete(key.accountId, key.value)
      return
    }
    dao.upsert(
      DraftEntity(
        accountId = key.accountId,
        key = key.value,
        repoOwner = key.repo.owner,
        repoName = key.repo.name,
        number = key.number,
        slot = key.slot,
        body = body,
        updatedAt = clock.now().toEpochMilliseconds(),
      ),
    )
  }

  /** Saves without waiting, and outlives the caller: for a composer that's closing. */
  fun saveSoon(key: DraftKey, body: String) {
    scope.launch { save(key, body) }
  }

  /** Adds [text] to the end of the draft, after a blank line, as when quoting into a reply. */
  suspend fun append(key: DraftKey, text: String) {
    val existing = get(key)?.trimEnd()
    save(key, if (existing.isNullOrEmpty()) text else "$existing\n\n$text")
  }

  suspend fun delete(key: DraftKey) = dao.delete(key.accountId, key.value)

  /** Whether the issue or PR has a draft in any slot. */
  fun hasDraft(accountId: Long, repo: RepoRef, number: Int): Flow<Boolean> =
    dao.observeHasDraft(accountId, repo.owner, repo.name, number).distinctUntilChanged()

  /**
   * The drafts on one issue or PR whose slot starts with [prefix], by slot: for a screen with many
   * composers, like a PR's changes, to show where drafts were left.
   */
  fun slots(
    accountId: Long,
    repo: RepoRef,
    number: Int,
    prefix: String,
  ): Flow<Map<String, String>> =
    dao
      .observeSlots(accountId, repo.owner, repo.name, number, prefix)
      .map { rows -> rows.associate { it.slot to it.body } }
      .distinctUntilChanged()

  /** Every issue or PR with a draft, across accounts. */
  fun threadsWithDrafts(): Flow<Set<DraftThread>> =
    dao.observeDraftThreads().map { rows ->
      rows.map { DraftThread(it.accountId, RepoRef(it.repoOwner, it.repoName), it.number) }.toSet()
    }
}
