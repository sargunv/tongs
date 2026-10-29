package dev.sargunv.tongs.core.data

import app.cash.turbine.test
import dev.sargunv.tongs.forge.api.InboxPoll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

class InboxRepositoryTest {
  private val inbox = FakeInbox()

  /** Signs in, syncs threads 1 and 2, and holds forge calls until [FakeInbox.gate] completes. */
  private suspend fun TestScope.setUp(db: TongsDatabase): Pair<Long, InboxRepository> {
    val (account, clients) = signIn(db, inbox)
    inbox.polls +=
      InboxPoll.Changed(
        listOf(thread("1", START - 1.hours), thread("2", START - 2.hours)),
        complete = true,
        cursor = "c",
        pollInterval = 60.seconds,
      )
    InboxSync(db, clients, virtualClock()).poll(account)
    inbox.gate = CompletableDeferred()
    val repository = InboxRepository(db, clients, backgroundScope, virtualClock())
    // These tests are about what happens once the forge is told; undo has its own.
    repository.undoWindow = Duration.ZERO
    return account to repository
  }

  private suspend fun InboxRepository.visible() = inbox().first().map { it.id }

  @Test
  fun doneHidesAtOnceAndDeletesWhenConfirmed() = dbTest { db ->
    val dao = db.inbox()
    val (account, repository) = setUp(db)
    repository.markDone(ThreadKey(account, "1"))
    assertEquals(listOf("2"), repository.visible())
    assertEquals(true, dao.thread(account, "1")!!.pendingDone)

    inbox.gate!!.complete(Unit)
    runCurrent()
    assertEquals(listOf("done 1"), inbox.calls)
    assertNull(dao.thread(account, "1"))
  }

  @Test
  fun doneGoesOutAfterAReadStillInFlight() = dbTest { db ->
    val dao = db.inbox()
    val (account, repository) = setUp(db)
    repository.undoWindow = 5.seconds
    // Opened, so marked read, then done while the read is still on its way.
    repository.markRead(ThreadKey(account, "1"))
    runCurrent()
    repository.markDone(ThreadKey(account, "1"))
    advanceTimeBy(6.seconds)
    runCurrent()
    assertEquals(listOf("read 1"), inbox.calls)

    inbox.gate!!.complete(Unit)
    runCurrent()
    assertEquals(listOf("read 1", "done 1"), inbox.calls)
    assertNull(dao.thread(account, "1"))
  }

  @Test
  fun drainingAReadKeepsDoneInsideItsUndoWindow() = dbTest { db ->
    val (account, repository) = setUp(db)
    val key = ThreadKey(account, "1")
    repository.markRead(key)
    runCurrent()
    repository.markRead(key)
    runCurrent()
    repository.undoWindow = Duration.INFINITE
    val undo = repository.markDone(key)
    inbox.gate!!.complete(Unit)
    runCurrent()
    assertEquals(listOf("read 1"), inbox.calls)
    assertEquals(true, db.inbox().thread(account, "1")!!.pendingDone)
    undo.undo()
    assertEquals(false, db.inbox().thread(account, "1")!!.pendingDone)
  }

  @Test
  fun doneComesBackIfTheForgeRefuses() = dbTest { db ->
    val dao = db.inbox()
    val (account, repository) = setUp(db)
    repository.failures.test {
      repository.markDone(ThreadKey(account, "1"))
      assertEquals(listOf("2"), repository.visible())
      inbox.gate!!.completeExceptionally(IllegalStateException("403"))
      assertEquals("Couldn't mark as done: 403", awaitItem())
    }
    assertEquals(listOf("1", "2"), repository.visible())
  }

  @Test
  fun readShowsAtOnceAndRevertsIfTheForgeRefuses() = dbTest { db ->
    val dao = db.inbox()
    val (account, repository) = setUp(db)
    repository.markRead(ThreadKey(account, "1"))
    assertEquals(false, repository.inbox().first().first().isUnread)
    inbox.gate!!.completeExceptionally(IllegalStateException("offline"))
    runCurrent()
    assertEquals(true, repository.inbox().first().first().isUnread)
    assertEquals(false, dao.thread(account, "1")!!.pendingRead)
  }

  @Test
  fun snoozeHidesUntilItsTimeThenShowsUnread() = dbTest { db ->
    val dao = db.inbox()
    val (account, repository) = setUp(db)
    repository.markRead(ThreadKey(account, "2"))
    inbox.gate!!.complete(Unit)
    runCurrent()
    repository.snooze(ThreadKey(account, "2"), START + 1.hours)

    repository.inbox().test {
      assertEquals(listOf("1"), awaitItem().map { it.id })
      advanceTimeBy(1.hours + 1.seconds)
      val back = awaitItem()
      // Back on top, unread.
      assertEquals(listOf("2", "1"), back.map { it.id })
      assertEquals(true, back.first().isUnread)
      cancelAndIgnoreRemainingEvents()
    }
  }
}

class InboxUndoTest {
  private val inbox = FakeInbox()

  private suspend fun kotlinx.coroutines.test.TestScope.setUp(
    db: TongsDatabase,
  ): Pair<Long, InboxRepository> {
    val (account, clients) = signIn(db, inbox)
    inbox.polls +=
      InboxPoll.Changed(
        listOf(thread("1", START - 1.hours), thread("2", START - 2.hours)),
        complete = true,
        cursor = "c",
        pollInterval = 60.seconds,
      )
    InboxSync(db, clients, virtualClock()).poll(account)
    return account to InboxRepository(db, clients, backgroundScope, virtualClock())
  }

  @Test
  fun doneWaitsForTheUndoWindow() = dbTest { db ->
    val dao = db.inbox()
    val (account, repository) = setUp(db)
    // Virtual time skips ahead while the test waits on the database, so hold the window open.
    repository.undoWindow = Duration.INFINITE
    val undo = repository.markDone(listOf(ThreadKey(account, "1"), ThreadKey(account, "2")))
    assertEquals(true, dao.thread(account, "1")!!.pendingDone)
    assertEquals(emptyList(), repository.inbox().first())
    assertEquals(emptyList(), inbox.calls)

    undo.undo()
    runCurrent()
    assertEquals(emptyList(), inbox.calls)
    assertEquals(false, dao.thread(account, "1")!!.pendingDone)
    assertEquals(false, dao.thread(account, "2")!!.pendingDone)
  }

  @Test
  fun doneGoesOutAfterTheWindowAndUndoIsTooLate() = dbTest { db ->
    val dao = db.inbox()
    val (account, repository) = setUp(db)
    repository.undoWindow = Duration.ZERO
    val undo = repository.markDone(ThreadKey(account, "1"))
    runCurrent()
    assertEquals(listOf("done 1"), inbox.calls)
    assertNull(dao.thread(account, "1"))
    undo.undo()
    assertNull(dao.thread(account, "1"))
  }

  @Test
  fun snoozeUndoes() = dbTest { db ->
    val dao = db.inbox()
    val (account, repository) = setUp(db)
    val undo = repository.snooze(ThreadKey(account, "1"), START + 1.hours)
    assertEquals(listOf("2"), repository.inbox().first().map { it.id })
    assertEquals(listOf("1"), repository.snoozed().first().map { it.id })
    undo.undo()
    assertEquals(listOf("1", "2"), repository.inbox().first().map { it.id })
    assertNull(dao.thread(account, "1")!!.snoozedUntil)
  }

  @Test
  fun sendsWhatAnEarlierRunLeftPending() = dbTest { db ->
    val dao = db.inbox()
    val (account, clients) = signIn(db, inbox)
    inbox.polls +=
      InboxPoll.Changed(
        listOf(thread("1", START)),
        complete = true,
        cursor = "c",
        pollInterval = null,
      )
    InboxSync(db, clients, virtualClock()).poll(account)
    // As if the app closed inside the undo window.
    dao.setPendingDone(account, "1", true)
    InboxRepository(db, clients, backgroundScope, virtualClock())
    runCurrent()
    assertEquals(listOf("done 1"), inbox.calls)
    assertNull(dao.thread(account, "1"))
  }

  @Test
  fun replaysPersistedReadsAndRevertsRefusedOnes() = dbTest { db ->
    val dao = db.inbox()
    val (account, clients) = signIn(db, inbox)
    inbox.polls += InboxPoll.Changed(listOf(thread("1", START)), true, "c", null)
    InboxSync(db, clients, virtualClock()).poll(account)
    // The process died after the optimistic write, before the request was confirmed.
    dao.markReadPending(account, "1")
    inbox.gate = CompletableDeferred()
    InboxRepository(db, clients, backgroundScope, virtualClock())
    runCurrent()
    assertEquals(listOf("read 1"), inbox.calls)
    inbox.gate!!.complete(Unit)
    runCurrent()
    assertEquals(false, dao.thread(account, "1")!!.pendingRead)
    assertEquals(false, dao.thread(account, "1")!!.unread)

    dao.markReadPending(account, "1")
    inbox.gate = CompletableDeferred()
    InboxRepository(db, clients, backgroundScope, virtualClock())
    runCurrent()
    inbox.gate!!.completeExceptionally(IllegalStateException("offline"))
    runCurrent()
    assertEquals(listOf("read 1", "read 1"), inbox.calls)
    assertEquals(false, dao.thread(account, "1")!!.pendingRead)
    assertEquals(true, dao.thread(account, "1")!!.unread)
  }
}
