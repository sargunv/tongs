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
  fun keepsThreadsNoLongerUnreadAsReadElsewhere() = dbTest { db ->
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

    // A partial poll covers threads newer than its oldest: 2 was read or done elsewhere,
    // and 3 isn't covered.
    inbox.polls +=
      changed(thread("1", START - 1.hours), thread("4", START - 150.minutes), complete = false)
    sync.poll(account)
    assertEquals(setOf("1", "2", "3", "4"), dao.threads(account).map { it.id }.toSet())
    assertEquals(setOf("2"), readElsewhere(db, account))

    // A complete poll covers everything, and still nothing leaves.
    inbox.polls += changed(thread("1", START))
    sync.poll(account)
    assertEquals(setOf("1", "2", "3", "4"), dao.threads(account).map { it.id }.toSet())
    assertEquals(setOf("2", "3", "4"), readElsewhere(db, account))
    assertEquals(listOf("1"), dao.threads(account).filter { it.unread }.map { it.id })
  }

  @Test
  fun aPartialPollDoesNotCoverThreadsTiedWithItsOldest() = dbTest { db ->
    val (account, clients) = signIn(db, inbox)
    val sync = InboxSync(db, clients, virtualClock())
    inbox.polls += changed(thread("1", START - 1.hours), thread("2", START - 2.hours))
    sync.poll(account)

    // The poll stopped at its page limit inside a run of threads updated at the same time as 2, so
    // 2 may be on the next page, still unread. 1 is newer than the run, so the poll covers it.
    inbox.polls +=
      changed(thread("3", START), thread("4", START - 2.hours), complete = false, cursor = "c2")
    sync.poll(account)
    assertEquals(setOf("1"), readElsewhere(db, account))
    assertEquals(true, db.inbox().thread(account, "2")!!.unread)
  }

  @Test
  fun newActivityClearsReadElsewhere() = dbTest { db ->
    val (account, clients) = signIn(db, inbox)
    val sync = InboxSync(db, clients, virtualClock())
    inbox.polls += changed(thread("1", START - 1.hours))
    sync.poll(account)
    inbox.polls += changed(cursor = "c2")
    sync.refresh()
    assertEquals(setOf("1"), readElsewhere(db, account))

    // Unread again: new activity, so it's back in the inbox as unread, without the mark.
    inbox.polls += changed(thread("1", START), cursor = "c3")
    sync.refresh()
    val row = InboxRepository(db, clients, backgroundScope, virtualClock()).inbox().first().single()
    assertEquals(true, row.isUnread)
    assertEquals(false, row.readElsewhere)
    assertEquals(false, db.inbox().thread(account, "1")!!.readElsewhere)
  }

  @Test
  fun readingHereClearsReadElsewhere() = dbTest { db ->
    val (account, clients) = signIn(db, inbox)
    val sync = InboxSync(db, clients, virtualClock())
    val repository = InboxRepository(db, clients, backgroundScope, virtualClock())
    inbox.polls += changed(thread("1", START - 1.hours), thread("2", START - 2.hours))
    sync.poll(account)
    inbox.polls += changed(cursor = "c2")
    sync.refresh()
    assertEquals(setOf("1", "2"), readElsewhere(db, account))

    // Opening a row read elsewhere clears its mark locally: the forge already has it read.
    repository.acknowledgeReadElsewhere(ThreadKey(account, "1"))
    // Marking read here clears it too.
    repository.markRead(ThreadKey(account, "2"))
    runCurrent()
    assertEquals(emptySet(), readElsewhere(db, account))
    assertEquals(listOf("read 2"), inbox.calls)
    assertEquals(listOf("1", "2"), repository.inbox().first().map { it.id })
  }

  @Test
  fun aThreadLeavesOnlyWhenDoneHere() = dbTest { db ->
    val dao = db.inbox()
    val (account, clients) = signIn(db, inbox)
    val sync = InboxSync(db, clients, virtualClock())
    val repository = InboxRepository(db, clients, backgroundScope, virtualClock())
    inbox.polls += changed(thread("1", START - 1.hours), thread("2", START - 2.hours))
    sync.poll(account)
    inbox.polls += changed(cursor = "c2")
    sync.refresh()
    assertEquals(setOf("1", "2"), dao.threads(account).map { it.id }.toSet())

    repository.markDone(listOf(ThreadKey(account, "1")))
    advanceTimeBy(repository.undoWindow + 1.seconds)
    runCurrent()
    assertEquals(listOf("done 1"), inbox.calls)
    // Polls that don't list it keep it gone, and the rest stay.
    inbox.polls += changed(cursor = "c3")
    sync.refresh()
    assertEquals(listOf("2"), dao.threads(account).map { it.id })
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

  @Test
  fun asksForTheWholeInboxNowAndThen() = dbTest { db ->
    val (account, clients) = signIn(db, inbox)
    val sync = InboxSync(db, clients, virtualClock())
    inbox.polls += changed(thread("1", START - 1.hours), cursor = "c1")
    // The session's first poll is full, then polls send the cursor.
    sync.poll(account)
    sync.poll(account)
    advanceTimeBy(InboxSync.FULL_POLL_INTERVAL)
    sync.poll(account)
    sync.poll(account)
    // A refresh is always full.
    sync.refresh()
    assertEquals(listOf(null, "c1", null, "c1", null), inbox.cursors)
  }

  @Test
  fun marksOnlyThreadsReadElsewhere() = dbTest { db ->
    val dao = db.inbox()
    val (account, clients) = signIn(db, inbox)
    val sync = InboxSync(db, clients, virtualClock())
    val repository = InboxRepository(db, clients, backgroundScope, virtualClock())
    inbox.polls +=
      changed(
        thread("1", START - 1.hours),
        thread("2", START - 2.hours),
        thread("3", START - 3.hours),
      )
    sync.poll(account)
    // 1 is read here, 2 snoozed here; 3 is read on the web.
    repository.markRead(ThreadKey(account, "1"))
    runCurrent()
    repository.snooze(ThreadKey(account, "2"), START + 1.hours)

    // GitHub lists only unread threads, and none are unread now.
    inbox.polls += changed()
    sync.refresh()
    assertEquals(setOf("1", "2", "3"), dao.threads(account).map { it.id }.toSet())
    assertEquals(false, dao.thread(account, "2")!!.unread)
    // Only 3 says so; 1 was read here, and 2 shows unread when it's back.
    val rows = repository.inbox().first().associateBy { it.id }
    assertEquals(false, rows.getValue("1").readElsewhere)
    assertEquals(true, rows.getValue("3").readElsewhere)
    advanceTimeBy(2.hours)
    val back = repository.inbox().first().single { it.id == "2" }
    assertEquals(true, back.isUnread)
    assertEquals(false, back.readElsewhere)
  }

  private suspend fun readElsewhere(db: TongsDatabase, account: Long) =
    db.inbox().threads(account).filter { it.readElsewhere }.map { it.id }.toSet()
}
