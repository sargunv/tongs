package dev.sargunv.tongs.feature.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.room3.Room
import androidx.room3.withWriteTransaction
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.sargunv.tongs.core.data.AccountEntity
import dev.sargunv.tongs.core.data.AccountRepository
import dev.sargunv.tongs.core.data.DraftRepository
import dev.sargunv.tongs.core.data.ForgeClients
import dev.sargunv.tongs.core.data.InMemoryTokenStore
import dev.sargunv.tongs.core.data.ThreadRef
import dev.sargunv.tongs.core.data.ThreadRepository
import dev.sargunv.tongs.core.data.TongsDatabase
import dev.sargunv.tongs.core.data.TongsDatabaseConstructor
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.core.ui.TongsTheme
import dev.sargunv.tongs.forge.api.Account
import dev.sargunv.tongs.forge.api.AuthProvider
import dev.sargunv.tongs.forge.api.ForgeAdapter
import dev.sargunv.tongs.forge.api.ForgeClient
import dev.sargunv.tongs.forge.api.ForgeKind
import dev.sargunv.tongs.forge.api.MergeMethod
import dev.sargunv.tongs.forge.api.Reaction
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.StateReason
import dev.sargunv.tongs.forge.api.ThreadKind
import dev.sargunv.tongs.forge.api.ThreadLoad
import dev.sargunv.tongs.forge.api.ThreadSource
import dev.sargunv.tongs.forge.api.TimelineItem
import dev.sargunv.tongs.forge.api.TimelinePage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class ReplyScreenTest {
  @Test
  fun commentCapturesBodyIgnoresDuplicatesAndSurvivesDismissalDuringSave() =
    submitWhileWriterBusy("Comment")

  @Test
  fun closeCapturesBodyIgnoresDuplicatesAndSurvivesDismissalDuringSave() =
    submitWhileWriterBusy("Close with comment")

  private fun submitWhileWriterBusy(action: String) =
    runDesktopComposeUiTest(width = 700, height = 640) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val db =
        Room.inMemoryDatabaseBuilder<TongsDatabase>(factory = TongsDatabaseConstructor::initialize)
          .setDriver(BundledSQLiteDriver())
          .build()
      val models = ViewModelStore()
      val releaseWrite = CompletableDeferred<Unit>()
      var visible by mutableStateOf(true)
      var closes = 0
      try {
        val tokens = InMemoryTokenStore()
        val source = ReplySource()
        val adapter =
          object : ForgeAdapter {
            override val kind = ForgeKind.GitHub
            override val auth: AuthProvider
              get() = error("Not used")

            override fun client(account: Account, token: String) =
              object : ForgeClient {
                override val inbox = null
                override val reviews = null
                override val threads = source

                override fun close() {}
              }
          }
        val adapters = mapOf<ForgeKind, ForgeAdapter>(ForgeKind.GitHub to adapter)
        val clients = ForgeClients(db, tokens, adapters)
        val accounts = AccountRepository(db, tokens, adapters, clients)
        val drafts = DraftRepository(db, scope, Clock.System)
        val threads = ThreadRepository(db, clients, accounts, drafts, scope, Clock.System)
        val ref = runBlocking {
          val id =
            db
              .accounts()
              .upsert(
                AccountEntity(
                  forge = "GitHub",
                  webUrl = "https://github.com",
                  apiUrl = "https://api.github.com",
                  userId = "U_1",
                  login = "octocat",
                  name = null,
                  avatarUrl = null,
                  authMethod = "ClassicToken",
                  capabilities = "",
                  missingScopes = "",
                ),
              )
          ThreadRef(id, Fixtures.repo, 412).also {
            tokens.put(id, "fake-token")
            threads.refresh(it)
            drafts.save(it.replyDraft, "Looks good")
          }
        }
        val model =
          ReplyViewModel(
            Route.ThreadReply(Route.Thread(ref.accountId, ref.repo, "PullRequest", ref.number)),
            threads,
            drafts,
            accounts,
          )
        models.put("reply", model)
        setContent {
          TongsTheme {
            if (visible)
              ReplyScreen(model) {
                closes++
                visible = false
              }
          }
        }
        waitUntil { onAllNodesWithText("Close with comment").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Comment").assertIsEnabled()

        // Hold Room's writer before submitting, so the save cannot finish until after dismissal.
        val writeHeld = CompletableDeferred<Unit>()
        val writer = scope.launch {
          db.withWriteTransaction {
            writeHeld.complete(Unit)
            releaseWrite.await()
          }
        }
        runBlocking { writeHeld.await() }
        val click =
          onNodeWithText(action).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val edit =
          onNodeWithText("Looks good")
            .fetchSemanticsNode()
            .config[SemanticsActions.SetText]
            .action!!
        runOnIdle {
          click()
          // Stale events arrive before recomposition removes the composer.
          click()
          edit(androidx.compose.ui.text.AnnotatedString("Edited after submit"))
          assertEquals(1, closes)
        }
        waitUntil { onAllNodesWithText("Comment").fetchSemanticsNodes().isEmpty() }
        runOnIdle { models.clear() }
        assertEquals(emptyList(), source.comments.toList())

        releaseWrite.complete(Unit)
        runBlocking { writer.join() }
        waitUntil { source.comments.isNotEmpty() }
        // The accepted draft stays frozen while its editor is dismissed and its model cleared.
        assertEquals("Looks good", runBlocking { drafts.get(ref.replyDraft) })
        source.accept.complete(Unit)
        waitUntil { runBlocking { drafts.get(ref.replyDraft) == null } }
        waitUntil { source.closes.get() == if (action == "Comment") 0 else 1 }
        runOnIdle {
          assertEquals(1, closes)
          assertEquals(listOf("Looks good"), source.comments.toList())
        }
      } finally {
        releaseWrite.complete(Unit)
        runOnIdle { models.clear() }
        scope.cancel()
        db.close()
      }
    }
}

private class ReplySource : ThreadSource {
  val comments = CopyOnWriteArrayList<String>()
  val closes = AtomicInteger()
  val accept = CompletableDeferred<Unit>()

  override suspend fun load(repo: RepoRef, number: Int, headCount: Int, tailCount: Int) =
    ThreadLoad(
      Fixtures.pullRequest,
      TimelinePage(emptyList(), null, null, false, false),
      TimelinePage(emptyList(), null, null, false, false),
      0,
    )

  override suspend fun addComment(threadId: String, body: String): TimelineItem.Comment {
    comments += body
    accept.await()
    return TimelineItem.Comment("posted", Fixtures.now, null, body)
  }

  override suspend fun loadTimeline(
    repo: RepoRef,
    number: Int,
    cursor: String,
    count: Int,
  ): TimelinePage = error("Unexpected pagination")

  override suspend fun close(threadId: String, kind: ThreadKind, reason: StateReason?) {
    closes.incrementAndGet()
  }

  override suspend fun reopen(threadId: String, kind: ThreadKind) = error("Unexpected reopen")

  override suspend fun react(subjectId: String, reaction: Reaction, add: Boolean) =
    error("Unexpected reaction")

  override suspend fun merge(pullRequestId: String, method: MergeMethod, expectedHeadOid: String) =
    error("Unexpected merge")
}
