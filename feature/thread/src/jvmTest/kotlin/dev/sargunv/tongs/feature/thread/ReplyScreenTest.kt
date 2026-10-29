package dev.sargunv.tongs.feature.thread

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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
  fun repeatedSubmissionsWhileSavingQueueOnlyOneReply() =
    runDesktopComposeUiTest(width = 700, height = 640) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val db =
        Room.inMemoryDatabaseBuilder<TongsDatabase>(factory = TongsDatabaseConstructor::initialize)
          .setDriver(BundledSQLiteDriver())
          .build()
      val models = ViewModelStore()
      val releaseWrite = CompletableDeferred<Unit>()
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
        setContent { TongsTheme { ReplyScreen(model) { closes++ } } }
        waitUntil { onAllNodesWithText("Close with comment").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Comment").assertIsEnabled()

        // Hold Room's writer so send() suspends while saving, before it can close the composer.
        val writeHeld = CompletableDeferred<Unit>()
        val writer = scope.launch {
          db.withWriteTransaction {
            writeHeld.complete(Unit)
            releaseWrite.await()
          }
        }
        runBlocking { writeHeld.await() }
        val click =
          onNodeWithText("Comment").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        runOnIdle {
          // Both callbacks arrive before recomposition can disable the button.
          click()
          click()
        }
        onRoot().performKeyInput {
          keyDown(Key.CtrlLeft)
          pressKey(Key.Enter)
          keyUp(Key.CtrlLeft)
          keyDown(Key.MetaLeft)
          pressKey(Key.Enter)
          keyUp(Key.MetaLeft)
        }
        runOnIdle { assertEquals(0, closes) }
        onNodeWithText("Comment").assertIsNotEnabled()
        onNodeWithText("Close with comment").assertIsNotEnabled()

        releaseWrite.complete(Unit)
        runBlocking { writer.join() }
        waitUntil { closes > 0 }
        waitUntil { source.comments.get() > 0 }
        runOnIdle {
          assertEquals(1, closes)
          assertEquals(1, source.comments.get())
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
  val comments = AtomicInteger()

  override suspend fun load(repo: RepoRef, number: Int, headCount: Int, tailCount: Int) =
    ThreadLoad(
      Fixtures.pullRequest,
      TimelinePage(emptyList(), null, null, false, false),
      TimelinePage(emptyList(), null, null, false, false),
      0,
    )

  override suspend fun addComment(threadId: String, body: String): TimelineItem.Comment {
    val id = comments.incrementAndGet()
    return TimelineItem.Comment("posted-$id", Fixtures.now, null, body)
  }

  override suspend fun loadTimeline(
    repo: RepoRef,
    number: Int,
    cursor: String,
    count: Int,
  ): TimelinePage = error("Unexpected pagination")

  override suspend fun close(threadId: String, kind: ThreadKind, reason: StateReason?) =
    error("Unexpected close")

  override suspend fun reopen(threadId: String, kind: ThreadKind) = error("Unexpected reopen")

  override suspend fun react(subjectId: String, reaction: Reaction, add: Boolean) =
    error("Unexpected reaction")

  override suspend fun merge(pullRequestId: String, method: MergeMethod, expectedHeadOid: String) =
    error("Unexpected merge")
}
