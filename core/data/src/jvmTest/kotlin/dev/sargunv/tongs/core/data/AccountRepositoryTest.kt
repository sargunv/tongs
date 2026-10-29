package dev.sargunv.tongs.core.data

import dev.sargunv.tongs.forge.api.ForgeAdapter
import dev.sargunv.tongs.forge.api.ForgeKind
import dev.sargunv.tongs.forge.api.Instance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent

class AccountRepositoryTest {
  @Test
  fun initialPollWaitsForTokenStorage() = dbTest { db ->
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val tokens =
      object : TokenStore {
        var saved: String? = null

        override suspend fun get(accountId: Long) = saved

        override suspend fun put(accountId: Long, token: String) {
          entered.complete(Unit)
          release.await()
          saved = token
        }

        override suspend fun remove(accountId: Long) {
          saved = null
        }
      }
    val inbox = FakeInbox()
    val adapters = mapOf<ForgeKind, ForgeAdapter>(ForgeKind.GitHub to FakeAdapter(inbox))
    val clients = ForgeClients(db, tokens, adapters)
    val accounts = AccountRepository(db, tokens, adapters, clients)
    val published = mutableListOf<Long>()
    val observer =
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        accounts.accounts.collect { rows ->
          for (row in rows) {
            val id = row.account.id.value
            published += id
            val client =
              assertNotNull(
                clients.client(id),
                "Published accounts must be ready for their first poll",
              )
            client.inbox!!.poll(null)
          }
        }
      }
    val signingIn = async { accounts.signIn(Instance.GitHubDotCom, "fake-token") }
    try {
      entered.await()
      runCurrent()
      assertTrue(published.isEmpty(), "Account published before token storage finished")
      release.complete(Unit)
      val account = signingIn.await()
      runCurrent()
      assertEquals(listOf(account.account.id.value), published)
      assertEquals(1, inbox.cursors.size)
    } finally {
      release.complete(Unit)
      observer.cancel()
    }
  }

  @Test
  fun failedTokenStorageDoesNotPublishAccount() = dbTest { db ->
    val tokens =
      object : TokenStore {
        override suspend fun get(accountId: Long): String? = null

        override suspend fun put(accountId: Long, token: String) {
          error("Keychain refused write")
        }

        override suspend fun remove(accountId: Long) {}
      }
    val adapters = mapOf<ForgeKind, ForgeAdapter>(ForgeKind.GitHub to FakeAdapter(FakeInbox()))
    val accounts = AccountRepository(db, tokens, adapters, ForgeClients(db, tokens, adapters))
    assertFailsWith<IllegalStateException> { accounts.signIn(Instance.GitHubDotCom, "fake-token") }
    assertTrue(db.accounts().all().isEmpty())
  }
}
