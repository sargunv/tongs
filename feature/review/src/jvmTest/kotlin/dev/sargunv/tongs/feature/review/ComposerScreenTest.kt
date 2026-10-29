package dev.sargunv.tongs.feature.review

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.sargunv.tongs.core.data.*
import dev.sargunv.tongs.core.ui.TongsTheme
import dev.sargunv.tongs.engine.DiffSide
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.ReviewAnchor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlinx.coroutines.*
import org.jetbrains.skia.Image

/** Offscreen checks with made-up data; no window or forge mutations. */
@OptIn(ExperimentalTestApi::class)
class ComposerScreenTest {
  @Test fun folded() = composer(360)

  @Test fun unfolded() = composer(700)

  @Test fun desktop() = composer(1200)

  private fun composer(width: Int) =
    runDesktopComposeUiTest(width = width, height = 800) {
      val db =
        Room.inMemoryDatabaseBuilder<TongsDatabase>(factory = TongsDatabaseConstructor::initialize)
          .setDriver(BundledSQLiteDriver())
          .build()
      val app = CoroutineScope(SupervisorJob() + Dispatchers.Main)
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
        val key = PullRequestKey(id, RepoRef("octo", "hello"), 7)
        val clients = ForgeClients(db, InMemoryTokenStore(), emptyMap())
        val drafts = DraftRepository(db, app, Clock.System)
        val repository = PullRequestChangesRepository(db, clients, drafts, app, Clock.System)
        val line =
          DraftKey(
            id,
            key.repo,
            7,
            "line:A",
            DraftDestination.LineComment,
            ReviewAnchor("commitA", "src/removed.kt", DiffSide.New, 3, 4),
          )
        runBlocking { drafts.save(line, "Please keep this on the original commit.") }
        lateinit var controller: ChangesController
        runOnIdle {
          controller =
            ChangesController(app, PullRequestLoad("octo/hello#7") {}, repository, drafts, key)
        }
        setContent {
          TongsTheme(darkTheme = false) {
            ReviewSidePane(controller, null, {}, Modifier.fillMaxSize())
          }
        }
        waitUntil {
          controller.allDrafts.size == 1 && drafts.writer(DraftKey.summary(key)).ready.value
        }
        onNodeWithTag("review-summary")
          .performTextInput("My summary survives opening another editor.")
        // Replace the form immediately after input, with no debounce or disposal save.
        runOnIdle { controller.editDraft(controller.allDrafts.first { it.draftKey == line }) }
        onNodeWithTag("composer-body").performTextInput(" Final edit.")
        onNode(hasText("Start review") or hasText("Add to review")).performClick()
        waitUntil {
          controller.composer == null &&
            controller.allDrafts.any { it.draftKey == line && it.inReview }
        }
        onNodeWithTag("review-summary")
          .assertTextEquals("My summary survives opening another editor.")
        val row = controller.allDrafts.first { it.draftKey == line }
        assertEquals("commitA", row.commitOid)
        assertFalse(controller.anchorIsCurrent(row))
        runOnIdle { controller.editDraft(row) }
        onNodeWithTag("composer-body")
          .assertTextEquals("Please keep this on the original commit. Final edit.")
        onNodeWithText("Done").assertIsDisplayed()
        val output = File("build/reports/drafts/changes-composer-$width.png")
        output.parentFile.mkdirs()
        output.writeBytes(
          checkNotNull(
              Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(),
            )
            .bytes,
        )
      } finally {
        runBlocking { app.coroutineContext[Job]!!.cancelAndJoin() }
        db.close()
      }
    }
}
