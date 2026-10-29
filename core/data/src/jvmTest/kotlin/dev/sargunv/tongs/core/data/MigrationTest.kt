package dev.sargunv.tongs.core.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.sargunv.tongs.forge.api.InboxPoll
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MigrationTest {
  /** Creates a database at [path] as schema [version] made it, from its exported JSON. */
  private fun createAt(path: String, version: Int, vararg inserts: String) {
    val schema =
      Json.parseToJsonElement(
          File("schemas/dev.sargunv.tongs.core.data.TongsDatabase/$version.json").readText(),
        )
        .jsonObject
        .getValue("database")
        .jsonObject
    val connection = BundledSQLiteDriver().open(path)
    try {
      for (entity in schema.getValue("entities").jsonArray) {
        val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
        fun sql(element: JsonElement) =
          element.jsonObject
            .getValue("createSql")
            .jsonPrimitive
            .content
            .replace("\${TABLE_NAME}", table)
        connection.execSQL(sql(entity))
        for (index in entity.jsonObject["indices"]?.jsonArray.orEmpty()) {
          connection.execSQL(sql(index))
        }
      }
      for (query in schema.getValue("setupQueries").jsonArray) {
        connection.execSQL(query.jsonPrimitive.content)
      }
      connection.execSQL("PRAGMA user_version = $version")
      for (insert in inserts) connection.execSQL(insert)
    } finally {
      connection.close()
    }
  }

  @Test
  fun reviewColumnsKeepExistingChangesAndDefaultToUnknown() = runTest {
    val dir = createTempDirectory("tongs-review-migration").toFile()
    try {
      val path = dir.resolve("tongs.db").path
      createAt(
        path,
        6,
        """
        INSERT INTO account (id, forge, webUrl, apiUrl, userId, login, authMethod, capabilities,
          missingScopes)
        VALUES (1, 'GitHub', 'https://github.com', 'https://api.github.com', 'U_1', 'octocat',
          'ClassicToken', '', '')
        """,
        """
        INSERT INTO changes_pr (accountId, owner, repo, number, nodeId, title, state, headOid,
          webUrl, viewerDidAuthor, viewerReviewRequested, viewerHasPendingReview, syncedAt)
        VALUES (1, 'octo', 'hello', 7, 'PR_1', 'Title', 'open', 'head1', '', 0, 0, 0, 1)
        """,
        """
        INSERT INTO changes_draft_comment (id, accountId, owner, repo, number, commitOid, path,
          side, startSide, startLine, endLine, body, createdAt, updatedAt)
        VALUES (1, 1, 'octo', 'hello', 7, 'head1', 'a.kt', 'New', 'New', 3, 3, 'Keep me', 1, 1)
        """,
        """
        INSERT INTO changes_thread (accountId, id, owner, repo, number, path, side, line,
          isResolved, isOutdated, isFileLevel, position)
        VALUES (1, 'T1', 'octo', 'hello', 7, 'a.kt', 'New', 3, 0, 0, 0, 0)
        """,
        """
        INSERT INTO changes_thread_comment (accountId, id, threadId, body, createdAt, position)
        VALUES (1, 'C1', 'T1', 'Published', 1, 0)
        """,
        """
        INSERT INTO feed_mute (accountId, kind, value, createdAt)
        VALUES (1, 'Repo', 'octo/hello', 42)
        """,
      )
      val db = openDatabase(path)
      try {
        assertEquals(
          listOf(FeedMuteEntity(1, "Repo", "octo/hello", 42)),
          db.feed().observeMutes().first(),
        )
        val dao = db.pullRequestChanges()
        val overview = dao.overview(1, "octo", "hello", 7)!!
        assertEquals("head1", overview.headOid)
        assertNull(overview.pendingReviewId)
        assertNull(overview.pendingReviewBody)
        assertEquals(0, overview.pendingReviewComments)
        val draft = dao.draft(1)!!
        assertEquals("Keep me", draft.body)
        assertEquals("head1", draft.commitOid)
        assertNull(draft.inReplyTo)
        val thread = dao.thread(1, "T1")!!
        assertFalse(thread.viewerCanReply)
        assertFalse(thread.viewerCanResolve)
        assertFalse(thread.viewerCanUnresolve)
        assertNull(thread.resolvedBy)
        assertEquals(
          CommentState.Published,
          dao.observeThreadComments(1, "octo", "hello", 7).first().single().state,
        )
      } finally {
        db.close()
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun addsFeedTablesWithoutLosingTheInbox() = runTest {
    val dir = createTempDirectory("tongs-feed-migration").toFile()
    try {
      val path = dir.resolve("tongs.db").path
      createAt(
        path,
        5,
        """
        INSERT INTO account (id, forge, webUrl, apiUrl, userId, login, authMethod, capabilities, missingScopes)
        VALUES (1, 'GitHub', 'https://github.com', 'https://api.github.com', 'U_1', 'octocat', 'ClassicToken', '', '')
        """,
        """
        INSERT INTO inbox_thread (accountId, id, repoOwner, repoName, title, subjectType, reason, unread, updatedAt, pendingRead, pendingDone, localUnread)
        VALUES (1, '7', 'octo', 'hello', 'Keep this', 'Issue', 'author', 0, 0, 0, 0, 0)
        """,
      )
      val db =
        Room.databaseBuilder<TongsDatabase>(path, factory = TongsDatabaseConstructor::initialize)
          .setDriver(BundledSQLiteDriver())
          .build()
      try {
        assertEquals("Keep this", db.inbox().thread(1, "7")?.title)
        assertEquals(emptyList(), db.feed().events(1))
        db.feed().upsertMute(FeedMuteEntity(1, "Repo", "octo/hello", 0))
        assertEquals(1, db.accounts().all().size)
      } finally {
        db.close()
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun dropsKeptForReadElsewhere() = runTest {
    val dir = createTempDirectory("tongs-migration").toFile()
    try {
      val path = dir.resolve("tongs.db").path
      createAt(
        path,
        4,
        """
        INSERT INTO account (id, forge, webUrl, apiUrl, userId, login, authMethod, capabilities,
          missingScopes)
        VALUES (1, 'GitHub', 'https://github.com', 'https://api.github.com', 'U_1', 'octocat',
          'ClassicToken', '', '')
        """,
        """
        INSERT INTO inbox_thread (accountId, id, repoOwner, repoName, title, subjectType, reason,
          unread, updatedAt, pendingRead, pendingDone, localUnread, kept)
        VALUES (1, '7', 'octo', 'hello', 'Read here', 'Issue', 'author', 0, 0, 0, 0, 0, 1)
        """,
      )
      val db =
        Room.databaseBuilder<TongsDatabase>(path, factory = TongsDatabaseConstructor::initialize)
          .setDriver(BundledSQLiteDriver())
          .build()
      try {
        val thread = db.inbox().thread(1, "7")!!
        assertEquals("Read here", thread.title)
        assertEquals(false, thread.readElsewhere)
      } finally {
        db.close()
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun versionOneLocalTriageSurvivesMigrationAndAFullPoll() = runTest {
    val dir = createTempDirectory("tongs-migration").toFile()
    try {
      val path = dir.resolve("tongs.db").path
      createAt(
        path,
        1,
        """
        INSERT INTO account (id, forge, webUrl, apiUrl, userId, login, authMethod, capabilities,
          missingScopes)
        VALUES (1, 'GitHub', 'https://github.com', 'https://api.github.com', 'U_1', 'octocat',
          'ClassicToken', '', '')
        """,
        """
        INSERT INTO inbox_thread (accountId, id, repoOwner, repoName, title, subjectType, reason,
          unread, updatedAt, pendingRead, pendingDone, localUnread, snoozedUntil)
        VALUES (1, '1', 'octo', 'hello', 'Local unread', 'Issue', 'author', 0, 0, 0, 0, 1, NULL),
          (1, '2', 'octo', 'hello', 'Snoozed', 'Issue', 'author', 0, 0, 0, 0, 0, 10000)
        """,
      )
      val db =
        Room.databaseBuilder<TongsDatabase>(path, factory = TongsDatabaseConstructor::initialize)
          .setDriver(BundledSQLiteDriver())
          .build()
      try {
        val inbox = FakeInbox()
        val (account, clients) = signIn(db, inbox)
        val clock =
          object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(0)
          }
        inbox.polls += InboxPoll.Changed(emptyList(), true, null, null)
        InboxSync(db, clients, clock).poll(account)
        assertEquals(setOf("1", "2"), db.inbox().threads(account).map { it.id }.toSet())
        assertEquals(true, db.inbox().thread(account, "1")!!.localUnread)
        assertEquals(10000L, db.inbox().thread(account, "2")!!.snoozedUntil)
      } finally {
        db.close()
      }
    } finally {
      dir.deleteRecursively()
    }
  }
}
