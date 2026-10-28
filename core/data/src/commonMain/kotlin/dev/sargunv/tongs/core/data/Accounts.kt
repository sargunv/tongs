package dev.sargunv.tongs.core.data

import dev.sargunv.tongs.forge.api.Account
import dev.sargunv.tongs.forge.api.AuthProvider
import dev.sargunv.tongs.forge.api.ForgeAdapter
import dev.sargunv.tongs.forge.api.ForgeClient
import dev.sargunv.tongs.forge.api.ForgeKind
import dev.sargunv.tongs.forge.api.Instance
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An account, with what its token lacks; see [AccountRepository.signIn]. */
data class SignedInAccount(val account: Account, val missingScopes: Set<String>)

/** Signed-in accounts. Tokens go in the [TokenStore]; everything else in the database. */
@SingleIn(AppScope::class)
@Inject
class AccountRepository(
  private val db: TongsDatabase,
  private val tokens: TokenStore,
  private val adapters: Map<ForgeKind, ForgeAdapter>,
  private val clients: ForgeClients,
) {
  private val dao = db.accounts()

  val accounts: Flow<List<SignedInAccount>> =
    dao.observeAll().map { rows ->
      rows.map { SignedInAccount(it.toAccount(), it.missingScopes.split(',').toSet() - "") }
    }

  suspend fun account(accountId: Long): Account? = dao.get(accountId)?.toAccount()

  fun auth(kind: ForgeKind): AuthProvider = adapterFor(kind).auth

  /**
   * Checks [token] with the forge and adds its account, or updates it if it's already signed in.
   * Throws [dev.sargunv.tongs.forge.api.AuthException] if the forge rejects the token.
   */
  suspend fun signIn(instance: Instance, token: String): SignedInAccount {
    val verified = adapterFor(instance.kind).auth.verify(instance, token.trim())
    val existing = dao.find(instance.kind.name, instance.webUrl, verified.userId)
    val entity =
      AccountEntity(
        id = existing?.id ?: 0,
        forge = instance.kind.name,
        webUrl = instance.webUrl,
        apiUrl = instance.apiUrl,
        userId = verified.userId,
        login = verified.login,
        name = verified.name,
        avatarUrl = verified.avatarUrl,
        authMethod = verified.authMethod.name,
        capabilities = verified.capabilities.joinToString(",") { it.id },
        missingScopes = verified.missingScopes.joinToString(","),
      )
    val id = existing?.id ?: dao.upsert(entity)
    if (existing != null) dao.upsert(entity)
    tokens.put(id, token.trim())
    clients.evict(id)
    return SignedInAccount(entity.copy(id = id).toAccount(), verified.missingScopes)
  }

  /** Removes the account, its token, and everything synced for it. */
  suspend fun signOut(accountId: Long) {
    clients.evict(accountId)
    tokens.remove(accountId)
    dao.delete(accountId)
  }

  private fun adapterFor(kind: ForgeKind) =
    adapters[kind] ?: error("No adapter for $kind; register one in the app graph")
}

/** One [ForgeClient] per account, created on first use with the account's token. */
@SingleIn(AppScope::class)
@Inject
class ForgeClients(
  private val db: TongsDatabase,
  private val tokens: TokenStore,
  private val adapters: Map<ForgeKind, ForgeAdapter>,
) {
  private val lock = Mutex()
  private val clients = mutableMapOf<Long, ForgeClient>()

  /** The account's client, or null if the account or its token is gone. */
  suspend fun client(accountId: Long): ForgeClient? = lock.withLock {
    clients[accountId]?.let {
      return it
    }
    val account = db.accounts().get(accountId)?.toAccount() ?: return null
    val token = tokens.get(accountId) ?: return null
    val adapter = adapters[account.instance.kind] ?: return null
    adapter.client(account, token).also { clients[accountId] = it }
  }

  suspend fun evict(accountId: Long) {
    lock.withLock { clients.remove(accountId) }?.close()
  }
}
