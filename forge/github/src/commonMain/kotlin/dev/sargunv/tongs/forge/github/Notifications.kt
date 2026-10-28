package dev.sargunv.tongs.forge.github

import dev.sargunv.tongs.core.net.HttpException
import dev.sargunv.tongs.core.net.Validators
import dev.sargunv.tongs.core.net.ifChangedSince
import dev.sargunv.tongs.core.net.isNotModified
import dev.sargunv.tongs.core.net.nextPageUrl
import dev.sargunv.tongs.core.net.pollInterval
import dev.sargunv.tongs.core.net.validators
import dev.sargunv.tongs.forge.api.InboxPoll
import dev.sargunv.tongs.forge.api.InboxSource
import dev.sargunv.tongs.forge.api.InboxThread
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.NotificationReason
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.Subject
import dev.sargunv.tongs.forge.api.SubjectType
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GitHub's notifications REST API. Polls with `If-Modified-Since`, so an unchanged inbox costs a
 * 304 and no rate limit.
 *
 * Each poll asks for read and unread threads (`all=true`), newest first, up to [maxPages] pages. If
 * there are more, older threads stay as they were; only a short poll reaches the end of the inbox.
 */
class GitHubNotifications(
  private val http: HttpClient,
  private val instance: Instance,
  private val token: String,
  private val maxPages: Int = 3,
  private val pageSize: Int = 50,
) : InboxSource {
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun poll(cursor: String?): InboxPoll {
    var url = "${instance.apiUrl}/notifications?all=true&per_page=$pageSize"
    val first =
      http.get(url) {
        auth()
        ifChangedSince(cursor?.let { Validators(lastModified = it) })
      }
    if (first.isNotModified) return InboxPoll.NotModified(first.pollInterval)
    first.checkSuccess("GET notifications")
    val threads = json.decodeFromString<List<NotificationDto>>(first.bodyAsText()).map(::toThread)
    val all = threads.toMutableList()
    var next = first.nextPageUrl
    var pages = 1
    while (next != null && pages < maxPages) {
      url = next
      val response = http.get(url) { auth() }
      response.checkSuccess("GET notifications page ${pages + 1}")
      all += json.decodeFromString<List<NotificationDto>>(response.bodyAsText()).map(::toThread)
      next = response.nextPageUrl
      pages++
    }
    return InboxPoll.Changed(
      threads = all,
      complete = next == null,
      cursor = first.validators.lastModified,
      pollInterval = first.pollInterval,
    )
  }

  override suspend fun markRead(threadId: String) {
    // 205 Reset Content.
    http
      .patch("${instance.apiUrl}/notifications/threads/$threadId") { auth() }
      .checkSuccess("PATCH thread $threadId")
  }

  override suspend fun markDone(threadId: String) {
    // 204 No Content.
    http
      .delete("${instance.apiUrl}/notifications/threads/$threadId") { auth() }
      .checkSuccess("DELETE thread $threadId")
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

private suspend fun HttpResponse.checkSuccess(what: String) {
  if (!status.isSuccess()) throw HttpException(status, what, bodyAsText())
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
