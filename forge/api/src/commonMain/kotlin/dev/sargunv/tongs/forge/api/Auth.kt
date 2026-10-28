package dev.sargunv.tongs.forge.api

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/** Who a token belongs to, and what it can do. */
data class VerifiedToken(
  val userId: String,
  val login: String,
  val name: String?,
  val avatarUrl: String?,
  val authMethod: AuthMethod,
  /** The scopes the forge reports for the token, if it reports any. */
  val scopes: Set<String>,
  /** Scopes the app asks for that the token lacks. Sign-in still works, with less. */
  val missingScopes: Set<String>,
  val capabilities: Set<Capability>,
)

/** Signs in to one forge kind. */
interface AuthProvider {
  /** The scopes to ask for when the user creates a token by hand. */
  val tokenScopes: List<String>

  /** Where the user creates that token, with [tokenScopes] filled in where the forge allows it. */
  fun newTokenUrl(instance: Instance): String

  /** Checks a token against the forge. Throws [AuthException] if it's rejected. */
  suspend fun verify(instance: Instance, token: String): VerifiedToken

  /** The OAuth device flow for [instance], or null if the forge or the build has none. */
  fun deviceFlow(instance: Instance): DeviceFlow? = null
}

class AuthException(message: String) : Exception(message)

/**
 * OAuth 2.0 device authorization ([RFC 8628](https://www.rfc-editor.org/rfc/rfc8628)): the app
 * shows a code, the user enters it in a browser, and the app polls until they approve it. GitHub
 * supports it with no client secret in the app.
 */
interface DeviceFlow {
  suspend fun start(): DeviceAuthorization

  /** Asks once whether the user has approved [authorization] yet. */
  suspend fun poll(authorization: DeviceAuthorization): DeviceFlowPoll
}

data class DeviceAuthorization(
  val deviceCode: String,
  /** The code the user types in, like `WDJB-MJHT`. */
  val userCode: String,
  val verificationUri: String,
  val expiresIn: Duration,
  /** The minimum time between polls. */
  val interval: Duration,
)

sealed interface DeviceFlowPoll {
  /** Not approved yet; poll again after the interval. */
  data object Pending : DeviceFlowPoll

  /** Polling too fast; wait at least [interval] from now on. */
  data class SlowDown(val interval: Duration) : DeviceFlowPoll

  data class Granted(val token: String, val scopes: Set<String>) : DeviceFlowPoll

  data object Expired : DeviceFlowPoll

  data object Denied : DeviceFlowPoll

  /** Any other error the forge returned, by its raw code. */
  data class Failed(val error: String) : DeviceFlowPoll
}

/** Polls until the user approves, denies, or the code expires. Returns the final result. */
suspend fun DeviceFlow.awaitToken(authorization: DeviceAuthorization): DeviceFlowPoll {
  var interval = authorization.interval.coerceAtLeast(1.seconds)
  while (true) {
    delay(interval)
    when (val result = poll(authorization)) {
      DeviceFlowPoll.Pending -> {}
      is DeviceFlowPoll.SlowDown -> interval = result.interval
      else -> return result
    }
  }
}
