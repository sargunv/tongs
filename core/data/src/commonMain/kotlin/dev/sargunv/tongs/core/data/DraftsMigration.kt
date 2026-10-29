package dev.sargunv.tongs.core.data

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Room runs this in its migration transaction. Unknown legacy anchors stay unknown. */
object DraftsMigration : Migration(7, 8) {
  override suspend fun migrate(connection: SQLiteConnection) {
    connection.execSQL("ALTER TABLE draft RENAME TO old_draft")
    connection.execSQL("DROP INDEX index_draft_accountId_repoOwner_repoName_number")
    connection.execSQL(
      """
      CREATE TABLE draft (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, accountId INTEGER NOT NULL,
        `key` TEXT NOT NULL, repoOwner TEXT NOT NULL, repoName TEXT NOT NULL, number INTEGER NOT NULL,
        slot TEXT NOT NULL, body TEXT NOT NULL, updatedAt INTEGER NOT NULL, destination TEXT NOT NULL,
        commitOid TEXT, path TEXT NOT NULL, side TEXT NOT NULL, startSide TEXT NOT NULL,
        startLine INTEGER NOT NULL, endLine INTEGER NOT NULL, inReplyTo TEXT,
        inReview INTEGER NOT NULL, submitting INTEGER NOT NULL, mayHaveBeenSent INTEGER NOT NULL,
        FOREIGN KEY(accountId) REFERENCES account(id) ON UPDATE NO ACTION ON DELETE CASCADE)
    """,
    )
    connection.execSQL(
      "CREATE INDEX index_draft_accountId_repoOwner_repoName_number ON draft(accountId, repoOwner, repoName, number)",
    )
    connection.execSQL("CREATE UNIQUE INDEX index_draft_accountId_key ON draft(accountId, `key`)")
    // Pending rows keep their IDs. The edit slot is newer editable text, so folds into that row.
    connection.execSQL(
      """
      INSERT INTO draft SELECT c.id, c.accountId,
        c.owner || '/' || c.repo || '#' || c.number || '/review/comment:' || c.id,
        c.owner, c.repo, c.number, 'review/comment:' || c.id,
        COALESCE(e.body, c.body), MAX(c.updatedAt, COALESCE(e.updatedAt, 0)),
        CASE WHEN c.inReplyTo IS NULL THEN 'LineComment' ELSE 'ReviewReply' END,
        CASE WHEN c.inReplyTo IS NULL THEN NULLIF(c.commitOid, '') ELSE NULL END,
        c.path, c.side, c.startSide, c.startLine, c.endLine, c.inReplyTo, 1, 0, 0
      FROM changes_draft_comment c LEFT JOIN old_draft e ON e.accountId = c.accountId
        AND e.`key` = c.owner || '/' || c.repo || '#' || c.number || '/review/edit:' || c.id
    """,
    )
    connection.execSQL(
      """
      INSERT INTO draft (accountId, `key`, repoOwner, repoName, number, slot, body, updatedAt,
        destination, commitOid, path, side, startSide, startLine, endLine, inReplyTo, inReview,
        submitting, mayHaveBeenSent)
      SELECT accountId, owner || '/' || repo || '#' || number || '/review/summary', owner, repo,
        number, 'review/summary', summary, updatedAt, 'Summary', NULL, '', 'New', 'New', 0, 0,
        NULL, 1, 0, 0 FROM changes_draft_review
    """,
    )
    connection
      .prepare(
        "SELECT accountId, `key`, repoOwner, repoName, number, slot, body, updatedAt FROM old_draft",
      )
      .use { old ->
        while (old.step()) {
          val slot = old.getText(5)
          // Matched edit slots were folded above; orphaned slots are retained for recovery.
          if (slot.startsWith("review/edit:")) {
            val found =
              connection
                .prepare("SELECT 1 FROM changes_draft_comment WHERE accountId = ? AND id = ?")
                .use {
                  it.bindLong(1, old.getLong(0))
                  it.bindLong(2, slot.substringAfterLast(':').toLongOrNull() ?: -1)
                  it.step()
                }
            if (found) continue
          }
          val match = Regex("review/line:([^:]+):([on])(\\d+)-([on])(\\d+):(.+)").matchEntire(slot)
          val destination =
            when {
              match != null || slot.startsWith("review/line:") -> "LineComment"
              slot.startsWith("review/reply:") -> "ReviewReply"
              slot == "reply" -> "ThreadReply"
              else -> "LineComment"
            }
          connection
            .prepare(
              """
          INSERT INTO draft (accountId, `key`, repoOwner, repoName, number, slot, body, updatedAt,
            destination, commitOid, path, side, startSide, startLine, endLine, inReplyTo, inReview,
            submitting, mayHaveBeenSent) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0)
        """,
            )
            .use { insert ->
              insert.bindLong(1, old.getLong(0))
              insert.bindText(2, old.getText(1))
              insert.bindText(3, old.getText(2))
              insert.bindText(4, old.getText(3))
              insert.bindLong(5, old.getLong(4))
              insert.bindText(6, slot)
              insert.bindText(7, old.getText(6))
              insert.bindLong(8, old.getLong(7))
              insert.bindText(9, destination)
              val g = match?.groupValues
              if (g != null) insert.bindText(10, g[1]) else insert.bindNull(10)
              insert.bindText(11, g?.get(6).orEmpty())
              insert.bindText(12, if (g?.get(4) == "o") "Old" else "New")
              insert.bindText(13, if (g?.get(2) == "o") "Old" else "New")
              insert.bindLong(14, g?.get(3)?.toLongOrNull() ?: 0)
              insert.bindLong(15, g?.get(5)?.toLongOrNull() ?: 0)
              if (destination == "ReviewReply")
                insert.bindText(16, slot.removePrefix("review/reply:"))
              else insert.bindNull(16)
              insert.step()
            }
        }
      }
    connection.execSQL("ALTER TABLE draft ADD COLUMN continuedReviewId TEXT")
    connection.execSQL(
      """
      UPDATE draft SET continuedReviewId = (SELECT pendingReviewId FROM changes_pr p
        WHERE p.accountId = draft.accountId AND p.owner = draft.repoOwner
          AND p.repo = draft.repoName AND p.number = draft.number)
      WHERE destination = 'Summary' AND inReview = 1
    """,
    )
    connection.execSQL("DROP TABLE old_draft")
    connection.execSQL("DROP TABLE changes_draft_comment")
    connection.execSQL("DROP TABLE changes_draft_review")
  }
}
