package dev.sargunv.tongs.core.data

import dev.sargunv.tongs.forge.api.ForgeException
import dev.sargunv.tongs.forge.api.InboxPoll
import dev.sargunv.tongs.forge.api.InboxSource
import dev.sargunv.tongs.forge.api.InboxThread
import dev.sargunv.tongs.forge.api.SsoAuthorization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy

class InboxHydrationSyncTest {
  private val inbox = FakeInbox()
  private val hydrator = FakeHydrator()

  private fun changed(vararg threads: InboxThread, complete: Boolean = true) =
    InboxPoll.Changed(threads.toList(), complete, "c", 60.seconds)

  @Test
  fun hydratesEachUpdateOnce() = dbTest { db ->
    val dao = db.inbox()
    val (account, clients) = signIn(db, inbox, hydrator)
    val sync = InboxSync(db, clients, virtualClock())
    inbox.polls += changed(thread("1", START - 1.hours), thread("2", START - 2.hours))
    sync.poll(account)
    assertEquals(listOf(listOf("1", "2")), hydrator.batches)
    assertEquals("new 1", dao.thread(account, "1")!!.whatsNew)
    assertEquals("OPEN", dao.thread(account, "1")!!.subjectState)

    // The same threads again, one read elsewhere: nothing to hydrate.
    inbox.polls +=
      changed(thread("1", START - 1.hours, unread = false), thread("2", START - 2.hours))
    sync.poll(account)
    // A 304 hydrates nothing either.
    sync.poll(account)
    assertEquals(1, hydrator.batches.size)
    assertEquals("new 1", dao.thread(account, "1")!!.whatsNew)

    // Thread 2 updates: only it is hydrated, and its old line goes until then.
    inbox.polls += changed(thread("2", START), thread("1", START - 1.hours, unread = false))
    sync.poll(account)
    assertEquals(listOf("2"), hydrator.batches.last())
    assertEquals("new 2", dao.thread(account, "2")!!.whatsNew)
    assertEquals(2, sync.hydrationCost.value)
  }

  @Test
  fun retriesHydrationAfterABackoff() = dbTest { db ->
    val dao = db.inbox()
    val (account, clients) = signIn(db, inbox, hydrator)
    val sync = InboxSync(db, clients, virtualClock())
    hydrator.failure = IllegalStateException("502")
    inbox.polls += changed(thread("1", START - 1.hours))
    sync.poll(account)
    assertNull(dao.thread(account, "1")!!.whatsNew)
    // The poll itself worked.
    assertNull(dao.syncState(account)!!.lastError)

    hydrator.failure = null
    sync.poll(account)
    assertEquals(0, hydrator.batches.size)
    advanceTimeBy(InboxSync.ERROR_BACKOFF + 1.seconds)
    sync.poll(account)
    assertEquals(listOf(listOf("1")), hydrator.batches)
  }

  @Test
  fun recordsWhyAPollFailed() = dbTest { db ->
    var failure: Exception = ForgeException.Offline(java.io.IOException("no route"))
    val failing =
      object : InboxSource by FakeInbox() {
        override suspend fun poll(cursor: String?): InboxPoll = throw failure
      }
    val (account, clients) = signIn(db, failing)
    val sync = InboxSync(db, clients, virtualClock())
    val repository = InboxRepository(db, clients, backgroundScope, virtualClock())
    sync.poll(account)
    assertEquals(SyncProblem.Kind.Offline, repository.syncStatus().first().single().problem?.kind)

    // A rate limit waits for its reset, beyond the usual backoff.
    val reset = START + 30.minutes
    failure = ForgeException.RateLimited(reset)
    sync.poll(account)
    val limited = repository.syncStatus().first().single().problem!!
    assertEquals(SyncProblem.Kind.RateLimited, limited.kind)
    assertEquals(reset.toEpochMilliseconds(), limited.retryAt)
    assertEquals(reset, sync.dueAt(account))

    val url = "https://github.com/orgs/octo/sso?authorization_request=1"
    failure = ForgeException.SsoRequired(SsoAuthorization("octo", url))
    sync.poll(account)
    val sso = repository.syncStatus().first().single().problem!!
    assertEquals(SyncProblem.Kind.SsoRequired, sso.kind)
    assertEquals(SsoNotice("octo", url), sso.sso)
  }

  @Test
  fun threadsHiddenBySsoArentDone() = dbTest { db ->
    val dao = db.inbox()
    val (account, clients) = signIn(db, inbox)
    val sync = InboxSync(db, clients, virtualClock())
    val hidden =
      thread("2", START - 2.hours).copy(repo = dev.sargunv.tongs.forge.api.RepoRef("Octo-Org", "x"))
    inbox.polls += changed(thread("1", START - 1.hours), hidden)
    sync.poll(account)
    inbox.polls +=
      InboxPoll.Changed(
        listOf(thread("1", START)),
        complete = true,
        cursor = "c2",
        pollInterval = 60.seconds,
        ssoRequired = listOf(SsoAuthorization("octo-org", "https://github.com/orgs/octo-org/sso")),
      )
    sync.poll(account)
    assertEquals(setOf("1", "2"), dao.threads(account).map { it.id }.toSet())
    // Hidden isn't missing: it's still unread, and not marked read elsewhere.
    assertEquals(true, dao.thread(account, "2")!!.unread)
    assertEquals(false, dao.thread(account, "2")!!.readElsewhere)
    val status = InboxRepository(db, clients, backgroundScope, virtualClock()).syncStatus().first()
    assertEquals(listOf("octo-org"), status.single().ssoHidden.map { it.organization })
  }
}
