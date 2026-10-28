package dev.sargunv.tongs.core.data

import dev.sargunv.tongs.forge.api.ForgeException
import dev.sargunv.tongs.forge.api.SsoAuthorization
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** How one account's inbox sync is doing, for the inbox to explain. */
data class AccountSyncStatus(
  val accountId: Long,
  val login: String,
  /** Why the last poll failed, or null if it worked. */
  val problem: SyncProblem?,
  /** Organizations hiding threads until the token is authorized for their SAML SSO. */
  val ssoHidden: List<SsoNotice>,
  /** When a poll last worked, in epoch milliseconds. */
  val lastSyncedAt: Long?,
)

data class SyncProblem(
  val kind: Kind,
  val message: String,
  /** When sync tries again by itself, if it waits for a time, as for a rate limit's reset. */
  val retryAt: Long?,
  /** Where to authorize the token, for [Kind.SsoRequired]. */
  val sso: SsoNotice?,
) {
  enum class Kind {
    /** No network; the inbox shows what it last synced. */
    Offline,
    /** The forge rejects the token; sign in again. */
    Unauthorized,
    /** The token can't read notifications; it needs another scope, or a classic token. */
    NoAccess,
    RateLimited,
    SsoRequired,
    Other,
  }
}

@Serializable data class SsoNotice(val organization: String?, val url: String)

internal fun SsoAuthorization.toNotice() = SsoNotice(organization, url)

private val ssoListSerializer = ListSerializer(SsoNotice.serializer())

internal fun encodeSso(notices: List<SsoNotice>): String? =
  notices.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(ssoListSerializer, it) }

internal fun decodeSso(json: String?): List<SsoNotice> =
  json?.let { runCatching { Json.decodeFromString(ssoListSerializer, it) }.getOrNull() }.orEmpty()

internal fun Throwable.problemKind(): SyncProblem.Kind =
  when (this) {
    is ForgeException.Offline -> SyncProblem.Kind.Offline
    is ForgeException.Unauthorized -> SyncProblem.Kind.Unauthorized
    is ForgeException.NoAccess -> SyncProblem.Kind.NoAccess
    is ForgeException.RateLimited -> SyncProblem.Kind.RateLimited
    is ForgeException.SsoRequired -> SyncProblem.Kind.SsoRequired
    else -> SyncProblem.Kind.Other
  }

internal fun InboxSyncEntity.status(login: String): AccountSyncStatus {
  val sso = decodeSso(sso)
  val kind = errorKind?.let { name -> SyncProblem.Kind.entries.find { it.name == name } }
  val problem = lastError?.let {
    SyncProblem(
      kind = kind ?: SyncProblem.Kind.Other,
      message = it,
      retryAt = retryAt,
      sso = sso.firstOrNull().takeIf { kind == SyncProblem.Kind.SsoRequired },
    )
  }
  return AccountSyncStatus(
    accountId = accountId,
    login = login,
    problem = problem,
    ssoHidden = if (problem == null) sso else emptyList(),
    lastSyncedAt = lastSyncedAt,
  )
}
