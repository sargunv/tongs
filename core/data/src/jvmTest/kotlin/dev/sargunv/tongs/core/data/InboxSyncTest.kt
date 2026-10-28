package dev.sargunv.tongs.core.data

import dev.sargunv.tongs.forge.api.InboxPoll
import dev.sargunv.tongs.forge.api.InboxSource
import dev.sargunv.tongs.forge.api.InboxThread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

class InboxSyncTest {
  private val inbox = FakeInbox()

  private fun changed(
    vararg threads: InboxThread,
    complete: Boolean = true,
    cursor: String = "c1",
  ) = InboxPoll.Changed(threads.toList(), complete, cursor, 60.seconds)

  @Test
  fun sendsTheLastCursorAndKeepsDataOnNotModified() = dbTest { db ->
    val dao = db.inbox()
    val (account, clients) = signIn(db, inbox)
    val sync = InboxSync(db, clients, virtualClock())
    inbox.polls += changed(thread("1", START - 1.hours), cursor = "Mon, 1")
    inbox.polls += InboxPoll.NotModified(90.seconds)

    sync.poll(account)
    sync.poll(account)

    assertEquals(listOf(null, "Mon, 1"), inbox.cursors)
    assertEquals(listOf("1"), dao.threads(account).map { it.id })
    val state = dao.syncState(account)!!
    assertEquals("Mon, 1", state.cursor)
    assertEquals(90, state.pollIntervalSeconds)
    assertNull(state.lastError)
  }

  @Test
  fun pollsNoMoreOftenThanTheForgeAsks() = dbTest { db ->
    val dao = db.inbox()
    val (account, clients) = signIn(db, inbox)
    val sync = InboxSync(db, clients, virtualClock())
    inbox.polls += changed(thread("1", START))
    val polling = launch { sync.pollWhileActive() }

    runCurrent()
    assertEquals(1, inbox.cursors.size)
    advanceTimeBy(59.seconds)
    assertEquals(1, inbox.cursors.size)
    advanceTimeBy(2.seconds)
    assertEquals(2, inbox.cursors.size)
    // The forge asks for longer.
    inbox.polls += InboxPoll.NotModified(300.seconds)
    advanceTimeBy(61.seconds)
    assertEquals(3, inbox.cursors.size)
    advanceTimeBy(240.seconds)
    assertEquals(3, inbox.cursors.size)
    advanceTimeBy(61.seconds)
    assertEquals(4, inbox.cursors.size)
    polling.cancel()

    // A new session doesn't poll before the interval is up, either.
    val again = launch { InboxSync(db, clients, virtualClock()).pollWhileActive() }
    runCurrent()
    assertEquals(4, inbox.cursors.size)
    again.cancel()
  }

  @Test
  fun backsOffAfterAnError() = dbTest { db ->
    val dao = db.inbox()
    val failing =
      object : InboxSource by FakeInbox() {
        override suspend fun poll(cursor: String?): InboxPoll = error("offline")
      }
    val (account, clients) = signIn(db, failing)
    val sync = InboxSync(db, clients, virtualClock())
    sync.poll(account)
    assertEquals("offline", dao.syncState(account)!!.lastError)
    assertEquals(START + InboxSync.ERROR_BACKOFF, sync.dueAt(account))
  }

  @Test
  fun deletesThreadsDoneElsewhere() = dbTest { db ->
    val dao = db.inbox()
    val (account, clients) = signIn(db, inbox)
    val sync = InboxSync(db, clients, virtualClock())
    inbox.polls +=
      changed(
        thread("1", START - 1.hours),
        thread("2", START - 2.hours),
        thread("3", START - 3.hours),
      )
    sync.poll(account)

    // A partial poll covers threads at least as new as its oldest: 2 is gone, and 3 isn't covered.
    inbox.polls +=
      changed(thread("1", START - 1.hours), thread("4", START - 150.minutes), complete = false)
    sync.poll(account)
    assertEquals(setOf("1", "3", "4"), dao.threads(account).map { it.id }.toSet())

    // A complete poll covers everything.
    inbox.polls += changed(thread("1", START))
    sync.poll(account)
    assertEquals(listOf("1"), dao.threads(account).map { it.id })
  }

  @Test
  fun keepsAnUnconfirmedReadAcrossSyncs() = dbTest { db ->
    val dao = db.inbox()
    val (account, clients) = signIn(db, inbox)
    val sync = InboxSync(db, clients, virtualClock())
    inbox.polls += changed(thread("1", START - 1.hours))
    sync.poll(account)

    inbox.gate = CompletableDeferred()
    val repository = InboxRepository(db, clients, backgroundScope, virtualClock())
    repository.markRead(ThreadKey(account, "1"))
    // The forge hasn't answered, and a poll still says unread.
    inbox.polls += changed(thread("1", START - 1.hours), cursor = "c2")
    sync.poll(account)
    assertEquals(false, dao.thread(account, "1")!!.unread)

    inbox.gate!!.complete(Unit)
    runCurrent()
    assertEquals(false, dao.thread(account, "1")!!.pendingRead)
    assertEquals(false, repository.inbox().first().single().isUnread)
  }
}
