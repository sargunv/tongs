package dev.sargunv.tongs.forge.github

import com.apollographql.apollo.api.json.jsonReader
import com.apollographql.apollo.api.parseResponse
import dev.sargunv.tongs.core.net.HttpException
import dev.sargunv.tongs.forge.api.FeedCommit
import dev.sargunv.tongs.forge.api.FeedHydration
import dev.sargunv.tongs.forge.api.FeedHydrator
import dev.sargunv.tongs.forge.api.ForgeException
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.PushCommits
import dev.sargunv.tongs.forge.api.PushRef
import dev.sargunv.tongs.forge.api.SubjectRef
import dev.sargunv.tongs.forge.api.SubjectState
import dev.sargunv.tongs.forge.api.SubjectType
import dev.sargunv.tongs.forge.github.graphql.FeedPushQuery
import dev.sargunv.tongs.forge.github.graphql.FeedSubjectQuery
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.Buffer

/**
 * Batches subject state in GraphQL and hydrates pushes through REST compare, at most [compareLimit]
 * per poll. New branches compare against the repository's default branch; their first commit stands
 * in when there is no base. Older pushes wait for the next poll.
 */
class GitHubFeedHydrator(
  private val http: HttpClient,
  private val instance: Instance,
  private val token: String,
  private val batchSize: Int = 100,
  private val compareLimit: Int = 10,
  /** The most commits kept per push. */
  private val keepCommits: Int = 20,
) : FeedHydrator {
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun hydrate(subjects: List<SubjectRef>, pushes: List<PushRef>): FeedHydration {
    val pending = pushes.take(compareLimit)
    val needsBase = pending.filter { it.before == null || it.before == ZERO_SHA }
    val items: List<Any> = subjects + needsBase
    val batches = coroutineScope {
      items.chunked(batchSize).map { batch -> async { hydrateBatch(batch) } }.awaitAll()
    }
    val states = batches.flatMap { it.subjects }
    val placed = batches.flatMap { it.pushes }
    val unplaced =
      pending.filter { it.before != null && it.before != ZERO_SHA }.map { it to it.before!! } +
        batches.flatMap { it.unplaced }
    val compared = coroutineScope {
      unplaced.map { (push, base) -> async { compare(push, base) } }.awaitAll()
    }
    val points = batches.mapNotNull { it.points }
    return FeedHydration(
      subjects = states,
      pushes = placed + compared.map { it.first },
      requests = compared.sumOf { it.second },
      points = points.takeIf { it.isNotEmpty() }?.sum(),
    )
  }

  private class Batch(
    val subjects: List<SubjectState>,
    val pushes: List<PushCommits>,
    /** New branches with their default branch as the comparison base. */
    val unplaced: List<Pair<PushRef, String>>,
    val points: Int?,
  )

  private suspend fun hydrateBatch(batch: List<Any>): Batch {
    val parts = batch.mapIndexed { i, item ->
      when (item) {
        is SubjectRef ->
          BatchPart(
            "s$i",
            FeedSubjectQuery.OPERATION_DOCUMENT,
            mapOf("owner" to item.repo.owner, "name" to item.repo.name, "number" to item.number),
          )
        is PushRef ->
          BatchPart(
            "p$i",
            FeedPushQuery.OPERATION_DOCUMENT,
            mapOf(
              "owner" to item.repo.owner,
              "name" to item.repo.name,
              "head" to item.head,
            ),
          )
        else -> error("Not a subject or push: $item")
      }
    }
    val response = offline {
      http.post(graphQlUrl(instance)) {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(batchBody("FeedHydrationBatch", parts))
      }
    }
    response.throwIfFailed("GraphQL feed hydration")
    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    val data = body["data"] as? JsonObject
    if (data == null) {
      val errors = body["errors"] as? JsonArray
      val types = errors.orEmpty().mapNotNull { (it as? JsonObject)?.get("type")?.toString() }
      if ("\"RATE_LIMITED\"" in types) throw ForgeException.RateLimited(response.rateLimitReset)
      error("GraphQL feed hydration: ${errors?.firstOrNull() ?: "no data"}")
    }
    val points = (data["rateLimit"] as? JsonObject)?.get("cost")?.jsonPrimitive?.intOrNull
    val subjects = mutableListOf<SubjectState>()
    val pushes = mutableListOf<PushCommits>()
    val unplaced = mutableListOf<Pair<PushRef, String>>()
    for ((i, item) in batch.withIndex()) {
      val part = parts[i]
      // An alias GitHub couldn't resolve (deleted, transferred, or behind SSO) is null, with an
      // error for its path; it's described as nothing rather than failing the batch.
      val alias = data[part.alias]?.takeUnless { it is JsonNull }
      val wrapped = alias?.let {
        Buffer()
          .writeUtf8(buildJsonObject { putJsonObject("data") { put("repository", it) } }.toString())
      }
      when (item) {
        is SubjectRef -> {
          val query = FeedSubjectQuery(item.repo.owner, item.repo.name, item.number)
          val subject = wrapped?.let {
            query.parseResponse(it.jsonReader()).data?.repository?.issueOrPullRequest
          }
          subjects += subjectState(item, subject)
        }
        is PushRef -> {
          val query = FeedPushQuery(item.repo.owner, item.repo.name, item.head)
          val repository = wrapped?.let { query.parseResponse(it.jsonReader()).data?.repository }
          val commit = repository?.`object`?.onCommit
          val default = repository?.defaultBranchRef?.name
          if (default != null && default != item.ref) {
            unplaced += item to default
          } else {
            pushes +=
              PushCommits(
                item,
                commit?.let { listOf(FeedCommit(it.oid, it.messageHeadline)) },
                null,
              )
          }
        }
      }
    }
    return Batch(subjects, pushes, unplaced, points)
  }

  /** The commits from [base] to the push's head, from the compare API: exact, but heavy. */
  private suspend fun compare(push: PushRef, base: String): Pair<PushCommits, Int> {
    var requests = 0
    suspend fun page(number: Int): JsonObject {
      requests++
      val response = offline {
        http.get(
          "${instance.apiUrl}/repos/${push.repo}/compare/$base...${push.head}?per_page=100&page=$number",
        ) {
          header(HttpHeaders.Authorization, "Bearer $token")
          header(HttpHeaders.Accept, "application/vnd.github+json")
          header("X-GitHub-Api-Version", GITHUB_API_VERSION)
        }
      }
      response.throwIfFailed("GET compare")
      return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }
    try {
      val first = page(1)
      val total = first.int("total_commits")
      // Compare pages are oldest first. One additional request gets the newest page of a large
      // push.
      val body = if (total != null && total > 100) page((total - 1) / 100 + 1) else first
      val commits =
        (body["commits"] as? JsonArray).orEmpty().mapNotNull { c ->
          val commit = c as? JsonObject ?: return@mapNotNull null
          val message = commit.obj("commit")?.string("message") ?: ""
          FeedCommit(commit.string("sha") ?: return@mapNotNull null, message.lineSequence().first())
        }
      return PushCommits(push, commits.reversed().take(keepCommits), total ?: commits.size) to
        requests
    } catch (e: ForgeException.NoAccess) {
      return PushCommits(push, null, null) to requests
    } catch (e: HttpException) {
      // Gone or unreachable from the base; transient failures are retried after sync's backoff.
      if (e.status.value !in setOf(404, 409, 422)) throw e
      return PushCommits(push, null, null) to requests
    }
  }
}

private fun subjectState(
  ref: SubjectRef,
  subject: FeedSubjectQuery.IssueOrPullRequest?,
): SubjectState {
  subject?.onPullRequest?.let { pr ->
    return SubjectState(
      ref,
      SubjectType.PullRequest,
      pr.title,
      pr.prState.rawValue,
      isDraft = pr.isDraft,
    )
  }
  subject?.onIssue?.let { issue ->
    return SubjectState(
      ref,
      SubjectType.Issue,
      issue.title,
      issue.issueState.rawValue,
      stateReason = issue.stateReason?.rawValue,
      isDraft = false,
    )
  }
  return SubjectState(ref)
}

/** One operation's selection in a batched query, under [alias], with its own [variables]. */
internal class BatchPart(val alias: String, val document: String, val variables: Map<String, Any?>)

/**
 * One request body holding every part's selection under its alias, with each variable suffixed by
 * the alias (`$owner_s0`), plus `rateLimit { cost }`. Each document must be a single query whose
 * body is one field and that uses no fragments.
 */
internal fun batchBody(name: String, parts: List<BatchPart>): String {
  val variable = Regex("""\$(\w+)""")
  val definitions = mutableListOf<String>()
  val selections = StringBuilder()
  for (part in parts) {
    val document = part.document
    val open = document.indexOf('(')
    val close = document.indexOf(')', open)
    val rename: (MatchResult) -> String = { "\$${it.groupValues[1]}_${part.alias}" }
    definitions += document.substring(open + 1, close).replace(variable, rename)
    val bodyStart = document.indexOf('{', close)
    val selection = document.substring(bodyStart + 1, document.lastIndexOf('}')).trim()
    selections.append("${part.alias}: ").append(selection.replace(variable, rename)).append(' ')
  }
  val text = buildString {
    append("query $name(")
    append(definitions.joinToString(", "))
    append(") { rateLimit { cost remaining resetAt } ")
    append(selections)
    append('}')
  }
  return buildJsonObject {
    put("query", text)
    putJsonObject("variables") {
      for (part in parts) for ((key, value) in part.variables) putValue(
        "${key}_${part.alias}",
        value,
      )
    }
  }
    .toString()
}

private fun JsonObjectBuilder.putValue(key: String, value: Any?) {
  when (value) {
    null -> put(key, JsonNull)
    is String -> put(key, value)
    is Int -> put(key, value)
    is Long -> put(key, value)
    is Boolean -> put(key, value)
    else -> error("Unsupported variable $key: $value")
  }
}
