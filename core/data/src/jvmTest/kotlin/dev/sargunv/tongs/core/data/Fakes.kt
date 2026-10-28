package dev.sargunv.tongs.core.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.sargunv.tongs.forge.api.Account
import dev.sargunv.tongs.forge.api.AuthMethod
import dev.sargunv.tongs.forge.api.AuthProvider
import dev.sargunv.tongs.forge.api.Capability
import dev.sargunv.tongs.forge.api.ForgeAdapter
import dev.sargunv.tongs.forge.api.ForgeClient
import dev.sargunv.tongs.forge.api.ForgeKind
import dev.sargunv.tongs.forge.api.InboxPoll
import dev.sargunv.tongs.forge.api.InboxSource
import dev.sargunv.tongs.forge.api.InboxThread
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.NotificationReason
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.Subject
import dev.sargunv.tongs.forge.api.SubjectType
import dev.sargunv.tongs.forge.api.VerifiedToken
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Runs [block] with an in-memory database whose queries run on the test's scheduler, so virtual
 * time covers them too.
 */
fun dbTest(block: suspend TestScope.(TongsDatabase) -> Unit) = runTest {
  val db =
    Room.inMemoryDatabaseBuilder<TongsDatabase>(factory = TongsDatabaseConstructor::initialize)
      .setDriver(BundledSQLiteDriver())
      .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
      .build()
  try {
    block(db)
  } finally {
    db.close()
  }
}

/** A clock on the test scheduler's virtual time, starting at 2026-10-28T00:00Z. */
fun TestScope.virtualClock(): Clock =
  object : Clock {
    override fun now() =
      Instant.fromEpochMilliseconds(START.toEpochMilliseconds() + testScheduler.currentTime)
  }

val START = Instant.parse("2026-10-28T00:00:00Z")

fun thread(id: String, updatedAt: Instant, unread: Boolean = true) =
  InboxThread(
    id = id,
    repo = RepoRef("octo", "hello"),
    subject = Subject("Thread $id", SubjectType.PullRequest, id.toIntOrNull(), null),
    reason = NotificationReason.Author,
    unread = unread,
    updatedAt = updatedAt,
    lastReadAt = null,
  )

/** An inbox that answers polls from a queue and records every call. */
class FakeInbox : InboxSource {
  val polls = ArrayDeque<InboxPoll>()
  val cursors = mutableListOf<String?>()
  val calls = mutableListOf<String>()
  /** If set, mark calls wait for it; complete it exceptionally to fail them. */
  var gate: CompletableDeferred<Unit>? = null

  override suspend fun poll(cursor: String?): InboxPoll {
    cursors += cursor
    return polls.removeFirstOrNull() ?: InboxPoll.NotModified(null)
  }

  override suspend fun markRead(threadId: String) {
    calls += "read $threadId"
    gate?.await()
  }

  override suspend fun markDone(threadId: String) {
    calls += "done $threadId"
    gate?.await()
  }
}

class FakeAdapter(private val inbox: InboxSource) : ForgeAdapter {
  override val kind = ForgeKind.GitHub
  override val auth: AuthProvider =
    object : AuthProvider {
      override val tokenScopes = listOf("notifications")

      override fun newTokenUrl(instance: Instance) = ""

      override suspend fun verify(instance: Instance, token: String) =
        VerifiedToken(
          userId = "U_1",
          login = "octocat",
          name = null,
          avatarUrl = null,
          authMethod = AuthMethod.ClassicToken,
          scopes = setOf("notifications"),
          missingScopes = emptySet(),
          capabilities = setOf(Capability.Inbox),
        )
    }

  override fun client(account: Account, token: String): ForgeClient =
    object : ForgeClient {
      override val inbox = this@FakeAdapter.inbox
      override val reviews = null

      override fun close() {}
    }
}

/** Signs in one fake account and returns its ID. */
suspend fun signIn(db: TongsDatabase, inbox: InboxSource): Pair<Long, ForgeClients> {
  val tokens = InMemoryTokenStore()
  val adapters = mapOf<ForgeKind, ForgeAdapter>(ForgeKind.GitHub to FakeAdapter(inbox))
  val clients = ForgeClients(db, tokens, adapters)
  val account = AccountRepository(db, tokens, adapters, clients).signIn(Instance.GitHubDotCom, "t")
  return account.account.id.value to clients
}
