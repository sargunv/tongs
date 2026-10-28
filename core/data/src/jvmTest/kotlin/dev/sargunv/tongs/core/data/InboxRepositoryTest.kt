package dev.sargunv.tongs.core.data

import app.cash.turbine.test
import dev.sargunv.tongs.forge.api.InboxPoll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    return account to InboxRepository(db, clients, backgroundScope, virtualClock())
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
