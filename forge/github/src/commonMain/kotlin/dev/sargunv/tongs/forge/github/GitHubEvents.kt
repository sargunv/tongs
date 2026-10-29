package dev.sargunv.tongs.forge.github

import dev.sargunv.tongs.core.net.Validators
import dev.sargunv.tongs.core.net.ifChangedSince
import dev.sargunv.tongs.core.net.isNotModified
import dev.sargunv.tongs.core.net.nextPageUrl
import dev.sargunv.tongs.core.net.pollInterval
import dev.sargunv.tongs.core.net.validators
import dev.sargunv.tongs.forge.api.FeedAction
import dev.sargunv.tongs.forge.api.FeedActor
import dev.sargunv.tongs.forge.api.FeedCommit
import dev.sargunv.tongs.forge.api.FeedEvent
import dev.sargunv.tongs.forge.api.FeedEventKind
import dev.sargunv.tongs.forge.api.FeedPoll
import dev.sargunv.tongs.forge.api.FeedSource
import dev.sargunv.tongs.forge.api.FeedSubject
import dev.sargunv.tongs.forge.api.ForgeException
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.RefType
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.SsoAuthorization
import dev.sargunv.tongs.forge.api.SubjectType
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray

/**
 * GitHub's activity feed, built from the events API, since GitHub has no API for its "For you"
 * feed. Each poll merges three kinds of stream:
 * - `/users/{login}/received_events`: the repos the user watches and the people they follow,
 * - `/users/{login}/events`: the user's own, private ones included,
 * - `/users/{login}/events/orgs/{org}`: each organization's dashboard, from `/user/orgs`.
 *
 * Each stream is conditional on its `ETag`, so an unchanged one costs a 304 and no rate limit, and
 * pages (100 events each, at most [maxPages]: GitHub keeps 300 events and 30 days) only until the
 * newest events from the last poll show up. An organization that needs SAML SSO authorization is
 * reported and skipped rather than failing the poll.
 */
class GitHubEvents(
  private val http: HttpClient,
  private val instance: Instance,
  private val token: String,
  private val login: String,
  private val clock: Clock = Clock.System,
  private val maxPages: Int = 3,
  private val pageSize: Int = 100,
) : FeedSource {
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun poll(cursor: String?): FeedPoll = offline {
    val old =
      cursor?.let { runCatching { json.decodeFromString<Cursor>(it) }.getOrNull() } ?: Cursor()
    val orgs = organizations(old)
    val streams = buildList {
      add("received" to "${instance.apiUrl}/users/$login/received_events")
      add("own" to "${instance.apiUrl}/users/$login/events")
      for (org in orgs.list) add("org:$org" to "${instance.apiUrl}/users/$login/events/orgs/$org")
    }
    val results = coroutineScope {
      streams
        .map { (key, url) ->
          async { pollStream(key, url, old.streams[key], optional = key.startsWith("org:")) }
        }
        .awaitAll()
    }
    val requests = orgs.requests + results.sumOf { it.requests }
    val interval = results.mapNotNull { it.pollInterval }.maxOrNull()
    val next =
      Cursor(
        streams =
          results
            .associate { it.key to it.cursor }
            .filterValues { it != null }
            .mapValues { it.value!! },
        orgs = orgs.list,
        orgsEtag = orgs.etag,
        orgsFetchedAt = orgs.fetchedAt,
      )
    // Nothing new, and nothing to remember: a stream's cursor or the organizations changing
    // counts as a change, so the next poll starts from them.
    if (results.all { it.notModified } && next == old) {
      return@offline FeedPoll.NotModified(interval, requests)
    }
    val events = results.flatMap { it.events }.distinctBy { it.id }
    FeedPoll.Changed(
      events = events,
      cursor = json.encodeToString(Cursor.serializer(), next),
      pollInterval = interval,
      requests = requests,
      ssoRequired = results.mapNotNull { it.sso },
    )
  }

  private class Orgs(
    val list: List<String>,
    val etag: String?,
    val fetchedAt: Long?,
    val requests: Int,
  )

  /**
   * The user's organizations, from the last poll if they were listed within the hour, otherwise
   * asked again (conditionally). A failure other than the rate limit keeps the last list.
   */
  private suspend fun organizations(old: Cursor): Orgs {
    val now = clock.now().toEpochMilliseconds()
    val fresh = old.orgsFetchedAt?.let { now - it < ORGS_TTL.inWholeMilliseconds } == true
    if (fresh && old.orgs != null) return Orgs(old.orgs, old.orgsEtag, old.orgsFetchedAt, 0)
    val response =
      http.get("${instance.apiUrl}/user/orgs?per_page=100") {
        auth()
        if (old.orgs != null) ifChangedSince(Validators(etag = old.orgsEtag))
      }
    if (response.isNotModified) return Orgs(old.orgs.orEmpty(), old.orgsEtag, now, 0)
    try {
      response.throwIfFailed("GET user/orgs")
    } catch (e: ForgeException.RateLimited) {
      throw e
    } catch (_: Exception) {
      return Orgs(old.orgs.orEmpty(), old.orgsEtag, old.orgsFetchedAt, 1)
    }
    val list = mutableListOf<String>()
    var page = response
    var requests = 1
    while (true) {
      list +=
        json.parseToJsonElement(page.bodyAsText()).jsonArray.mapNotNull {
          (it as? JsonObject)?.string("login")
        }
      val next = page.nextPageUrl ?: break
      page = http.get(next) { auth() }
      requests++
      page.throwIfFailed("GET user/orgs page")
    }
    return Orgs(list, response.validators.etag, now, requests)
  }

  private class StreamResult(
    val key: String,
    val notModified: Boolean,
    val events: List<FeedEvent>,
    val cursor: StreamCursor?,
    val pollInterval: Duration?,
    val requests: Int,
    val sso: SsoAuthorization? = null,
  )

  private suspend fun pollStream(
    key: String,
    url: String,
    old: StreamCursor?,
    optional: Boolean,
  ): StreamResult {
    var requests = 0
    // An organization that needs SSO authorization is asked again only once in a while.
    val blocked =
      old?.ssoUrl?.takeIf { (old.blockedUntil ?: 0) > clock.now().toEpochMilliseconds() }
    if (blocked != null) {
      val sso = SsoAuthorization(key.removePrefix("org:"), blocked)
      return StreamResult(key, true, emptyList(), old, null, 0, sso)
    }
    try {
      val first =
        http.get("$url?per_page=$pageSize") {
          auth()
          ifChangedSince(old?.let { Validators(it.lastModified, it.etag) })
        }
      if (first.isNotModified)
        return StreamResult(key, true, emptyList(), old, first.pollInterval, 0)
      requests++
      first.throwIfFailed("GET $key events")
      val events = parsePage(first).toMutableList()
      val seen = old?.seen.orEmpty().toSet()
      var next = first.nextPageUrl
      var pages = 1
      while (next != null && pages < maxPages && events.none { it.id in seen }) {
        val response = http.get(next) { auth() }
        requests++
        response.throwIfFailed("GET $key events page ${pages + 1}")
        events += parsePage(response)
        next = response.nextPageUrl
        pages++
      }
      val validators = first.validators
      val cursor =
        StreamCursor(
          etag = validators.etag,
          lastModified = validators.lastModified,
          seen = events.take(SEEN).map { it.id }.ifEmpty { old?.seen.orEmpty() },
        )
      return StreamResult(key, false, events, cursor, first.pollInterval, requests)
    } catch (e: ForgeException.SsoRequired) {
      if (!optional) throw e
      val until = clock.now().toEpochMilliseconds() + SSO_RETRY.inWholeMilliseconds
      val cursor = (old ?: StreamCursor()).copy(ssoUrl = e.authorization.url, blockedUntil = until)
      return StreamResult(key, true, emptyList(), cursor, null, requests, e.authorization)
    } catch (e: ForgeException.NoAccess) {
      if (optional) return StreamResult(key, true, emptyList(), old, null, requests)
      throw ForgeException.NoAccess("This token can't read activity", e.acceptedScopes)
    } catch (e: ForgeException) {
      throw e
    } catch (e: dev.sargunv.tongs.core.net.HttpException) {
      // An organization the user has left, or whose dashboard is gone, isn't the feed's failure.
      if (optional) return StreamResult(key, true, emptyList(), old, null, requests)
      throw e
    }
  }

  private suspend fun parsePage(response: HttpResponse): List<FeedEvent> =
    json.parseToJsonElement(response.bodyAsText()).jsonArray.mapNotNull {
      (it as? JsonObject)?.let { event -> parseEvent(event, instance.webUrl) }
    }

  private fun HttpRequestBuilder.auth() {
    header(HttpHeaders.Authorization, "Bearer $token")
    header(HttpHeaders.Accept, "application/vnd.github+json")
    header("X-GitHub-Api-Version", GITHUB_API_VERSION)
  }

  @Serializable
  internal data class StreamCursor(
    val etag: String? = null,
    val lastModified: String? = null,
    /** The newest event IDs from the last answer; paging stops at the first page with one. */
    val seen: List<String> = emptyList(),
    /** Where to authorize SSO, for an organization that asked; not polled until [blockedUntil]. */
    val ssoUrl: String? = null,
    val blockedUntil: Long? = null,
  )

  @Serializable
  internal data class Cursor(
    val streams: Map<String, StreamCursor> = emptyMap(),
    val orgs: List<String>? = null,
    val orgsEtag: String? = null,
    val orgsFetchedAt: Long? = null,
  )

  private companion object {
    const val SEEN = 10
    val ORGS_TTL = 1.hours
    val SSO_RETRY = 1.hours
  }
}

/** GitHub's all-zero object ID, a push's `before` when it created the branch. */
internal const val ZERO_SHA = "0000000000000000000000000000000000000000"

/**
 * One event from GitHub's events API, normalized; null if it has no repo or actor. Payloads are
 * trimmed since October 2025: pushes carry no commits, and PR events carry no title or state.
 */
internal fun parseEvent(event: JsonObject, webUrl: String): FeedEvent? {
  val id = event.string("id") ?: return null
  val type = event.string("type") ?: return null
  val actorJson = event.obj("actor") ?: return null
  val actor =
    FeedActor(
      login = actorJson.string("login") ?: return null,
      avatarUrl = actorJson.string("avatar_url"),
    )
  val repoName = event.obj("repo")?.string("name") ?: return null
  val repo = RepoRef(repoName.substringBefore('/'), repoName.substringAfter('/'))
  val repoUrl = "$webUrl/$repo"
  val createdAt = event.string("created_at")?.let(Instant::parse) ?: return null
  val payload = event.obj("payload") ?: JsonObject(emptyMap())
  val actionRaw = payload.string("action")
  val base =
    FeedEvent(
      id = id,
      kind = FeedEventKind.Other,
      kindRaw = type,
      action = FeedAction.of(actionRaw),
      actionRaw = actionRaw,
      actor = actor,
      repo = repo,
      createdAt = createdAt,
      isPublic = (event["public"] as? JsonPrimitive)?.booleanOrNull,
      webUrl = repoUrl,
    )
  return when (type) {
    "PushEvent" -> {
      val ref = payload.string("ref")?.shortRef()
      val before = payload.string("before")
      val head = payload.string("head")
      // Payloads from before October 2025 list commits, oldest first.
      val commits =
        (payload["commits"] as? JsonArray)
          ?.mapNotNull { c ->
            val commit = c as? JsonObject ?: return@mapNotNull null
            FeedCommit(
              commit.string("sha") ?: return@mapNotNull null,
              commit.string("message")?.lineSequence()?.firstOrNull().orEmpty(),
            )
          }
          ?.reversed()
      base.copy(
        kind = FeedEventKind.Push,
        ref = ref,
        refType =
          RefType.Branch.takeIf { payload.string("ref")?.startsWith("refs/heads/") != false },
        before = before,
        head = head,
        commits = commits,
        commitCount = payload.int("size") ?: commits?.size,
        webUrl =
          when {
            before != null && head != null && before != ZERO_SHA ->
              "$repoUrl/compare/${before.take(12)}...${head.take(12)}"
            head != null -> "$repoUrl/commit/$head"
            else -> repoUrl
          },
      )
    }
    "CreateEvent",
    "DeleteEvent" -> {
      val refType =
        when (payload.string("ref_type")) {
          "branch" -> RefType.Branch
          "tag" -> RefType.Tag
          "repository" -> RefType.Repository
          else -> null
        }
      val ref = payload.string("ref")
      base.copy(
        kind = if (type == "CreateEvent") FeedEventKind.RefCreated else FeedEventKind.RefDeleted,
        ref = ref,
        refType = refType,
        excerpt = payload.string("description")?.takeIf { refType == RefType.Repository },
        webUrl =
          when {
            type == "DeleteEvent" || ref == null -> repoUrl
            refType == RefType.Tag -> "$repoUrl/releases/tag/$ref"
            else -> "$repoUrl/tree/$ref"
          },
      )
    }
    "IssuesEvent" -> {
      val issue = payload.obj("issue")
      base.copy(
        kind = FeedEventKind.Issue,
        subject = issue?.subject(SubjectType.Issue),
        webUrl = issue?.string("html_url") ?: repoUrl,
      )
    }
    "IssueCommentEvent" -> {
      val issue = payload.obj("issue")
      val comment = payload.obj("comment")
      val isPr = issue?.get("pull_request") is JsonObject
      base.copy(
        kind = FeedEventKind.Comment,
        subject = issue?.subject(if (isPr) SubjectType.PullRequest else SubjectType.Issue),
        excerpt = comment?.string("body")?.excerpt(),
        webUrl = comment?.string("html_url") ?: issue?.string("html_url") ?: repoUrl,
      )
    }
    "PullRequestEvent",
    "PullRequestReviewEvent",
    "PullRequestReviewCommentEvent",
    "PullRequestReviewThreadEvent" -> {
      val pr = payload.obj("pull_request")
      val number = payload.int("number") ?: pr?.int("number")
      val subject = number?.let {
        FeedSubject(
          SubjectType.PullRequest,
          it,
          title = pr?.string("title"),
          state = pr?.string("state"),
        )
      }
      val prUrl = pr?.string("html_url") ?: number?.let { "$repoUrl/pull/$it" } ?: repoUrl
      when (type) {
        "PullRequestEvent" ->
          base.copy(kind = FeedEventKind.PullRequest, subject = subject, webUrl = prUrl)
        "PullRequestReviewEvent" -> {
          val review = payload.obj("review")
          base.copy(
            kind = FeedEventKind.Review,
            subject = subject,
            reviewState = review?.string("state")?.lowercase(),
            excerpt = review?.string("body")?.excerpt(),
            webUrl = review?.string("html_url") ?: prUrl,
          )
        }
        "PullRequestReviewCommentEvent" -> {
          val comment = payload.obj("comment")
          base.copy(
            kind = FeedEventKind.ReviewComment,
            subject = subject,
            excerpt = comment?.string("body")?.excerpt(),
            target = comment?.string("path"),
            webUrl = comment?.string("html_url") ?: prUrl,
          )
        }
        else -> base.copy(subject = subject, webUrl = prUrl)
      }
    }
    "ReleaseEvent" -> {
      val release = payload.obj("release")
      val tag = release?.string("tag_name")
      base.copy(
        kind = FeedEventKind.Release,
        ref = tag,
        refType = RefType.Tag,
        title = release?.string("name")?.takeIf { it.isNotBlank() } ?: tag,
        excerpt = release?.string("body")?.excerpt(),
        webUrl = release?.string("html_url") ?: "$repoUrl/releases",
      )
    }
    "WatchEvent" -> base.copy(kind = FeedEventKind.Star)
    "ForkEvent" -> {
      val forkee = payload.obj("forkee")
      base.copy(
        kind = FeedEventKind.Fork,
        target = forkee?.string("full_name"),
        webUrl = forkee?.string("html_url") ?: repoUrl,
      )
    }
    "MemberEvent" -> {
      val member = payload.obj("member")?.string("login")
      base.copy(
        kind = FeedEventKind.Member,
        target = member,
        webUrl = member?.let { "$webUrl/$it" } ?: repoUrl,
      )
    }
    "PublicEvent" -> base.copy(kind = FeedEventKind.Publicized)
    "GollumEvent" -> {
      val pages = (payload["pages"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject) }
      base.copy(
        kind = FeedEventKind.Wiki,
        title = pages.firstOrNull()?.string("title"),
        commitCount = pages.size,
        webUrl = pages.firstOrNull()?.string("html_url") ?: "$repoUrl/wiki",
      )
    }
    "CommitCommentEvent" -> {
      val comment = payload.obj("comment")
      base.copy(
        kind = FeedEventKind.CommitComment,
        head = comment?.string("commit_id"),
        excerpt = comment?.string("body")?.excerpt(),
        webUrl = comment?.string("html_url") ?: repoUrl,
      )
    }
    "DiscussionEvent",
    "DiscussionCommentEvent" -> {
      val discussion = payload.obj("discussion")
      base.copy(
        kind = FeedEventKind.Discussion,
        title = discussion?.string("title"),
        target = discussion?.int("number")?.toString(),
        excerpt = payload.obj("comment")?.string("body")?.excerpt(),
        webUrl = discussion?.string("html_url") ?: "$repoUrl/discussions",
      )
    }
    else -> base
  }
}

private fun JsonObject.subject(type: SubjectType): FeedSubject? {
  val number = int("number") ?: return null
  return FeedSubject(type, number, title = string("title"), state = string("state"))
}

private fun String.shortRef(): String = removePrefix("refs/heads/").removePrefix("refs/tags/")

/** The start of a Markdown body, on one line, for a row's peek. */
internal fun String.excerpt(max: Int = 280): String? {
  val text =
    lineSequence()
      .map { it.trim() }
      // Quoted replies and HTML comments say little on their own.
      .filterNot { it.startsWith(">") || it.startsWith("<!--") }
      .filter { it.isNotEmpty() }
      .joinToString(" ")
      .replace(Regex("""\s+"""), " ")
  if (text.isEmpty()) return null
  return if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"
}

internal fun JsonObject.string(name: String): String? =
  (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
