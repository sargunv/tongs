package dev.sargunv.tongs.core.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
