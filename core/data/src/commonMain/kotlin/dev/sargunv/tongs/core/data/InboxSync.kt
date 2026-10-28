package dev.sargunv.tongs.core.data

import androidx.room3.withWriteTransaction
import dev.sargunv.tongs.forge.api.InboxPoll
import dev.sargunv.tongs.forge.api.InboxThread
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Syncs each account's inbox into the database. Polls are conditional on the forge's cursor, so an
 * unchanged inbox costs GitHub a 304, and each account waits as long as its forge's poll interval
 * (`X-Poll-Interval`) asks between polls. [pollWhileActive] runs only while the app is in the
 * foreground: there are no push notifications, so there's no reason to poll in the background.
 */
@SingleIn(AppScope::class)
@Inject
class InboxSync(
  private val db: TongsDatabase,
  private val clients: ForgeClients,
  private val clock: Clock,
) {
  private val dao = db.inbox()
  private val lock = Mutex()
  private val _refreshing = MutableStateFlow(false)

  /** True while [refresh] runs. */
  val refreshing: StateFlow<Boolean> = _refreshing

  /**
   * Polls every account whenever it's due, until cancelled. Polls at once for accounts whose
   * interval has passed since their last poll, including in an earlier session.
   */
  suspend fun pollWhileActive() {
    db
      .accounts()
      .observeAll()
      .map { rows -> rows.map { it.id } }
      .distinctUntilChanged()
      .collectLatest { accountIds ->
        while (true) {
          var next = Instant.DISTANT_FUTURE
          for (id in accountIds) {
            var due = dueAt(id)
            if (due <= clock.now()) {
              poll(id)
              due = dueAt(id)
            }
            if (due < next) next = due
          }
          if (next == Instant.DISTANT_FUTURE) return@collectLatest
          delay(next - clock.now())
        }
      }
  }

  /** Polls every account now, whatever their intervals, as when the user pulls to refresh. */
  suspend fun refresh() {
    _refreshing.value = true
    try {
      for (account in db.accounts().all()) poll(account.id)
    } finally {
      _refreshing.value = false
    }
  }

  /** When [accountId] may next be polled. */
  internal suspend fun dueAt(accountId: Long): Instant {
    val state = dao.syncState(accountId) ?: return Instant.DISTANT_PAST
    val last =
      state.lastPolledAt?.let(Instant::fromEpochMilliseconds) ?: return Instant.DISTANT_PAST
    val interval = state.pollIntervalSeconds?.seconds ?: DEFAULT_POLL_INTERVAL
    // After an error, back off to at least the default.
    return last + if (state.lastError != null) maxOf(interval, ERROR_BACKOFF) else interval
  }

  /** Polls one account once, conditionally, and stores the result. */
  suspend fun poll(accountId: Long) = lock.withLock {
    val state = dao.syncState(accountId)
    val now = clock.now().toEpochMilliseconds()
    val result =
      try {
        // Without a token or the capability, record the error, so the account isn't retried
        // until its backoff passes.
        val client = clients.client(accountId) ?: error("No token for this account")
        val inbox = client.inbox ?: error("This account's token can't read notifications")
        inbox.poll(state?.cursor)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        dao.upsertSyncState(
          InboxSyncEntity(
            accountId = accountId,
            cursor = state?.cursor,
            pollIntervalSeconds = state?.pollIntervalSeconds,
            lastPolledAt = now,
            lastError = e.message ?: e.toString(),
          ),
        )
        return@withLock
      }
    val interval = result.pollInterval?.inWholeSeconds ?: state?.pollIntervalSeconds
    when (result) {
      is InboxPoll.NotModified ->
        dao.upsertSyncState(InboxSyncEntity(accountId, state?.cursor, interval, now, null))
      is InboxPoll.Changed -> {
        db.withWriteTransaction {
          merge(accountId, result)
          dao.upsertSyncState(InboxSyncEntity(accountId, result.cursor, interval, now, null))
        }
        hydrate(accountId, result.threads)
      }
    }
  }

  /**
   * Writes the forge's view of each thread, keeping local state, and deletes threads the poll shows
   * were marked done elsewhere: those it covers but doesn't include.
   */
  private suspend fun merge(accountId: Long, poll: InboxPoll.Changed) {
    val existing = dao.threads(accountId).associateBy { it.id }
    dao.upsert(poll.threads.map { it.toEntity(accountId, existing[it.id]) })
    val seen = poll.threads.mapTo(HashSet()) { it.id }
    val coveredFrom = poll.threads.minOfOrNull { it.updatedAt.toEpochMilliseconds() }
    val gone =
      existing.values.filter {
        it.id !in seen && (poll.complete || (coveredFrom != null && it.updatedAt >= coveredFrom))
      }
    if (gone.isNotEmpty()) dao.delete(accountId, gone.map { it.id })
  }

  private suspend fun hydrate(accountId: Long, threads: List<InboxThread>) {
    val hydrator = clients.client(accountId)?.inboxHydrator ?: return
    if (threads.isEmpty()) return
    val hydrations =
      try {
        hydrator.hydrate(threads)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        return
      }
    for (h in hydrations) dao.setHydration(accountId, h.threadId, h.subjectState, h.whatsNew)
  }

  companion object {
    /** GitHub's usual `X-Poll-Interval`, for forges that don't say. */
    val DEFAULT_POLL_INTERVAL: Duration = 60.seconds
    val ERROR_BACKOFF: Duration = 120.seconds
  }
}

/** The forge's fields from [thread], with local state carried over from [old]. */
internal fun InboxThread.toEntity(accountId: Long, old: InboxThreadEntity?): InboxThreadEntity {
  val updated = updatedAt.toEpochMilliseconds()
  return InboxThreadEntity(
    accountId = accountId,
    id = id,
    repoOwner = repo.owner,
    repoName = repo.name,
    title = subject.title,
    subjectType = subject.type.raw,
    subjectNumber = subject.number,
    webUrl = subject.webUrl,
    reason = reason.raw,
    // A read the forge hasn't confirmed yet stays read.
    unread = unread && old?.pendingRead != true,
    updatedAt = updated,
    lastReadAt = lastReadAt?.toEpochMilliseconds(),
    pendingRead = old?.pendingRead ?: false,
    pendingDone = old?.pendingDone ?: false,
    localUnread = old?.localUnread ?: false,
    snoozedUntil = old?.snoozedUntil,
    // Hydration describes the thread as of its last update; a newer update makes it stale.
    subjectState = old?.subjectState,
    whatsNew = old?.whatsNew?.takeIf { old.updatedAt == updated },
  )
}
