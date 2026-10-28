package dev.sargunv.tongs.forge.github

import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.json.jsonReader
import com.apollographql.apollo.api.parseResponse
import dev.sargunv.tongs.forge.api.ForgeException
import dev.sargunv.tongs.forge.api.Hydration
import dev.sargunv.tongs.forge.api.InboxHydrator
import dev.sargunv.tongs.forge.api.InboxThread
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.SubjectActivity
import dev.sargunv.tongs.forge.api.SubjectType
import dev.sargunv.tongs.forge.api.ThreadHydration
import dev.sargunv.tongs.forge.api.summarizeWhatsNew
import dev.sargunv.tongs.forge.github.graphql.InboxHydrationQuery
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.Buffer

/**
 * Hydrates inbox rows with one GraphQL request per [batchSize] threads. Each thread is the
 * `repository` field of [InboxHydrationQuery] under its own alias, with its own `since` (the
 * thread's `last_read_at`), so the timeline holds only what's new. Apollo's generated parser reads
 * each alias.
 *
 * GitHub charges a query by its connections: [batchSize] threads with two each (`commits(last: 1)`
 * and `timelineItems(last: 20)`) is 100 requests, which rounds to 1 point of the 5,000 an hour.
 * Only issues and PRs have anything to hydrate; other subjects come back empty without a request.
 */
class GitHubHydrator(
  private val http: HttpClient,
  private val instance: Instance,
  private val token: String,
  /** The account's login: your own comments and pushes aren't news. */
  private val viewer: String,
  private val batchSize: Int = 50,
  private val timelineLimit: Int = 20,
) : InboxHydrator {
  override suspend fun hydrate(threads: List<InboxThread>): Hydration {
    val (subjects, others) =
      threads.partition {
        it.subject.number != null &&
          (it.subject.type == SubjectType.PullRequest || it.subject.type == SubjectType.Issue)
      }
    val results = others.mapTo(mutableListOf()) { ThreadHydration(it.id) }
    // Batches go out together: each takes seconds, and GitHub charges the same either way.
    val batches = coroutineScope {
      subjects.chunked(batchSize).map { async { hydrateBatch(it) } }.awaitAll()
    }
    for ((hydrations, _) in batches) results += hydrations
    val costs = batches.mapNotNull { it.second }
    return Hydration(results, costs.takeIf { it.isNotEmpty() }?.sum())
  }

  private suspend fun hydrateBatch(batch: List<InboxThread>): Pair<List<ThreadHydration>, Int?> {
    val request = batchRequest(batch, timelineLimit)
    val response =
      try {
        http.post(graphQlUrl(instance)) {
          header(HttpHeaders.Authorization, "Bearer $token")
          contentType(ContentType.Application.Json)
          setBody(request.body)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: IOException) {
        throw ForgeException.Offline(e)
      }
    response.throwIfFailed("GraphQL hydration")
    val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    val data = json["data"] as? JsonObject
    if (data == null) {
      val errors = json["errors"] as? JsonArray
      val types = errors.orEmpty().mapNotNull { (it as? JsonObject)?.get("type")?.toString() }
      if ("\"RATE_LIMITED\"" in types) throw ForgeException.RateLimited(response.rateLimitReset)
      error("GraphQL hydration: ${errors?.firstOrNull() ?: "no data"}")
    }
    val cost = (data["rateLimit"] as? JsonObject)?.get("cost")?.jsonPrimitive?.intOrNull
    val hydrations = batch.mapIndexed { i, thread ->
      val alias = data["t$i"]
      // A thread GitHub couldn't resolve (deleted, transferred, or behind SSO) comes back null
      // with an error for its alias; describe it as nothing rather than failing the batch.
      if (alias == null || alias is JsonNull) return@mapIndexed ThreadHydration(thread.id)
      val query = request.queries[i]
      val one = buildJsonObject { putJsonObject("data") { put("repository", alias) } }
      val parsed = query.parseResponse(Buffer().writeUtf8(one.toString()).jsonReader())
      val subject = parsed.data?.repository?.issueOrPullRequest
      subject?.let { hydration(thread, it, viewer, timelineLimit) } ?: ThreadHydration(thread.id)
    }
    return hydrations to cost
  }
}

/** A batched request: the body to send, and each alias's query for parsing its part. */
internal class BatchRequest(val body: String, val queries: List<InboxHydrationQuery>)

/**
 * Builds one document with [InboxHydrationQuery]'s selection under an alias per thread (`t0`, `t1`,
 * …) and its variables suffixed to match (`$owner_0`), plus `rateLimit { cost }`.
 */
internal fun batchRequest(batch: List<InboxThread>, timelineLimit: Int): BatchRequest {
  val document = InboxHydrationQuery.OPERATION_DOCUMENT
  val open = document.indexOf('(')
  val close = document.indexOf(')', open)
  val definitions = document.substring(open + 1, close)
  val bodyStart = document.indexOf('{', close)
  val selection = document.substring(bodyStart + 1, document.lastIndexOf('}')).trim()
  val variable = Regex("""\$(\w+)""")
  val queries = batch.map {
    InboxHydrationQuery(
      owner = it.repo.owner,
      name = it.repo.name,
      number = it.subject.number!!,
      since = Optional.presentIfNotNull(it.lastReadAt?.toString()),
      timeline = timelineLimit,
    )
  }
  val text = buildString {
    append("query InboxHydrationBatch(")
    append(
      batch.indices.joinToString(", ") { i ->
        definitions.replace(variable) { "\$${it.groupValues[1]}_$i" }
      },
    )
    append(") { rateLimit { cost remaining resetAt } ")
    for (i in batch.indices) {
      append("t$i: ")
      append(selection.replace(variable) { "\$${it.groupValues[1]}_$i" })
      append(' ')
    }
    append('}')
  }
  val body = buildJsonObject {
    put("query", text)
    putJsonObject("variables") {
      for ((i, query) in queries.withIndex()) {
        put("owner_$i", query.owner)
        put("name_$i", query.name)
        put("number_$i", query.number)
        put("since_$i", query.since.getOrNull())
        put("timeline_$i", query.timeline)
      }
    }
  }
  return BatchRequest(body.toString(), queries)
}

internal fun hydration(
  thread: InboxThread,
  subject: InboxHydrationQuery.IssueOrPullRequest,
  viewer: String,
  timelineLimit: Int,
): ThreadHydration {
  subject.onPullRequest?.let { pr ->
    val nodes = pr.prTimeline.nodes.orEmpty().filterNotNull()
    val activity = nodes.mapNotNull { node ->
      node.onPullRequestCommit?.let { SubjectActivity.Commit(it.commit.author?.user?.login) }
        ?: node.onHeadRefForcePushedEvent?.let { SubjectActivity.ForcePush(it.actor?.login) }
        ?: node.onIssueComment?.let { SubjectActivity.Comment(it.author?.login) }
        ?: node.onPullRequestReview?.let {
          SubjectActivity.Review(it.author?.login, it.state.rawValue)
        }
        ?: node.onReviewRequestedEvent?.let {
          val reviewer = it.requestedReviewer
          SubjectActivity.ReviewRequested(
            it.actor?.login,
            reviewer?.onUser?.login ?: reviewer?.onBot?.login ?: reviewer?.onTeam?.combinedSlug,
          )
        }
        ?: node.onMergedEvent?.let { SubjectActivity.Merged(it.actor?.login) }
        ?: node.onClosedEvent?.let { SubjectActivity.Closed(it.actor?.login) }
        ?: node.onReopenedEvent?.let { SubjectActivity.Reopened(it.actor?.login) }
        ?: node.onReadyForReviewEvent?.let { SubjectActivity.ReadyForReview(it.actor?.login) }
        ?: node.onConvertToDraftEvent?.let { SubjectActivity.ConvertedToDraft(it.actor?.login) }
        ?: node.onAssignedEvent?.let {
          SubjectActivity.Assigned(
            it.actor?.login,
            it.assignee?.onUser?.login ?: it.assignee?.onBot?.login,
          )
        }
    }
    val head = pr.commits.nodes?.lastOrNull()?.commit
    val checks = head?.statusCheckRollup?.state?.rawValue
    // Failing checks on a commit made since you last looked are news, whoever pushed it.
    val headIsNew =
      head != null &&
        (thread.lastReadAt == null || Instant.parse(head.committedDate) > thread.lastReadAt!!)
    val failed = checks == "FAILURE" || checks == "ERROR"
    return ThreadHydration(
      threadId = thread.id,
      subjectState = pr.prState.rawValue,
      isDraft = pr.isDraft,
      checks = checks,
      reviewDecision = pr.reviewDecision?.rawValue,
      whatsNew =
        summarizeWhatsNew(
          activity,
          viewer,
          checksFailed = failed && headIsNew && thread.unread,
          truncated = nodes.size >= timelineLimit,
        ),
    )
  }
  subject.onIssue?.let { issue ->
    val nodes = issue.issueTimeline.nodes.orEmpty().filterNotNull()
    val activity = nodes.mapNotNull { node ->
      node.onIssueComment?.let { SubjectActivity.Comment(it.author?.login) }
        ?: node.onClosedEvent?.let { SubjectActivity.Closed(it.actor?.login) }
        ?: node.onReopenedEvent?.let { SubjectActivity.Reopened(it.actor?.login) }
        ?: node.onAssignedEvent?.let {
          SubjectActivity.Assigned(
            it.actor?.login,
            it.assignee?.onUser?.login ?: it.assignee?.onBot?.login,
          )
        }
    }
    return ThreadHydration(
      threadId = thread.id,
      subjectState = issue.issueState.rawValue,
      stateReason = issue.stateReason?.rawValue,
      whatsNew = summarizeWhatsNew(activity, viewer, truncated = nodes.size >= timelineLimit),
    )
  }
  return ThreadHydration(thread.id)
}
