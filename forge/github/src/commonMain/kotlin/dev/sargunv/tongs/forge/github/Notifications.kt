package dev.sargunv.tongs.forge.github

import dev.sargunv.tongs.core.net.Validators
import dev.sargunv.tongs.core.net.ifChangedSince
import dev.sargunv.tongs.core.net.isNotModified
import dev.sargunv.tongs.core.net.nextPageUrl
import dev.sargunv.tongs.core.net.pollInterval
import dev.sargunv.tongs.core.net.validators
import dev.sargunv.tongs.forge.api.ForgeException
import dev.sargunv.tongs.forge.api.InboxPoll
import dev.sargunv.tongs.forge.api.InboxSource
import dev.sargunv.tongs.forge.api.InboxThread
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.NotificationReason
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.SsoAuthorization
import dev.sargunv.tongs.forge.api.Subject
import dev.sargunv.tongs.forge.api.SubjectType
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GitHub's notifications REST API. Polls with `If-Modified-Since`, so an unchanged inbox costs a
 * 304 and no rate limit.
 *
 * Each poll asks for unread threads, newest first, up to [maxPages] pages. If there are more, older
 * threads stay as they were; only a short poll reaches the end of the unread threads.
 *
 * Not `all=true`: GitHub's list of read threads includes the ones marked done, on the web or with
 * [markDone], and nothing in it tells them apart, so it can't say what's still in the inbox once
 * it's read. Only unread is certain: a thread with new activity comes back unread. With a cursor, a
 * changed inbox answers with only the threads updated since the cursor, as if `since` were set, so
 * such a poll is never complete.
 */
class GitHubNotifications(
  private val http: HttpClient,
  private val instance: Instance,
  private val token: String,
  private val maxPages: Int = 3,
  private val pageSize: Int = 50,
) : InboxSource {
  private val json = Json { ignoreUnknownKeys = true }

  /** Organization logins by ID, for SSO notices; they rarely change. */
  private val organizationLogins = mutableMapOf<Long, String?>()

  override suspend fun poll(cursor: String?): InboxPoll = offline {
    var url = "${instance.apiUrl}/notifications?per_page=$pageSize"
    val first =
      http.get(url) {
        auth()
        ifChangedSince(cursor?.let { Validators(lastModified = it) })
      }
    if (first.isNotModified) return@offline InboxPoll.NotModified(first.pollInterval)
    first.throwIfFailed("GET notifications")
    val threads = json.decodeFromString<List<NotificationDto>>(first.bodyAsText()).map(::toThread)
    val all = threads.toMutableList()
    val hidden = mutableSetOf<Long>()
    (parseSsoHeader(first.headers["X-GitHub-SSO"]) as? SsoHeader.PartialResults)?.let {
      hidden += it.organizationIds
    }
    var next = first.nextPageUrl
    var pages = 1
    while (next != null && pages < maxPages) {
      url = next
      val response = http.get(url) { auth() }
      response.throwIfFailed("GET notifications page ${pages + 1}")
      all += json.decodeFromString<List<NotificationDto>>(response.bodyAsText()).map(::toThread)
      (parseSsoHeader(response.headers["X-GitHub-SSO"]) as? SsoHeader.PartialResults)?.let {
        hidden += it.organizationIds
      }
      next = response.nextPageUrl
      pages++
    }
    InboxPoll.Changed(
      threads = all,
      // With a cursor, GitHub answers only with threads updated since it, so the answer covers
      // what it holds, not the whole inbox.
      complete = cursor == null && next == null,
      cursor = first.validators.lastModified,
      pollInterval = first.pollInterval,
      ssoRequired = hidden.map { ssoAuthorization(it) },
    )
  }

  override suspend fun markRead(threadId: String) = offline {
    // 205 Reset Content.
    http
      .patch("${instance.apiUrl}/notifications/threads/$threadId") { auth() }
      .throwIfFailed("PATCH thread $threadId")
  }

  override suspend fun markDone(threadId: String) = offline {
    // 204 No Content.
    http
      .delete("${instance.apiUrl}/notifications/threads/$threadId") { auth() }
      .throwIfFailed("DELETE thread $threadId")
  }

  /**
   * Where to authorize the token for an organization that hid threads. The header gives only its
   * ID, so look up its login once; the org's SSO page starts the authorization.
   */
  private suspend fun ssoAuthorization(organizationId: Long): SsoAuthorization {
    val login =
      organizationLogins.getOrPut(organizationId) {
        runCatching {
          val response = http.get("${instance.apiUrl}/organizations/$organizationId") { auth() }
          if (!response.status.isSuccess()) null
          else json.decodeFromString<OwnerDto>(response.bodyAsText()).login
        }
          .getOrNull()
      }
    val url =
      if (login != null) "${instance.webUrl}/orgs/$login/sso"
      else "${instance.webUrl}/settings/connections/applications"
    return SsoAuthorization(login, url)
  }

  private fun HttpRequestBuilder.auth() {
    header(HttpHeaders.Authorization, "Bearer $token")
    header(HttpHeaders.Accept, "application/vnd.github+json")
    header("X-GitHub-Api-Version", GITHUB_API_VERSION)
  }

  private fun toThread(dto: NotificationDto): InboxThread {
    val type = SubjectType.of(dto.subject.type)
    // The subject's API URL ends in its number for issues and PRs: .../pulls/123.
    val number =
      if (type == SubjectType.Issue || type == SubjectType.PullRequest) {
        dto.subject.url?.substringAfterLast('/')?.toIntOrNull()
      } else null
    return InboxThread(
      id = dto.id,
      repo = RepoRef(dto.repository.owner.login, dto.repository.name),
      subject =
        Subject(
          title = dto.subject.title,
          type = type,
          number = number,
          webUrl = webUrl(dto.repository.htmlUrl, type, dto.subject.url, number),
        ),
      reason = NotificationReason.of(dto.reason),
      unread = dto.unread,
      updatedAt = Instant.parse(dto.updatedAt),
      lastReadAt = dto.lastReadAt?.let(Instant::parse),
    )
  }
}

/** The REST API version this adapter targets. */
internal const val GITHUB_API_VERSION = "2026-03-10"

/** Where a subject opens in the browser; the API only gives its API URL, if that. */
internal fun webUrl(repoUrl: String, type: SubjectType, apiUrl: String?, number: Int?): String =
  when (type) {
    SubjectType.PullRequest -> number?.let { "$repoUrl/pull/$it" }
    SubjectType.Issue -> number?.let { "$repoUrl/issues/$it" }
    SubjectType.Commit -> apiUrl?.substringAfterLast('/')?.let { "$repoUrl/commit/$it" }
    SubjectType.Release -> "$repoUrl/releases"
    SubjectType.Discussion -> "$repoUrl/discussions"
    SubjectType.CheckSuite -> "$repoUrl/actions"
    is SubjectType.Other -> null
  } ?: repoUrl

/** Runs [block], reporting a network failure as [ForgeException.Offline]. */
internal suspend inline fun <T> offline(block: () -> T): T =
  try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: IOException) {
    throw ForgeException.Offline(e)
  }

@Serializable
private class NotificationDto(
  val id: String,
  val repository: RepositoryDto,
  val subject: SubjectDto,
  val reason: String,
  val unread: Boolean,
  @SerialName("updated_at") val updatedAt: String,
  @SerialName("last_read_at") val lastReadAt: String? = null,
)

@Serializable
private class RepositoryDto(
  val name: String,
  val owner: OwnerDto,
  @SerialName("html_url") val htmlUrl: String,
)

@Serializable private class OwnerDto(val login: String)

@Serializable private class SubjectDto(val title: String, val url: String? = null, val type: String)
