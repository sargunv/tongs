package dev.sargunv.tongs.forge.github

import dev.sargunv.tongs.engine.GitTransport
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlin.io.encoding.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `owner/repo#number`, or a `github.com/owner/repo/pull/number` URL. */
data class PullRequestRef(val owner: String, val repo: String, val number: Int) {
  override fun toString() = "$owner/$repo#$number"

  companion object {
    private val SHORT = Regex("""([\w.-]+)/([\w.-]+)#(\d+)""")
    private val URL = Regex("""(?:https?://)?github\.com/([\w.-]+)/([\w.-]+)/pull/(\d+).*""")

    fun parse(text: String): PullRequestRef? {
      val match = SHORT.matchEntire(text) ?: URL.matchEntire(text) ?: return null
      val (owner, repo, number) = match.destructured
      return PullRequestRef(owner, repo, number.toInt())
    }
  }
}

/** The parts of a PR the review needs. */
class PullRequest(
  val baseSha: String,
  val headSha: String,
  /** The base repo's size in KiB, as GitHub reports it: history and all. */
  val repoSizeKib: Long,
  val changedFiles: Int,
  val additions: Int,
  val deletions: Int,
)

/**
 * The GitHub REST calls a review needs, and git smart HTTP to the same host. Bring your own
 * [HttpClient]; the app picks the engine.
 *
 * Without a [token], public repos still work: git fetches need no auth, and REST allows 60 requests
 * an hour per IP, two per review.
 */
class GitHub(
  private val http: HttpClient,
  private val token: String?,
  private val apiUrl: String = "https://api.github.com",
  private val webUrl: String = "https://github.com",
) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun pullRequest(ref: PullRequestRef): PullRequest {
    val dto =
      json.decodeFromString<PullDto>(api("repos/${ref.owner}/${ref.repo}/pulls/${ref.number}"))
    return PullRequest(
      baseSha = dto.base.sha,
      headSha = dto.head.sha,
      repoSizeKib = dto.base.repo.size,
      changedFiles = dto.changedFiles,
      additions = dto.additions,
      deletions = dto.deletions,
    )
  }

  /** The merge base GitHub diffs a PR against: three-dot, like the web UI. */
  suspend fun mergeBase(ref: PullRequestRef, base: String, head: String): String {
    val path = "repos/${ref.owner}/${ref.repo}/compare/$base...$head?per_page=1"
    return json.decodeFromString<CompareDto>(api(path)).mergeBaseCommit.sha
  }

  private suspend fun api(path: String): String {
    val response =
      http.get("$apiUrl/$path") {
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2026-03-10")
      }
    check(response) { "GET $path" }
    return response.bodyAsText()
  }

  /**
   * Asks for the repo's git capability advertisement, the first step of smart HTTP. The fetches
   * don't need it; it opens the connection while other work runs.
   */
  suspend fun warmUp(owner: String, repo: String) {
    runCatching {
      http.get("$webUrl/$owner/$repo.git/info/refs?service=git-upload-pack") {
        basicAuth?.let { header(HttpHeaders.Authorization, it) }
        header("Git-Protocol", "version=2")
      }
    }
  }

  private val basicAuth = token?.let {
    "Basic " + Base64.encode("x-access-token:$it".encodeToByteArray())
  }

  /** Git protocol v2 `fetch` against the repo's smart-HTTP endpoint, streaming the response. */
  fun git(owner: String, repo: String): GitTransport {
    val url = "$webUrl/$owner/$repo.git/git-upload-pack"
    return GitTransport { body, onChunk ->
      http
        .preparePost(url) {
          basicAuth?.let { header(HttpHeaders.Authorization, it) }
          header("Git-Protocol", "version=2")
          accept(ContentType("application", "x-git-upload-pack-result"))
          setBody(ByteArrayContent(body, ContentType("application", "x-git-upload-pack-request")))
        }
        .execute { response ->
          check(response) { "POST $url" }
          val channel = response.bodyAsChannel()
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val read = channel.readAvailable(buffer)
            if (read < 0) break
            if (read > 0) onChunk(buffer, read)
          }
        }
    }
  }

  private suspend fun check(response: HttpResponse, what: () -> String) {
    if (!response.status.isSuccess()) {
      error("${what()}: ${response.status} ${response.bodyAsText().take(200)}")
    }
  }
}

@Serializable
private class PullDto(
  val base: RefDto,
  val head: RefDto,
  val additions: Int,
  val deletions: Int,
  @SerialName("changed_files") val changedFiles: Int,
)

@Serializable private class RefDto(val sha: String, val repo: RepoDto)

@Serializable private class RepoDto(val size: Long)

@Serializable
private class CompareDto(@SerialName("merge_base_commit") val mergeBaseCommit: CommitDto)

@Serializable private class CommitDto(val sha: String)
