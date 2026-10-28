package dev.sargunv.tongs.core.data

import dev.sargunv.tongs.forge.api.Capability
import dev.sargunv.tongs.forge.api.InboxSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Identifies a thread across accounts. */
data class ThreadKey(val accountId: Long, val threadId: String)

/**
 * The unified inbox, from the database, and the triage actions on it. Actions are optimistic: the
 * database changes at once, so the UI updates, and the forge call follows in [scope]. If the forge
 * refuses, the change is undone and [failures] says why.
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

  /** Messages for actions the forge refused. */
  val failures: SharedFlow<String> = _failures

  /** Every visible thread, latest first; snoozed threads appear when their time comes. */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun inbox(): Flow<List<InboxRow>> =
    ticks().flatMapLatest { now -> dao.observeInbox(now) }.distinctUntilChanged()

  /** Why the last poll failed, for each account whose last poll did. */
  fun syncErrors(): Flow<List<String>> =
    dao.observeSyncStates().map { states -> states.mapNotNull { it.lastError } }

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

  suspend fun markRead(key: ThreadKey) =
    optimistic(
      key,
      apply = { dao.markReadPending(key.accountId, key.threadId) },
      call = { it.markRead(key.threadId) },
      confirm = { dao.confirmRead(key.accountId, key.threadId) },
      revert = { dao.revertRead(key.accountId, key.threadId) },
      what = "mark as read",
    )

  suspend fun markDone(key: ThreadKey) =
    optimistic(
      key,
      apply = { dao.setPendingDone(key.accountId, key.threadId, true) },
      call = { it.markDone(key.threadId) },
      confirm = { dao.delete(key.accountId, listOf(key.threadId)) },
      revert = { dao.setPendingDone(key.accountId, key.threadId, false) },
      what = "mark as done",
    )

  /**
   * Marks a thread unread on this device, until it's read again. No forge has an API for it yet;
   * one that does would advertise [Capability.InboxMarkUnread].
   */
  suspend fun markUnread(key: ThreadKey) = dao.markUnreadLocally(key.accountId, key.threadId)

  /** Hides a thread until [until], when it comes back unread. Local: forges have no snooze. */
  suspend fun snooze(key: ThreadKey, until: Instant) =
    dao.snooze(key.accountId, key.threadId, until.toEpochMilliseconds())

  /** Applies a change locally now, then calls the forge in the background. */
  private suspend fun optimistic(
    key: ThreadKey,
    apply: suspend () -> Unit,
    call: suspend (InboxSource) -> Unit,
    confirm: suspend () -> Unit,
    revert: suspend () -> Unit,
    what: String,
  ) {
    apply()
    scope.launch {
      val inbox = clients.client(key.accountId)?.inbox
      val result = runCatching { inbox?.let { call(it) } ?: error("account is signed out") }
      result
        .onSuccess { confirm() }
        .onFailure {
          revert()
          _failures.tryEmit("Couldn't $what: ${it.message}")
        }
    }
  }
}
