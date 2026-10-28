package dev.sargunv.tongs.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.first

class InboxDaoTest {

  private fun entity(accountId: Long, id: String, hoursAgo: Int, unread: Boolean = true) =
    thread(id, START - hoursAgo.hours, unread).toEntity(accountId, null)

  @Test
  fun listsVisibleThreadsLatestFirst() = dbTest { db ->
    val dao = db.inbox()
    val (account, _) = signIn(db, FakeInbox())
    val now = START.toEpochMilliseconds()
    dao.upsert(
      listOf(
        entity(account, "1", hoursAgo = 3),
        entity(account, "2", hoursAgo = 1, unread = false),
        entity(account, "3", hoursAgo = 2),
        entity(account, "4", hoursAgo = 4),
      ),
    )
    dao.setPendingDone(account, "3", true)
    // Snoozed until an hour from now: hidden now, back (unread, and on top) after.
    dao.snooze(account, "4", now + 1.hours.inWholeMilliseconds)

    val visible = dao.observeInbox(now).first()
    assertEquals(listOf("2", "1"), visible.map { it.id })
    assertEquals(listOf(false, true), visible.map { it.isUnread })
    assertEquals("octocat", visible.first().accountLogin)

    val later = dao.observeInbox(now + 2.hours.inWholeMilliseconds).first()
    assertEquals(listOf("4", "2", "1"), later.map { it.id })
    assertEquals(true, later.first().isUnread)
    assertEquals(now + 1.hours.inWholeMilliseconds, dao.observeNextWake(now).first())
  }

  @Test
  fun readingClearsLocalUnreadAndSnooze() = dbTest { db ->
    val dao = db.inbox()
    val (account, _) = signIn(db, FakeInbox())
    dao.upsert(listOf(entity(account, "1", hoursAgo = 1, unread = false)))
    dao.markUnreadLocally(account, "1")
    assertEquals(true, dao.observeInbox(START.toEpochMilliseconds()).first().single().isUnread)

    dao.markReadPending(account, "1")
    val row = dao.thread(account, "1")!!
    assertEquals(false, row.localUnread)
    assertEquals(true, row.pendingRead)
    assertEquals(false, dao.observeInbox(START.toEpochMilliseconds()).first().single().isUnread)
  }

  @Test
  fun signingOutDeletesTheAccountsThreads() = dbTest { db ->
    val dao = db.inbox()
    val (account, _) = signIn(db, FakeInbox())
    dao.upsert(listOf(entity(account, "1", hoursAgo = 1)))
    db.accounts().delete(account)
    assertEquals(emptyList(), dao.threads(account))
  }
}
