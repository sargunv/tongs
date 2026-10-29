package dev.sargunv.tongs.forge.github

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Mutation
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.Query
import dev.sargunv.tongs.core.net.HttpException
import dev.sargunv.tongs.engine.DiffSide
import dev.sargunv.tongs.forge.api.DiffReviewThread
import dev.sargunv.tongs.forge.api.DraftReviewComment
import dev.sargunv.tongs.forge.api.FileViewedState
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.PendingReview
import dev.sargunv.tongs.forge.api.PullRequestOverview
import dev.sargunv.tongs.forge.api.PullRequestReviews
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.ReviewAnchor
import dev.sargunv.tongs.forge.api.ReviewComment
import dev.sargunv.tongs.forge.api.ReviewEvent
import dev.sargunv.tongs.forge.api.ReviewSubmission
import dev.sargunv.tongs.forge.github.graphql.AddPendingReviewThreadMutation
import dev.sargunv.tongs.forge.github.graphql.DeletePendingReviewMutation
import dev.sargunv.tongs.forge.github.graphql.MarkFileAsViewedMutation
import dev.sargunv.tongs.forge.github.graphql.PullRequestOverviewQuery
import dev.sargunv.tongs.forge.github.graphql.PullRequestReviewThreadCommentsQuery
import dev.sargunv.tongs.forge.github.graphql.PullRequestReviewThreadsQuery
import dev.sargunv.tongs.forge.github.graphql.PullRequestViewedFilesQuery
import dev.sargunv.tongs.forge.github.graphql.ReplyToReviewThreadMutation
import dev.sargunv.tongs.forge.github.graphql.ResolveReviewThreadMutation
import dev.sargunv.tongs.forge.github.graphql.StartPendingReviewMutation
import dev.sargunv.tongs.forge.github.graphql.SubmitPendingReviewMutation
import dev.sargunv.tongs.forge.github.graphql.UnmarkFileAsViewedMutation
import dev.sargunv.tongs.forge.github.graphql.UnresolveReviewThreadMutation
import dev.sargunv.tongs.forge.github.graphql.type.DiffSide as GqlDiffSide
import dev.sargunv.tongs.forge.github.graphql.type.DraftPullRequestReviewThread
import dev.sargunv.tongs.forge.github.graphql.type.FileViewedState as GqlViewedState
import dev.sargunv.tongs.forge.github.graphql.type.PullRequestReviewCommentState
import dev.sargunv.tongs.forge.github.graphql.type.PullRequestReviewEvent
import dev.sargunv.tongs.forge.github.graphql.type.PullRequestReviewThreadSubjectType
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GitHub's side of the changes screen: overview, threads, viewed state, replies, and resolving
 * through GraphQL. A review with only new line comments goes through REST's create-review call,
 * which takes them all in one request; one that also replies to threads, or continues a pending
 * review, is built up as a pending review through GraphQL and then submitted.
 */
class GitHubPullRequestReviews(
  private val http: HttpClient,
  private val instance: Instance,
  private val token: String,
) : PullRequestReviews {
  private val apollo: ApolloClient by lazy { gitHubApollo(http, instance, token) }
  private val json = Json { explicitNulls = false }

  override suspend fun overview(repo: RepoRef, number: Int): PullRequestOverview {
    val pr =
      checkNotNull(
        apollo.run(PullRequestOverviewQuery(repo.owner, repo.name, number)).repository?.pullRequest,
      ) {
        "No PR $repo#$number"
      }
    return PullRequestOverview(
      id = pr.id,
      title = pr.title,
      state = pr.state.rawValue.lowercase(),
      headOid = pr.headRefOid,
      authorLogin = pr.author?.login,
      webUrl = pr.url,
      viewerDidAuthor = pr.viewerDidAuthor,
      viewerReviewRequested = pr.viewerLatestReviewRequest != null,
      pendingReview =
        pr.reviews
          ?.nodes
          ?.filterNotNull()
          ?.firstOrNull { it.viewerDidAuthor }
          ?.let { PendingReview(it.id, it.body, it.comments.totalCount) },
    )
  }

  override suspend fun threads(repo: RepoRef, number: Int): List<DiffReviewThread> {
    val out = mutableListOf<DiffReviewThread>()
    var after: String? = null
    do {
      val page =
        apollo
          .run(
            PullRequestReviewThreadsQuery(
              repo.owner,
              repo.name,
              number,
              Optional.presentIfNotNull(after),
            ),
          )
          .repository
          ?.pullRequest
          ?.reviewThreads ?: break
      for (node in page.nodes.orEmpty().filterNotNull()) {
        val comments =
          node.comments.nodes
            .orEmpty()
            .filterNotNull()
            .map {
              ReviewComment(
                it.id,
                it.author?.login,
                it.author?.avatarUrl,
                it.body,
                Instant.parse(it.createdAt),
                it.state == PullRequestReviewCommentState.PENDING,
              )
            }
            .toMutableList()
        var cursor = node.comments.pageInfo.endCursor.takeIf { node.comments.pageInfo.hasNextPage }
        while (cursor != null) {
          val more =
            checkNotNull(
              apollo
                .run(PullRequestReviewThreadCommentsQuery(node.id, cursor))
                .node
                ?.onPullRequestReviewThread
                ?.comments,
            ) {
              "GitHub didn't return thread ${node.id}"
            }
          comments +=
            more.nodes.orEmpty().filterNotNull().map {
              ReviewComment(
                it.id,
                it.author?.login,
                it.author?.avatarUrl,
                it.body,
                Instant.parse(it.createdAt),
                it.state == PullRequestReviewCommentState.PENDING,
              )
            }
          cursor = more.pageInfo.endCursor.takeIf { more.pageInfo.hasNextPage }
        }
        out +=
          DiffReviewThread(
            id = node.id,
            path = node.path,
            side = node.diffSide.toSide(),
            startSide = node.startDiffSide?.toSide(),
            line = node.line,
            startLine = node.startLine,
            originalLine = node.originalLine,
            isResolved = node.isResolved,
            isOutdated = node.isOutdated,
            isFileLevel = node.subjectType == PullRequestReviewThreadSubjectType.FILE,
            comments = comments,
            viewerCanReply = node.viewerCanReply,
            viewerCanResolve = node.viewerCanResolve,
            viewerCanUnresolve = node.viewerCanUnresolve,
            resolvedBy = node.resolvedBy?.login,
          )
      }
      after = page.pageInfo.endCursor.takeIf { page.pageInfo.hasNextPage }
    } while (after != null)
    return out
  }

  override suspend fun viewedFiles(repo: RepoRef, number: Int): Map<String, FileViewedState> {
    val out = LinkedHashMap<String, FileViewedState>()
    var after: String? = null
    do {
      val page =
        apollo
          .run(
            PullRequestViewedFilesQuery(
              repo.owner,
              repo.name,
              number,
              Optional.presentIfNotNull(after),
            ),
          )
          .repository
          ?.pullRequest
          ?.files ?: break
      for (node in page.nodes.orEmpty().filterNotNull()) {
        out[node.path] =
          when (node.viewerViewedState) {
            GqlViewedState.VIEWED -> FileViewedState.Viewed
            GqlViewedState.DISMISSED -> FileViewedState.Dismissed
            else -> FileViewedState.Unviewed
          }
      }
      after = page.pageInfo.endCursor.takeIf { page.pageInfo.hasNextPage }
    } while (after != null)
    return out
  }

  override suspend fun setViewed(pullRequestId: String, path: String, viewed: Boolean) {
    if (viewed) apollo.run(MarkFileAsViewedMutation(pullRequestId, path))
    else apollo.run(UnmarkFileAsViewedMutation(pullRequestId, path))
  }

  override suspend fun submitReview(repo: RepoRef, number: Int, submission: ReviewSubmission) {
    val path = "repos/${repo.owner}/${repo.name}/pulls/$number/reviews"
    val response =
      http.post("${instance.apiUrl}/$path") {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header("X-GitHub-Api-Version", GITHUB_API_VERSION)
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateReviewDto.serializer(), submission.toGitHub()))
      }
    if (!response.status.isSuccess()) {
      throw HttpException(response.status, "POST $path", response.bodyAsText())
    }
  }

  override suspend fun reply(
    threadId: String,
    body: String,
    pendingReviewId: String?,
  ): ReviewComment {
    val comment =
      checkNotNull(
        apollo
          .run(
            ReplyToReviewThreadMutation(threadId, body, Optional.presentIfNotNull(pendingReviewId)),
          )
          .addPullRequestReviewThreadReply
          ?.comment,
      ) {
        "GitHub didn't return the reply"
      }
    return ReviewComment(
      id = comment.id,
      authorLogin = comment.author?.login,
      authorAvatarUrl = comment.author?.avatarUrl,
      body = comment.body,
      createdAt = Instant.parse(comment.createdAt),
      pending = comment.state == PullRequestReviewCommentState.PENDING,
    )
  }

  override suspend fun setResolved(threadId: String, resolved: Boolean) {
    if (resolved) apollo.run(ResolveReviewThreadMutation(threadId))
    else apollo.run(UnresolveReviewThreadMutation(threadId))
  }

  override suspend fun startPendingReview(
    pullRequestId: String,
    commitOid: String,
    comments: List<DraftReviewComment>,
  ): String {
    val threads = comments.map {
      val at = it.anchor.toGitHub()
      DraftPullRequestReviewThread(
        body = it.body,
        path = Optional.present(at.path),
        line = Optional.present(at.line),
        side = Optional.present(GqlDiffSide.safeValueOf(at.side)),
        startLine = Optional.presentIfNotNull(at.startLine),
        startSide = Optional.presentIfNotNull(at.startSide?.let(GqlDiffSide::safeValueOf)),
      )
    }
    val review =
      apollo
        .run(StartPendingReviewMutation(pullRequestId, commitOid, Optional.present(threads)))
        .addPullRequestReview
        ?.pullRequestReview
    return checkNotNull(review) { "GitHub didn't start the review" }.id
  }

  override suspend fun addToPendingReview(reviewId: String, comment: DraftReviewComment) {
    val at = comment.anchor.toGitHub()
    apollo.run(
      AddPendingReviewThreadMutation(
        reviewId = reviewId,
        path = at.path,
        body = comment.body,
        line = at.line,
        side = GqlDiffSide.safeValueOf(at.side),
        startLine = Optional.presentIfNotNull(at.startLine),
        startSide = Optional.presentIfNotNull(at.startSide?.let(GqlDiffSide::safeValueOf)),
      ),
    )
  }

  override suspend fun submitPendingReview(reviewId: String, event: ReviewEvent, body: String) {
    apollo.run(
      SubmitPendingReviewMutation(
        reviewId,
        when (event) {
          ReviewEvent.Comment -> PullRequestReviewEvent.COMMENT
          ReviewEvent.Approve -> PullRequestReviewEvent.APPROVE
          ReviewEvent.RequestChanges -> PullRequestReviewEvent.REQUEST_CHANGES
        },
        Optional.present(body),
      ),
    )
  }

  override suspend fun deletePendingReview(reviewId: String) {
    apollo.run(DeletePendingReviewMutation(reviewId))
  }
}

/** Runs a query, throwing on transport and GraphQL errors alike. */
private suspend fun <D : Query.Data> ApolloClient.run(query: Query<D>): D =
  query(query).execute().checked(query)

/** Runs a mutation, throwing on transport and GraphQL errors alike. */
private suspend fun <D : Mutation.Data> ApolloClient.run(mutation: Mutation<D>): D =
  mutation(mutation).execute().checked(mutation)

private fun <D : Operation.Data> ApolloResponse<D>.checked(operation: Operation<D>): D {
  exception?.let { throw it }
  errors
    ?.takeIf { it.isNotEmpty() }
    ?.let { errors ->
      error("${operation.name()}: ${errors.joinToString("; ") { it.message }}")
    }
  return checkNotNull(data) { "${operation.name()} returned no data" }
}

private fun GqlDiffSide.toSide() = if (this == GqlDiffSide.LEFT) DiffSide.Old else DiffSide.New

/** GitHub's name for a side: the old file is on the left. */
internal fun DiffSide.toGitHub(): String = if (this == DiffSide.Old) "LEFT" else "RIGHT"

/** A line comment as GitHub's REST API places it. */
@Serializable
internal data class GitHubCommentPosition(
  val path: String,
  val line: Int,
  val side: String,
  @SerialName("start_line") val startLine: Int? = null,
  @SerialName("start_side") val startSide: String? = null,
)

/**
 * Lowers a neutral anchor to GitHub's: `line` and `side` are the range's end, and a multi-line
 * range adds `start_line` and `start_side`.
 */
internal fun ReviewAnchor.toGitHub(): GitHubCommentPosition =
  GitHubCommentPosition(
    path = path,
    line = endLine,
    side = side.toGitHub(),
    startLine = startLine.takeIf { isMultiLine },
    startSide = startSide.toGitHub().takeIf { isMultiLine },
  )

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class CreateReviewDto(
  @SerialName("commit_id") val commitId: String,
  @EncodeDefault val event: String,
  val body: String? = null,
  val comments: List<CommentDto>,
)

@Serializable
internal data class CommentDto(
  val path: String,
  val body: String,
  val line: Int,
  val side: String,
  @SerialName("start_line") val startLine: Int? = null,
  @SerialName("start_side") val startSide: String? = null,
)

internal fun ReviewSubmission.toGitHub() =
  CreateReviewDto(
    commitId = commitOid,
    event =
      when (event) {
        ReviewEvent.Comment -> "COMMENT"
        ReviewEvent.Approve -> "APPROVE"
        ReviewEvent.RequestChanges -> "REQUEST_CHANGES"
      },
    body = body.takeIf { it.isNotBlank() },
    comments =
      comments.map {
        val at = it.anchor.toGitHub()
        CommentDto(at.path, it.body, at.line, at.side, at.startLine, at.startSide)
      },
  )
