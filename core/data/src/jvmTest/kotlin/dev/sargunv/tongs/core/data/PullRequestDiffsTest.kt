package dev.sargunv.tongs.core.data

import dev.sargunv.tongs.engine.FetchOptions
import dev.sargunv.tongs.engine.FetchSession
import dev.sargunv.tongs.engine.GitTransport
import dev.sargunv.tongs.engine.Highlighter
import dev.sargunv.tongs.engine.ReviewLoader
import dev.sargunv.tongs.forge.api.Account
import dev.sargunv.tongs.forge.api.ForgeAdapter
import dev.sargunv.tongs.forge.api.ForgeClient
import dev.sargunv.tongs.forge.api.ForgeKind
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.ReviewSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PullRequestDiffsTest {
  @Test
  fun theRepositorySelectsTheAccountAndOwnsTheObjectStoreLocation() = dbTest { db ->
    val cache = Files.createTempDirectory("review-repository").toFile()
    val tokens = InMemoryTokenStore()
    var received: Pair<RepoRef, Int>? = null
    val source =
      object : ReviewSource {
        override suspend fun loadPullRequest(
          repo: RepoRef,
          number: Int,
          openSession: (FetchOptions) -> FetchSession,
          onLoader: (ReviewLoader) -> Unit,
          log: (String) -> Unit,
        ) {
          received = repo to number
          onLoader(
            ReviewLoader(openSession(FetchOptions()), GitTransport { _, _ -> error("No network") }),
          )
        }
      }
    val adapter =
      object : ForgeAdapter by FakeAdapter(FakeInbox()) {
        override fun client(account: Account, token: String) =
          object : ForgeClient {
            override val inbox = null
            override val reviews = source

            override fun close() {}
          }
      }
    val adapters = mapOf<ForgeKind, ForgeAdapter>(ForgeKind.GitHub to adapter)
    val clients = ForgeClients(db, tokens, adapters)
    val accounts = AccountRepository(db, tokens, adapters, clients)
    val account = accounts.signIn(dev.sargunv.tongs.forge.api.Instance.GitHubDotCom, "t")
    val environment =
      object : AppEnvironment {
        override val dataDir = cache.path
        override val cacheDir = cache.path
        override val tokenStore = tokens
        override val isDesktop = true
      }
    val highlighter = Highlighter(threads = 1)
    var loader: ReviewLoader? = null
    try {
      val key = PullRequestKey(account.account.id.value, RepoRef("octo", "hello"), 7)
      PullRequestDiffs(accounts, clients, environment, highlighter).load(key, { loader = it })
      assertEquals(key.repo to 7, received)
      assertNotNull(loader)
      assertTrue(cache.resolve("github.com/octo/hello/objects").isDirectory)
    } finally {
      loader?.close()
      highlighter.close()
      cache.deleteRecursively()
    }
  }
}
