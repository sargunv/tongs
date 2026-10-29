package dev.sargunv.tongs.core.composer

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.sargunv.tongs.core.data.*
import dev.sargunv.tongs.core.markdown.MarkdownContext
import dev.sargunv.tongs.core.ui.TongsTheme
import dev.sargunv.tongs.forge.api.RepoRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlinx.coroutines.*

@OptIn(ExperimentalTestApi::class)
class ComposerDraftTest {
  @Test
  fun typingAndFormattingSurviveImmediateUnmountAndQuoteUsesTheSameWriter() =
    runDesktopComposeUiTest {
      val db =
        Room.inMemoryDatabaseBuilder<TongsDatabase>(factory = TongsDatabaseConstructor::initialize)
          .setDriver(BundledSQLiteDriver())
          .build()
      val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val id = runBlocking {
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
        }
        val drafts = DraftRepository(db, app, Clock.System)
        val key = DraftKey(id, RepoRef("octo", "hello"), 7)
        val visible = mutableStateOf(true)
        lateinit var state: ComposerState
        setContent {
          TongsTheme(darkTheme = false) {
            if (visible.value) {
              state = rememberComposerState(key, drafts)
              Composer(
                state,
                "Reply",
                emptyList(),
                { visible.value = false },
                MarkdownContext("https://github.com", key.repo),
                Modifier,
              )
            }
          }
        }
        waitUntil { state.ready }
        onNodeWithTag("composer-body").performTextInput("Keep this")
        onNodeWithContentDescription("Bold (Ctrl+B)").performClick()
        // No advance of a debounce clock, and no dispose-time save.
        runOnIdle { visible.value = false }
        runBlocking { drafts.writer(key).flush() }
        assertEquals("Keep this**bold**", runBlocking { drafts.get(key) })
        runBlocking { drafts.append(key, "> quote") }
        runOnIdle { visible.value = true }
        waitUntil { state.ready && state.text.text.toString().endsWith("> quote") }
        onNodeWithTag("composer-body").assertTextEquals("Keep this**bold**\n\n> quote")
        assertEquals(drafts.writer(key), drafts.writer(key))
      } finally {
        app.cancel()
        db.close()
      }
    }
}
