package dev.sargunv.tongs.forge.github

import com.apollographql.apollo.api.http.HttpHeader
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.network.http.HttpInfo
import dev.sargunv.tongs.forge.api.AuthException
import dev.sargunv.tongs.forge.api.AuthMethod
import dev.sargunv.tongs.forge.api.AuthProvider
import dev.sargunv.tongs.forge.api.Capability
import dev.sargunv.tongs.forge.api.DeviceAuthorization
import dev.sargunv.tongs.forge.api.DeviceFlow
import dev.sargunv.tongs.forge.api.DeviceFlowPoll
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.VerifiedToken
import dev.sargunv.tongs.forge.github.graphql.ViewerQuery
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.parameters
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The scopes Tongs asks for: private repos, the inbox, and org membership for team mentions. */
private val REQUESTED_SCOPES = listOf("repo", "notifications", "read:org")

/**
 * The OAuth App's client ID for the device flow. There's no app registered yet, so there's no
 * device flow, and sign-in takes a classic token.
 */
private val OAUTH_CLIENT_ID: String? = null

class GitHubAuth(private val http: HttpClient) : AuthProvider {
  override val tokenScopes = REQUESTED_SCOPES

  override fun newTokenUrl(instance: Instance) =
    "${instance.webUrl}/settings/tokens/new?scopes=${REQUESTED_SCOPES.joinToString(",")}" +
      "&description=Tongs"

  override suspend fun verify(instance: Instance, token: String): VerifiedToken {
    val apollo = gitHubApollo(http, instance, token)
    val response = apollo.use { it.query(ViewerQuery()).execute() }
    val viewer =
      response.data?.viewer
        ?: when (val e = response.exception) {
          is ApolloHttpException ->
            throw AuthException(
              if (e.statusCode == 401) "GitHub didn't accept this token."
              else "GitHub answered ${e.statusCode}.",
            )
          null -> throw AuthException("GitHub returned no user for this token.")
          else -> throw AuthException("Couldn't reach GitHub: ${e.message}")
        }
    val headers = response.executionContext[HttpInfo]?.headers.orEmpty()
    val method = authMethodOf(token)
    val scopes = scopesOf(headers)
    val missing = REQUESTED_SCOPES.filterNot { grants(scopes, it) }.toSet()
    return VerifiedToken(
      userId = viewer.id,
      login = viewer.login,
      name = viewer.name?.takeIf { it.isNotBlank() },
      avatarUrl = viewer.avatarUrl,
      authMethod = method,
      scopes = scopes,
      missingScopes = if (method == AuthMethod.ScopedToken) emptySet() else missing,
      capabilities = capabilities(method, scopes),
    )
  }

  override fun deviceFlow(instance: Instance): DeviceFlow? = OAUTH_CLIENT_ID?.let {
    GitHubDeviceFlow(http, instance, it, REQUESTED_SCOPES)
  }
}

/**
 * What a token can do. GitHub's notifications API takes only OAuth tokens and classic PATs with
 * `notifications` or `repo`; fine-grained PATs and GitHub App tokens can't read it.
 */
internal fun capabilities(method: AuthMethod, scopes: Set<String>): Set<Capability> = buildSet {
  add(Capability.GitPartialClone)
  if (method != AuthMethod.ScopedToken && grants(scopes, "notifications")) {
    add(Capability.Inbox)
    add(Capability.InboxReasons)
    add(Capability.InboxDone)
    // GitHub has no API to mark a thread unread.
  }
}

/** Classic PATs start `ghp_`, OAuth tokens `gho_`, and fine-grained PATs `github_pat_`. */
internal fun authMethodOf(token: String): AuthMethod =
  when {
    token.startsWith("gho_") -> AuthMethod.OAuth
    token.startsWith("github_pat_") || token.startsWith("ghu_") -> AuthMethod.ScopedToken
    else -> AuthMethod.ClassicToken
  }

private fun scopesOf(headers: List<HttpHeader>): Set<String> =
  headers
    .firstOrNull { it.name.equals("X-OAuth-Scopes", ignoreCase = true) }
    ?.value
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.toSet()
    .orEmpty()

/** Whether [scopes] include [scope], directly or through a broader scope that implies it. */
internal fun grants(scopes: Set<String>, scope: String): Boolean =
  scope in scopes ||
    when (scope) {
      "read:org" -> "write:org" in scopes || "admin:org" in scopes
      // The notifications API takes either.
      "notifications" -> "repo" in scopes
      else -> false
    }

/** GitHub's device flow, for an OAuth App. */
class GitHubDeviceFlow(
  private val http: HttpClient,
  private val instance: Instance,
  private val clientId: String,
  private val scopes: List<String>,
) : DeviceFlow {
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun start(): DeviceAuthorization {
    val response =
      http.submitForm(
        "${instance.webUrl}/login/device/code",
        parameters {
          append("client_id", clientId)
          append("scope", scopes.joinToString(" "))
        },
      ) {
        header(HttpHeaders.Accept, "application/json")
      }
    val dto = json.decodeFromString<DeviceCodeDto>(response.bodyAsText())
    return DeviceAuthorization(
      deviceCode = dto.deviceCode,
      userCode = dto.userCode,
      verificationUri = dto.verificationUri,
      expiresIn = dto.expiresIn.seconds,
      interval = dto.interval.seconds,
    )
  }

  override suspend fun poll(authorization: DeviceAuthorization): DeviceFlowPoll {
    val response =
      http.submitForm(
        "${instance.webUrl}/login/oauth/access_token",
        parameters {
          append("client_id", clientId)
          append("device_code", authorization.deviceCode)
          append("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
        },
      ) {
        header(HttpHeaders.Accept, "application/json")
      }
    return parseDevicePoll(json.decodeFromString<AccessTokenDto>(response.bodyAsText()))
  }
}

internal fun parseDevicePoll(dto: AccessTokenDto): DeviceFlowPoll =
  when {
    dto.accessToken != null ->
      DeviceFlowPoll.Granted(dto.accessToken, dto.scope.orEmpty().split(',').toSet() - "")
    dto.error == "authorization_pending" -> DeviceFlowPoll.Pending
    dto.error == "slow_down" -> DeviceFlowPoll.SlowDown((dto.interval ?: 10).seconds)
    dto.error == "expired_token" -> DeviceFlowPoll.Expired
    dto.error == "access_denied" -> DeviceFlowPoll.Denied
    else -> DeviceFlowPoll.Failed(dto.error ?: "no token")
  }

@Serializable
private class DeviceCodeDto(
  @SerialName("device_code") val deviceCode: String,
  @SerialName("user_code") val userCode: String,
  @SerialName("verification_uri") val verificationUri: String,
  @SerialName("expires_in") val expiresIn: Long,
  val interval: Long,
)

@Serializable
internal class AccessTokenDto(
  @SerialName("access_token") val accessToken: String? = null,
  val scope: String? = null,
  val error: String? = null,
  val interval: Long? = null,
)
