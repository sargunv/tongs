package dev.sargunv.tongs.core.composer

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.sargunv.tongs.core.data.AccountEntity
import dev.sargunv.tongs.core.data.DraftKey
import dev.sargunv.tongs.core.data.DraftRepository
import dev.sargunv.tongs.core.data.TongsDatabase
import dev.sargunv.tongs.core.data.TongsDatabaseConstructor
import dev.sargunv.tongs.forge.api.RepoRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest

class ComposerDraftTest {
  @Test
  fun aLineComposerRestoresItsSlotAfterLeavingComposition() = runTest {
    val db =
      Room.inMemoryDatabaseBuilder<TongsDatabase>(factory = TongsDatabaseConstructor::initialize)
        .setDriver(BundledSQLiteDriver())
        .build()
    try {
      val account =
        db
          .accounts()
          .upsert(
            AccountEntity(
              forge = "GitHub",
              webUrl = "https://github.com",
              apiUrl = "https://api.github.com",
              userId = "me",
              login = "me",
              name = null,
              avatarUrl = null,
              authMethod = "ClassicToken",
              capabilities = "",
              missingScopes = "",
            ),
          )
      val repository = DraftRepository(db, backgroundScope, Clock.System)
      val key = DraftKey(account, RepoRef("octo", "hello"), 7, "review/line:R12-R14:src/a.kt")
      val state = ComposerState(key, repository)
      state.load("")
      state.text.setTextAndPlaceCursorAtEnd("Keep **this** while I read another file")
      state.save() // Same save the 400 ms debounce calls.
      val remounted = ComposerState(key, repository)
      remounted.load("")
      assertEquals(state.text.text.toString(), remounted.text.text.toString())
      // Another line's slot is independent, including a reply on the same PR.
      val reply = ComposerState(key.copy(slot = "review/reply:T1"), repository)
      reply.load("")
      assertEquals("", reply.text.text.toString())
    } finally {
      db.close()
    }
  }
}
