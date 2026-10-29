package dev.sargunv.tongs.core.data

import androidx.room3.withWriteTransaction
import dev.sargunv.tongs.forge.api.Capability
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Identifies a thread across accounts. */
data class ThreadKey(val accountId: Long, val threadId: String)

/**
 * An action that can still be taken back, for an undo snackbar. [undo] does nothing once the window
 * has passed and the forge has been told.
 */
fun interface Undo {
  suspend fun undo()
}

/**
 * The unified inbox, from the database, and the triage actions on it. Actions are optimistic: the
 * database changes at once, so the UI updates, and the forge call follows in [scope]. If the forge
 * refuses, the change is undone and [failures] says why.
 *
 * Done waits [undoWindow] before telling the forge, since GitHub has no way to bring a thread back
 * once it's done. Snooze and unread are local, so they can always be undone. A done or read the app
 * didn't get to send, because it was closed, goes out the next time the repository starts.
 */
@SingleIn(AppScope::class)
@Inject
class InboxRepository(
  private val db: TongsDatabase,
  private val clients: ForgeClients,
  private val scope: CoroutineScope,
  private val clock: Clock,
) {
  private val dao = db.inbox()
  private val _failures = MutableSharedFlow<String>(extraBufferCapacity = 8)
  private val inFlight = Mutex()
  /** Threads with a forge call running in this process. */
  private val scheduled = mutableSetOf<ThreadKey>()
  /** Threads whose sends came while their call ran, so the call checks again once it's done. */
  private val again = mutableSetOf<ThreadKey>()
  /** Threads marked done in this process whose undo window is still open. */
  private val held = mutableSetOf<ThreadKey>()

  /** How long a done can be undone before the forge is told. */
  var undoWindow: Duration = UNDO_WINDOW

  /** Messages for actions the forge refused. */
  val failures: SharedFlow<String> = _failures

  init {
    scope.launch { sendPending() }
  }

  /** Every visible thread, latest first; snoozed threads appear when their time comes. */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun inbox(): Flow<List<InboxRow>> =
    ticks().flatMapLatest { now -> dao.observeInbox(now) }.distinctUntilChanged()

  /** Threads snoozed for later, soonest back first. */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun snoozed(): Flow<List<InboxRow>> =
    ticks().flatMapLatest { now -> dao.observeSnoozed(now) }.distinctUntilChanged()

  /** Each account's sync status, in sign-in order. */
  fun syncStatus(): Flow<List<AccountSyncStatus>> =
    combine(db.accounts().observeAll(), dao.observeSyncStates()) { accounts, states ->
        val byAccount = states.associateBy { it.accountId }
        accounts.map { account ->
          byAccount[account.id]?.status(account.login)
            ?: AccountSyncStatus(account.id, account.login, null, emptyList(), null)
        }
      }
      .distinctUntilChanged()

  fun thread(key: ThreadKey): Flow<InboxThreadEntity?> =
    dao.observeThread(key.accountId, key.threadId)

  /** The current time, then again whenever a snooze ends, and once a minute regardless. */
  private fun ticks(): Flow<Long> = flow {
    while (true) {
      val now = clock.now().toEpochMilliseconds()
      emit(now)
      val wake = dao.observeNextWake(now).first() ?: Long.MAX_VALUE
      delay((wake - now).coerceIn(0, 1.minutes.inWholeMilliseconds))
    }
  }

  suspend fun markRead(key: ThreadKey) {
    dao.markReadPending(key.accountId, key.threadId)
    scope.launch { send(key) }
  }

  /**
   * Clears a read thread's read-elsewhere mark once it's been seen here, as opening it does. Local:
   * the forge already has it read, or done.
   */
  suspend fun acknowledgeReadElsewhere(key: ThreadKey) =
    dao.clearReadElsewhere(key.accountId, key.threadId)

  /**
   * Hides [keys] at once and marks them done on the forge after [window], unless [Undo.undo] comes
   * first.
   */
  suspend fun markDone(keys: List<ThreadKey>, window: Duration = undoWindow): Undo {
    inFlight.withLock { held += keys }
    db.withWriteTransaction {
      for (key in keys) dao.setPendingDone(key.accountId, key.threadId, true)
    }
    val sending = CompletableDeferred<Unit>()
    val job = scope.launch {
      delay(window)
      sending.complete(Unit)
      inFlight.withLock { held -= keys.toSet() }
      for (key in keys) send(key)
    }
    return Undo {
      if (sending.isCompleted) return@Undo
      job.cancel()
      inFlight.withLock { held -= keys.toSet() }
      db.withWriteTransaction {
        for (key in keys) dao.setPendingDone(key.accountId, key.threadId, false)
      }
    }
  }

  suspend fun markDone(key: ThreadKey): Undo = markDone(listOf(key))

  /**
   * Marks a thread unread on this device, until it's read again. No forge has an API for it yet;
   * one that does would advertise [Capability.InboxMarkUnread].
   */
  suspend fun markUnread(key: ThreadKey): Undo {
    dao.markUnreadLocally(key.accountId, key.threadId)
    return Undo { dao.restoreSnooze(key.accountId, key.threadId, null, localUnread = false) }
  }

  /** Hides a thread until [until], when it comes back unread. Local: forges have no snooze. */
  suspend fun snooze(key: ThreadKey, until: Instant): Undo {
    val before = dao.thread(key.accountId, key.threadId)
    dao.snooze(key.accountId, key.threadId, until.toEpochMilliseconds())
    return Undo {
      if (before != null) {
        dao.restoreSnooze(key.accountId, key.threadId, before.snoozedUntil, before.localUnread)
      }
    }
  }

  /** Brings a snoozed thread back now, unread. */
  suspend fun unsnooze(key: ThreadKey) =
    dao.restoreSnooze(key.accountId, key.threadId, null, localUnread = true)

  /**
   * Sends what's pending for [key]: a done, or else a read. Confirms it, or undoes it. One call at
   * a time per thread: a send that comes while one runs, as a done after a read still on its way,
   * goes once that one is through.
   */
  private suspend fun send(key: ThreadKey) {
    inFlight.withLock {
      if (!scheduled.add(key)) {
        again += key
        return
      }
    }
    try {
      do {
        sendOnce(key)
        val more = inFlight.withLock { again.remove(key).also { if (!it) scheduled.remove(key) } }
      } while (more)
    } catch (e: Throwable) {
      withContext(NonCancellable) {
        inFlight.withLock {
          scheduled.remove(key)
          again.remove(key)
        }
      }
      throw e
    }
  }

  private suspend fun sendOnce(key: ThreadKey) {
    val thread = dao.thread(key.accountId, key.threadId) ?: return
    val done = thread.pendingDone && inFlight.withLock { key !in held }
    if (!done && !thread.pendingRead) return
    try {
      val inbox = clients.client(key.accountId)?.inbox ?: error("account is signed out")
      if (done) inbox.markDone(key.threadId) else inbox.markRead(key.threadId)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (done) dao.setPendingDone(key.accountId, key.threadId, false)
      else dao.revertRead(key.accountId, key.threadId)
      _failures.tryEmit("Couldn't mark as ${if (done) "done" else "read"}: ${e.message}")
      return
    }
    if (done) dao.delete(key.accountId, listOf(key.threadId))
    else dao.confirmRead(key.accountId, key.threadId)
  }

  /** Sends reads and dones left pending by an earlier run of the app. */
  private suspend fun sendPending() {
    for (thread in dao.pending()) {
      val key = ThreadKey(thread.accountId, thread.id)
      if (inFlight.withLock { key in held }) continue
      scope.launch { send(key) }
    }
  }

  companion object {
    val UNDO_WINDOW: Duration = 5.seconds
  }
}
