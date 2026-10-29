package dev.sargunv.tongs.forge.github

import com.apollographql.apollo.ApolloCall
import com.apollographql.apollo.api.Mutation
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.exception.ApolloHttpException
import dev.sargunv.tongs.forge.api.Actor
import dev.sargunv.tongs.forge.api.Check
import dev.sargunv.tongs.forge.api.CheckState
import dev.sargunv.tongs.forge.api.ChecksSummary
import dev.sargunv.tongs.forge.api.EventKind
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.Label
import dev.sargunv.tongs.forge.api.MergeMethod
import dev.sargunv.tongs.forge.api.Mergeable
import dev.sargunv.tongs.forge.api.PullRequestSummary
import dev.sargunv.tongs.forge.api.Reaction
import dev.sargunv.tongs.forge.api.Reactions
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.ReviewDecision
import dev.sargunv.tongs.forge.api.ReviewState
import dev.sargunv.tongs.forge.api.ReviewThread
import dev.sargunv.tongs.forge.api.ReviewerVerdict
import dev.sargunv.tongs.forge.api.StateReason
import dev.sargunv.tongs.forge.api.ThreadDetail
import dev.sargunv.tongs.forge.api.ThreadKind
import dev.sargunv.tongs.forge.api.ThreadLoad
import dev.sargunv.tongs.forge.api.ThreadReference
import dev.sargunv.tongs.forge.api.ThreadSource
import dev.sargunv.tongs.forge.api.ThreadState
import dev.sargunv.tongs.forge.api.TimelineItem
import dev.sargunv.tongs.forge.api.TimelinePage
import dev.sargunv.tongs.forge.api.ViewerAbilities
import dev.sargunv.tongs.forge.github.graphql.ThreadAddCommentMutation
import dev.sargunv.tongs.forge.github.graphql.ThreadAddReactionMutation
import dev.sargunv.tongs.forge.github.graphql.ThreadCloseIssueMutation
import dev.sargunv.tongs.forge.github.graphql.ThreadClosePullRequestMutation
import dev.sargunv.tongs.forge.github.graphql.ThreadMergeMutation
import dev.sargunv.tongs.forge.github.graphql.ThreadPullRequestFilesQuery
import dev.sargunv.tongs.forge.github.graphql.ThreadQuery
import dev.sargunv.tongs.forge.github.graphql.ThreadRemoveReactionMutation
import dev.sargunv.tongs.forge.github.graphql.ThreadReopenIssueMutation
import dev.sargunv.tongs.forge.github.graphql.ThreadReopenPullRequestMutation
import dev.sargunv.tongs.forge.github.graphql.ThreadReviewThreadsQuery
import dev.sargunv.tongs.forge.github.graphql.ThreadTimelineQuery
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadActor
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadAssigned
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadAutoMergeDisabled
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadAutoMergeEnabled
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadBaseRefChanged
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadClosed
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadComment
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadCommit
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadConvertToDraft
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadCrossReferenced
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadDemilestoned
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadHeadRefDeleted
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadHeadRefForcePushed
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadIssue
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadIssueTimeline
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadLabel
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadLabeled
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadLocked
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadMarkedAsDuplicate
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadMerged
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadMilestoned
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadPageInfo
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadPullRequest
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadPullRequestTimeline
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadReactions
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadReadyForReview
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadReference as ThreadReferenceFields
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadReferenced
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadRenamed
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadReopened
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadReview
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadReviewComment
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadReviewDismissed
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadReviewRequestRemoved
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadReviewRequested
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadReviewThread
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadReviewThreadsPage
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadReviewer
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadUnassigned
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadUnlabeled
import dev.sargunv.tongs.forge.github.graphql.fragment.ThreadUnlocked
import dev.sargunv.tongs.forge.github.graphql.type.CheckConclusionState
import dev.sargunv.tongs.forge.github.graphql.type.CheckRunState
import dev.sargunv.tongs.forge.github.graphql.type.CheckStatusState
import dev.sargunv.tongs.forge.github.graphql.type.FileViewedState
import dev.sargunv.tongs.forge.github.graphql.type.IssueClosedStateReason
import dev.sargunv.tongs.forge.github.graphql.type.PullRequestMergeMethod
import dev.sargunv.tongs.forge.github.graphql.type.ReactionContent
import dev.sargunv.tongs.forge.github.graphql.type.RepositoryPermission
import dev.sargunv.tongs.forge.github.graphql.type.StatusState
import io.ktor.client.HttpClient
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException

/** A GraphQL request GitHub refused or couldn't answer. [type] is GitHub's, like `NOT_FOUND`. */
class GitHubGraphQLException(message: String, val type: String? = null, cause: Throwable? = null) :
  Exception(message, cause)

/**
 * Issue and PR conversations over GitHub's GraphQL API. One [load] is one request (1 point of rate
 * limit), plus pages for older review threads and the PR's viewed file count.
 */
internal class GitHubThreads(http: HttpClient, instance: Instance, token: String) : ThreadSource {
  private val apollo = gitHubApollo(http, instance, token)

  override suspend fun load(
    repo: RepoRef,
    number: Int,
    headCount: Int,
    tailCount: Int,
  ): ThreadLoad {
    val what = "$repo#$number"
    val data =
      apollo.query(ThreadQuery(repo.owner, repo.name, number, headCount, tailCount)).fetch(
        "load $what",
        what,
      ) {
        it.repository?.issueOrPullRequest != null
      }
    val repository = data.repository!!
    val thread = repository.issueOrPullRequest!!
    thread.onIssue?.let { issue ->
      val head = issue.head.threadIssueTimeline
      return ThreadLoad(
        detail = issue.threadIssue.toDetail(repo),
        head = head.toPage(),
        tail = issue.tail.threadIssueTimeline.toPage(),
        timelineCount = head.filteredCount,
      )
    }
    thread.onPullRequest?.let { pr ->
      val head = pr.head.threadPullRequestTimeline
      val canMerge =
        repository.viewerPermission in
          setOf(
            RepositoryPermission.WRITE,
            RepositoryPermission.MAINTAIN,
            RepositoryPermission.ADMIN,
          )
      val methods =
        if (!canMerge) emptyList()
        else
          buildList {
            if (repository.mergeCommitAllowed) add(MergeMethod.Merge)
            if (repository.squashMergeAllowed) add(MergeMethod.Squash)
            if (repository.rebaseMergeAllowed) add(MergeMethod.Rebase)
          }
      val default = MergeMethod.of(repository.viewerDefaultMergeMethod.rawValue)
      val detail = pr.threadPullRequest.toDetail(repo, methods, default.takeIf { it in methods })
      val viewed = if (pr.threadPullRequest.changedFiles > 0) viewedFiles(repo, number) else 0
      return ThreadLoad(
        detail = detail.copy(pullRequest = detail.pullRequest?.copy(viewedFiles = viewed)),
        head = head.toPage(),
        tail = pr.tail.threadPullRequestTimeline.toPage(),
        timelineCount = head.filteredCount,
        reviewThreads = reviewThreads(repo, number, pr.reviewThreads.threadReviewThreadsPage),
      )
    }
    throw GitHubGraphQLException("$what is a ${thread.__typename}, not an issue or PR")
  }

  private suspend fun reviewThreads(
    repo: RepoRef,
    number: Int,
    initial: ThreadReviewThreadsPage,
  ): List<ReviewThread> {
    var page = initial
    val pages = mutableListOf(initial)
    val cursors = mutableSetOf<String>()
    while (page.pageInfo.hasPreviousPage) {
      val cursor = page.pageInfo.startCursor
      if (cursor == null || !cursors.add(cursor)) {
        throw GitHubGraphQLException("Couldn't load review threads: GitHub sent no earlier cursor")
      }
      page =
        apollo
          .query(ThreadReviewThreadsQuery(repo.owner, repo.name, number, cursor))
          .fetch("load review threads of $repo#$number") { it.repository?.pullRequest != null }
          .repository!!
          .pullRequest!!
          .reviewThreads
          .threadReviewThreadsPage
      pages += page
    }
    return pages
      .asReversed()
      .flatMap { it.nodes.orEmpty() }
      .mapNotNull { it?.threadReviewThread?.toReviewThread() }
      .distinctBy { it.id }
  }

  override suspend fun loadTimeline(
    repo: RepoRef,
    number: Int,
    cursor: String,
    count: Int,
  ): TimelinePage {
    val what = "$repo#$number"
    val thread =
      apollo
        .query(ThreadTimelineQuery(repo.owner, repo.name, number, cursor, count))
        .fetch("load more of $what", what) { it.repository?.issueOrPullRequest != null }
        .repository!!
        .issueOrPullRequest!!
    return thread.onIssue?.timelineItems?.threadIssueTimeline?.toPage()
      ?: thread.onPullRequest?.timelineItems?.threadPullRequestTimeline?.toPage()
      ?: throw GitHubGraphQLException("$what is a ${thread.__typename}, not an issue or PR")
  }

  /**
   * Counts the files the viewer marked viewed, a page of 100 at a time. Null if GitHub won't say,
   * so the summary shows no progress rather than zero.
   */
  private suspend fun viewedFiles(repo: RepoRef, number: Int): Int? {
    var cursor: String? = null
    var viewed = 0
    repeat(MAX_FILE_PAGES) {
      val files =
        try {
          apollo
            .query(
              ThreadPullRequestFilesQuery(
                repo.owner,
                repo.name,
                number,
                Optional.presentIfNotNull(cursor),
              ),
            )
            .fetch("count viewed files") { it.repository?.pullRequest?.files != null }
            .repository!!
            .pullRequest!!
            .files!!
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          return null
        }
      viewed += files.nodes.orEmpty().count { it?.viewerViewedState == FileViewedState.VIEWED }
      if (!files.pageInfo.hasNextPage) return viewed
      cursor = files.pageInfo.endCursor
    }
    return viewed
  }

  override suspend fun addComment(threadId: String, body: String): TimelineItem.Comment {
    val data =
      apollo.mutation(ThreadAddCommentMutation(threadId, body)).fetch("add a comment") {
        it.addComment?.commentEdge?.node != null
      }
    return data.addComment!!.commentEdge!!.node!!.threadComment.toItem()
  }

  override suspend fun close(threadId: String, kind: ThreadKind, reason: StateReason?) {
    when (kind) {
      ThreadKind.Issue -> {
        val closedReason =
          reason
            ?.let { IssueClosedStateReason.safeValueOf(it.raw) }
            ?.takeIf { it != IssueClosedStateReason.UNKNOWN__ }
        apollo
          .mutation(ThreadCloseIssueMutation(threadId, Optional.presentIfNotNull(closedReason)))
          .fetch("close the issue")
      }
      ThreadKind.PullRequest ->
        apollo.mutation(ThreadClosePullRequestMutation(threadId)).fetch("close the pull request")
      is ThreadKind.Other -> throw IllegalArgumentException("Can't close a ${kind.raw}")
    }
  }

  override suspend fun reopen(threadId: String, kind: ThreadKind) {
    when (kind) {
      ThreadKind.Issue ->
        apollo.mutation(ThreadReopenIssueMutation(threadId)).fetch("reopen the issue")
      ThreadKind.PullRequest ->
        apollo.mutation(ThreadReopenPullRequestMutation(threadId)).fetch("reopen the pull request")
      is ThreadKind.Other -> throw IllegalArgumentException("Can't reopen a ${kind.raw}")
    }
  }

  override suspend fun react(subjectId: String, reaction: Reaction, add: Boolean) {
    val content = ReactionContent.safeValueOf(reaction.raw)
    require(content != ReactionContent.UNKNOWN__) { "GitHub has no ${reaction.raw} reaction" }
    if (add) apollo.mutation(ThreadAddReactionMutation(subjectId, content)).fetch("add a reaction")
    else
      apollo.mutation(ThreadRemoveReactionMutation(subjectId, content)).fetch("remove a reaction")
  }

  override suspend fun merge(pullRequestId: String, method: MergeMethod, expectedHeadOid: String) {
    val githubMethod = PullRequestMergeMethod.safeValueOf(method.raw)
    require(githubMethod != PullRequestMergeMethod.UNKNOWN__) { "GitHub can't ${method.raw}" }
    apollo
      .mutation(ThreadMergeMutation(pullRequestId, githubMethod, expectedHeadOid))
      .fetch("merge the pull request")
  }

  private companion object {
    /** 3000 files, GitHub's own limit for a PR's file list. */
    const val MAX_FILE_PAGES = 30
  }
}

/**
 * Runs a call, and throws a [GitHubGraphQLException] if it failed or [complete] says the data lacks
 * what the caller needs. [action] finishes "Couldn't …", and [subject] names what a `NOT_FOUND` was
 * about. Queries ignore other partial errors, since GitHub reports one for each field it can't fill
 * (a SAML-protected org's data, say) while the rest is fine; mutations don't.
 */
private suspend fun <D : Operation.Data> ApolloCall<D>.fetch(
  action: String,
  subject: String? = null,
  complete: (D) -> Boolean = { true },
): D {
  val response = execute()
  response.exception?.let { throw it.toGitHubException(action) }
  val data = response.data
  val errors = response.errors.orEmpty()
  val strict = response.operation is Mutation<*>
  if (data != null && complete(data) && !(strict && errors.isNotEmpty())) return data
  val error = errors.firstOrNull()
  val type = error?.nonStandardFields?.get("type") as? String
  throw GitHubGraphQLException(
    when {
      type == "NOT_FOUND" || (error == null && data != null) -> "Not found: ${subject ?: action}"
      error != null -> "Couldn't $action: ${error.message}"
      else -> "Couldn't $action: GitHub sent no data"
    },
    type,
  )
}

private fun ApolloException.toGitHubException(action: String): GitHubGraphQLException {
  val message =
    when (this) {
      is ApolloHttpException ->
        when (statusCode) {
          401 -> "GitHub rejected the token (HTTP 401)"
          403 -> "GitHub refused the request (HTTP 403); it may be rate limited"
          else -> "GitHub answered HTTP $statusCode"
        }
      else -> message ?: "network error"
    }
  return GitHubGraphQLException("Couldn't $action: $message", cause = this)
}

// Mapping. Times are ISO 8601 strings; enums keep GitHub's raw value.

private fun instant(value: String) = Instant.parse(value)

private fun ThreadActor.toActor() = Actor(login, avatarUrl, isBot = __typename == "Bot")

private fun ThreadLabel.toLabel() = Label(name, color, description)

private fun List<ThreadReactions>?.toReactions(): List<Reactions> =
  orEmpty()
    .filter { it.reactors.totalCount > 0 }
    .map {
      Reactions(Reaction.of(it.content.rawValue), it.reactors.totalCount, it.viewerHasReacted)
    }

private fun ThreadPageInfo.page(items: List<TimelineItem>) =
  TimelinePage(
    items = items,
    startCursor = startCursor,
    endCursor = endCursor,
    hasPrevious = hasPreviousPage,
    hasNext = hasNextPage,
  )

private fun ThreadIssue.toDetail(repo: RepoRef) =
  ThreadDetail(
    id = id,
    repo = repo,
    number = number,
    kind = ThreadKind.Issue,
    title = title,
    body = body,
    author = author?.threadActor?.toActor(),
    createdAt = instant(createdAt),
    updatedAt = instant(updatedAt),
    editedAt = lastEditedAt?.let(::instant),
    webUrl = url,
    state = ThreadState.of(issueState.rawValue),
    stateReason = stateReason?.let { StateReason.of(it.rawValue) },
    labels = labels?.nodes.orEmpty().mapNotNull { it?.threadLabel?.toLabel() },
    assignees = assignees.nodes.orEmpty().mapNotNull { it?.threadActor?.toActor() },
    milestone = milestone?.title,
    reactions = reactionGroups?.map { it.threadReactions }.toReactions(),
    commentCount = comments.totalCount,
    locked = locked,
    viewer =
      ViewerAbilities(
        canComment = !locked || viewerCanUpdate,
        canReact = viewerCanReact,
        canClose = viewerCanClose,
        canReopen = viewerCanReopen,
      ),
  )

private fun ThreadPullRequest.toDetail(
  repo: RepoRef,
  mergeMethods: List<MergeMethod>,
  defaultMergeMethod: MergeMethod?,
) =
  ThreadDetail(
    id = id,
    repo = repo,
    number = number,
    kind = ThreadKind.PullRequest,
    title = title,
    body = body,
    author = author?.threadActor?.toActor(),
    createdAt = instant(createdAt),
    updatedAt = instant(updatedAt),
    editedAt = lastEditedAt?.let(::instant),
    webUrl = url,
    state = ThreadState.of(pullRequestState.rawValue),
    labels = labels?.nodes.orEmpty().mapNotNull { it?.threadLabel?.toLabel() },
    assignees = assignees.nodes.orEmpty().mapNotNull { it?.threadActor?.toActor() },
    milestone = milestone?.title,
    reactions = reactionGroups?.map { it.threadReactions }.toReactions(),
    commentCount = comments.totalCount,
    locked = locked,
    viewer =
      ViewerAbilities(
        canComment = !locked || viewerCanUpdate,
        canReact = viewerCanReact,
        canClose = viewerCanClose,
        canReopen = viewerCanReopen,
        mergeMethods = mergeMethods,
        defaultMergeMethod = defaultMergeMethod ?: mergeMethods.firstOrNull(),
      ),
    pullRequest =
      PullRequestSummary(
        baseRef = baseRefName,
        headRef = headRefName,
        headOid = headRefOid,
        headOwner = headRepositoryOwner?.login?.takeIf { isCrossRepository },
        isDraft = isDraft,
        additions = additions,
        deletions = deletions,
        changedFiles = changedFiles,
        commitCount = commits.totalCount,
        reviewDecision = reviewDecision?.let { ReviewDecision.of(it.rawValue) },
        mergeable = Mergeable.of(mergeable.rawValue),
        mergeStateRaw = mergeStateStatus.rawValue,
        checks = statusCheckRollup?.toChecks(),
        reviews =
          latestOpinionatedReviews?.nodes.orEmpty().mapNotNull { node ->
            node?.author?.threadActor?.let {
              ReviewerVerdict(it.toActor(), ReviewState.of(node.state.rawValue))
            }
          },
        requestedReviewers =
          reviewRequests?.nodes.orEmpty().mapNotNull { node ->
            node?.requestedReviewer?.run {
              onUser?.login
                ?: onBot?.login
                ?: onMannequin?.login
                ?: onTeam?.combinedSlug
                ?: onEnterpriseTeam?.name
            }
          },
        mergedAt = mergedAt?.let(::instant),
        mergedBy = mergedBy?.threadActor?.toActor(),
        autoMerge = autoMergeRequest != null,
      ),
  )

private fun ThreadPullRequest.StatusCheckRollup.toChecks(): ChecksSummary {
  var passed = 0
  var failed = 0
  var pending = 0
  var skipped = 0
  for (count in contexts.checkRunCountsByState.orEmpty()) {
    when (checkRunState(count.state)) {
      CheckState.Success -> passed += count.count
      CheckState.Failure -> failed += count.count
      CheckState.Pending -> pending += count.count
      else -> skipped += count.count
    }
  }
  for (count in contexts.statusContextCountsByState.orEmpty()) {
    when (statusState(count.state)) {
      CheckState.Success -> passed += count.count
      CheckState.Failure,
      CheckState.Error -> failed += count.count
      CheckState.Pending,
      CheckState.Expected -> pending += count.count
      else -> skipped += count.count
    }
  }
  val checks =
    contexts.nodes.orEmpty().mapNotNull { node ->
      node?.onCheckRun?.let { run ->
        val workflow = run.checkSuite.workflowRun?.workflow?.name
        Check(
          name = if (workflow != null) "$workflow / ${run.name}" else run.name,
          state = checkRunState(run.status, run.conclusion),
          url = run.detailsUrl,
          summary = run.title,
        )
      }
        ?: node?.onStatusContext?.let {
          Check(it.context, statusState(it.state), it.targetUrl, it.description)
        }
    }
  return ChecksSummary(
    state = statusState(state),
    passed = passed,
    failed = failed,
    pending = pending,
    skipped = skipped,
    checks = checks.sortedBy { checkOrder(it.state) },
  )
}

/** Failing first, then running, then the rest; a stable sort keeps GitHub's order within each. */
private fun checkOrder(state: CheckState) =
  when (state) {
    CheckState.Failure,
    CheckState.Error -> 0
    CheckState.Pending,
    CheckState.Expected -> 1
    is CheckState.Other -> 2
    CheckState.Success -> 3
    CheckState.Skipped -> 4
  }

private fun statusState(state: StatusState) = CheckState.of(state.rawValue)

/** A check run by its status and, once it's completed, its conclusion. */
private fun checkRunState(status: CheckStatusState, conclusion: CheckConclusionState?): CheckState =
  when {
    status != CheckStatusState.COMPLETED || conclusion == null -> CheckState.Pending
    else -> conclusionState(conclusion.rawValue)
  }

/** GitHub's count buckets mix statuses and conclusions. */
private fun checkRunState(state: CheckRunState): CheckState =
  when (state) {
    CheckRunState.IN_PROGRESS,
    CheckRunState.PENDING,
    CheckRunState.QUEUED,
    CheckRunState.WAITING -> CheckState.Pending
    // Done, with no conclusion to say how.
    CheckRunState.COMPLETED -> CheckState.Skipped
    else -> conclusionState(state.rawValue)
  }

private fun conclusionState(raw: String): CheckState =
  when (raw) {
    "SUCCESS" -> CheckState.Success
    "FAILURE",
    "TIMED_OUT",
    "STARTUP_FAILURE",
    "ACTION_REQUIRED" -> CheckState.Failure
    "SKIPPED",
    "NEUTRAL",
    "CANCELLED",
    "STALE" -> CheckState.Skipped
    else -> CheckState.Other(raw)
  }

private fun ThreadReviewThread.toReviewThread(): ReviewThread {
  val comments = comments.nodes.orEmpty().mapNotNull { it?.threadReviewComment }
  return ReviewThread(
    id = id,
    reviewId = comments.firstOrNull()?.pullRequestReview?.id,
    path = path,
    line = line,
    originalLine = originalLine,
    isResolved = isResolved,
    isOutdated = isOutdated,
    diffHunk =
      comments.firstOrNull()?.diffHunk?.lines()?.takeLast(DIFF_HUNK_LINES)?.joinToString("\n"),
    comments = comments.map { it.toItem() },
    totalComments = this.comments.totalCount,
  )
}

/** Lines of diff kept above a review thread: enough to see what it's about. */
private const val DIFF_HUNK_LINES = 6

private fun ThreadReviewComment.toItem() =
  TimelineItem.Comment(
    id = id,
    createdAt = instant(createdAt),
    author = author?.threadActor?.toActor(),
    body = body,
    editedAt = lastEditedAt?.let(::instant),
    webUrl = url,
    reactions = reactionGroups?.map { it.threadReactions }.toReactions(),
    viewerCanReact = viewerCanReact,
    minimizedReason = minimizedReason,
    isMinimized = isMinimized,
  )

private fun ThreadComment.toItem() =
  TimelineItem.Comment(
    id = id,
    createdAt = instant(createdAt),
    author = author?.threadActor?.toActor(),
    body = body,
    editedAt = lastEditedAt?.let(::instant),
    webUrl = url,
    reactions = reactionGroups?.map { it.threadReactions }.toReactions(),
    viewerCanReact = viewerCanReact,
    minimizedReason = minimizedReason,
    isMinimized = isMinimized,
  )

private fun ThreadReview.toItem() =
  TimelineItem.Review(
    id = id,
    createdAt = instant(submittedAt ?: createdAt),
    author = author?.threadActor?.toActor(),
    state = ReviewState.of(state.rawValue),
    body = body,
    webUrl = url,
    reactions = reactionGroups?.map { it.threadReactions }.toReactions(),
    viewerCanReact = viewerCanReact,
  )

private fun ThreadCommit.toItem() =
  TimelineItem.Commit(
    id = id,
    createdAt = instant(commit.committedDate),
    oid = commit.oid,
    headline = commit.messageHeadline,
    author = commit.author?.user?.threadActor?.toActor(),
    authorName = commit.author?.name,
    checks = commit.statusCheckRollup?.state?.let(::statusState),
    webUrl = url,
  )

private fun ThreadReferenceFields.toReference(): ThreadReference? {
  onIssue?.let {
    return ThreadReference(
      repo = RepoRef(it.repository.threadRepo.owner.login, it.repository.threadRepo.name),
      number = it.number,
      title = it.title,
      kind = ThreadKind.Issue,
      state = ThreadState.of(it.issueState.rawValue),
      webUrl = it.url,
    )
  }
  onPullRequest?.let {
    return ThreadReference(
      repo = RepoRef(it.repository.threadRepo.owner.login, it.repository.threadRepo.name),
      number = it.number,
      title = it.title,
      kind = ThreadKind.PullRequest,
      state = ThreadState.of(it.pullRequestState.rawValue),
      webUrl = it.url,
    )
  }
  return null
}

private fun ThreadReviewer.name(): String? =
  onUser?.login
    ?: onBot?.login
    ?: onMannequin?.login
    ?: onTeam?.combinedSlug
    ?: onEnterpriseTeam?.name

private fun event(
  id: String,
  createdAt: String,
  kind: EventKind,
  actor: ThreadActor?,
  label: ThreadLabel? = null,
  subject: String? = null,
  previous: String? = null,
  reference: ThreadReferenceFields? = null,
  commitOid: String? = null,
  stateReason: String? = null,
  willClose: Boolean = false,
) =
  TimelineItem.Event(
    id = id,
    createdAt = instant(createdAt),
    kind = kind,
    actor = actor?.toActor(),
    label = label?.toLabel(),
    subject = subject,
    previous = previous,
    reference = reference?.toReference(),
    commitOid = commitOid,
    stateReason = stateReason?.let(StateReason::of),
    willClose = willClose,
  )

// Item types both timelines share. Each `on*` argument is the node's fragment, or null.

private fun ThreadLabeled.toItem() =
  event(id, createdAt, EventKind.Labeled, actor?.threadActor, label = label.threadLabel)

private fun ThreadUnlabeled.toItem() =
  event(id, createdAt, EventKind.Unlabeled, actor?.threadActor, label = label.threadLabel)

private fun ThreadAssigned.toItem() =
  event(
    id,
    createdAt,
    EventKind.Assigned,
    actor?.threadActor,
    subject = assignee?.threadActor?.login,
  )

private fun ThreadUnassigned.toItem() =
  event(
    id,
    createdAt,
    EventKind.Unassigned,
    actor?.threadActor,
    subject = assignee?.threadActor?.login,
  )

private fun ThreadClosed.toItem() =
  event(
    id,
    createdAt,
    EventKind.Closed,
    actor?.threadActor,
    reference = closer?.onPullRequest?.threadReference,
    commitOid = closer?.onCommit?.oid,
    stateReason = stateReason?.rawValue,
  )

private fun ThreadReopened.toItem() =
  event(id, createdAt, EventKind.Reopened, actor?.threadActor, stateReason = stateReason?.rawValue)

private fun ThreadCrossReferenced.toItem() =
  event(
    id,
    createdAt,
    EventKind.CrossReferenced,
    actor?.threadActor,
    reference = source.threadReference,
    willClose = willCloseTarget,
  )

private fun ThreadReferenced.toItem() =
  event(
    id,
    createdAt,
    EventKind.Referenced,
    actor?.threadActor,
    subject = referencedCommit?.messageHeadline,
    commitOid = referencedCommit?.oid,
  )

private fun ThreadRenamed.toItem() =
  event(
    id,
    createdAt,
    EventKind.Renamed,
    actor?.threadActor,
    subject = currentTitle,
    previous = previousTitle,
  )

private fun ThreadMilestoned.toItem() =
  event(id, createdAt, EventKind.Milestoned, actor?.threadActor, subject = milestoneTitle)

private fun ThreadDemilestoned.toItem() =
  event(id, createdAt, EventKind.Demilestoned, actor?.threadActor, subject = milestoneTitle)

private fun ThreadMarkedAsDuplicate.toItem() =
  event(
    id,
    createdAt,
    EventKind.MarkedAsDuplicate,
    actor?.threadActor,
    reference = canonical?.threadReference,
  )

private fun ThreadLocked.toItem() =
  event(id, createdAt, EventKind.Locked, actor?.threadActor, subject = lockReason?.rawValue)

private fun ThreadUnlocked.toItem() = event(id, createdAt, EventKind.Unlocked, actor?.threadActor)

// PR-only item types.

private fun ThreadMerged.toItem() =
  event(
    id,
    createdAt,
    EventKind.Merged,
    actor?.threadActor,
    subject = mergeRefName,
    commitOid = mergeCommit?.oid,
  )

private fun ThreadReviewRequested.toItem() =
  event(
    id,
    createdAt,
    EventKind.ReviewRequested,
    actor?.threadActor,
    subject = requestedReviewer?.threadReviewer?.name(),
  )

private fun ThreadReviewRequestRemoved.toItem() =
  event(
    id,
    createdAt,
    EventKind.ReviewRequestRemoved,
    actor?.threadActor,
    subject = requestedReviewer?.threadReviewer?.name(),
  )

private fun ThreadReviewDismissed.toItem() =
  event(
    id,
    createdAt,
    EventKind.ReviewDismissed,
    actor?.threadActor,
    subject = review?.author?.login,
  )

private fun ThreadHeadRefForcePushed.toItem() =
  event(
    id,
    createdAt,
    EventKind.HeadRefForcePushed,
    actor?.threadActor,
    subject = ref?.name,
    previous = beforeCommit?.oid,
    commitOid = afterCommit?.oid,
  )

private fun ThreadHeadRefDeleted.toItem() =
  event(id, createdAt, EventKind.HeadRefDeleted, actor?.threadActor, subject = headRefName)

private fun ThreadReadyForReview.toItem() =
  event(id, createdAt, EventKind.ReadyForReview, actor?.threadActor)

private fun ThreadConvertToDraft.toItem() =
  event(id, createdAt, EventKind.ConvertToDraft, actor?.threadActor)

private fun ThreadBaseRefChanged.toItem() =
  event(
    id,
    createdAt,
    EventKind.BaseRefChanged,
    actor?.threadActor,
    subject = currentRefName,
    previous = previousRefName,
  )

private fun ThreadAutoMergeEnabled.toItem() =
  event(id, createdAt, EventKind.AutoMergeEnabled, actor?.threadActor)

private fun ThreadAutoMergeDisabled.toItem() =
  event(id, createdAt, EventKind.AutoMergeDisabled, actor?.threadActor)

private fun ThreadIssueTimeline.toPage(): TimelinePage =
  pageInfo.threadPageInfo.page(
    nodes.orEmpty().mapNotNull { node ->
      node ?: return@mapNotNull null
      node.threadComment?.toItem()
        ?: node.threadLabeled?.toItem()
        ?: node.threadUnlabeled?.toItem()
        ?: node.threadAssigned?.toItem()
        ?: node.threadUnassigned?.toItem()
        ?: node.threadClosed?.toItem()
        ?: node.threadReopened?.toItem()
        ?: node.threadCrossReferenced?.toItem()
        ?: node.threadReferenced?.toItem()
        ?: node.threadRenamed?.toItem()
        ?: node.threadMilestoned?.toItem()
        ?: node.threadDemilestoned?.toItem()
        ?: node.threadMarkedAsDuplicate?.toItem()
        ?: node.threadLocked?.toItem()
        ?: node.threadUnlocked?.toItem()
    },
  )

private fun ThreadPullRequestTimeline.toPage(): TimelinePage =
  pageInfo.threadPageInfo.page(
    nodes.orEmpty().mapNotNull { node ->
      node ?: return@mapNotNull null
      node.threadComment?.toItem()
        ?: node.threadReview?.toItem()
        ?: node.threadCommit?.toItem()
        ?: node.threadLabeled?.toItem()
        ?: node.threadUnlabeled?.toItem()
        ?: node.threadAssigned?.toItem()
        ?: node.threadUnassigned?.toItem()
        ?: node.threadClosed?.toItem()
        ?: node.threadReopened?.toItem()
        ?: node.threadMerged?.toItem()
        ?: node.threadCrossReferenced?.toItem()
        ?: node.threadReferenced?.toItem()
        ?: node.threadRenamed?.toItem()
        ?: node.threadReviewRequested?.toItem()
        ?: node.threadReviewRequestRemoved?.toItem()
        ?: node.threadReviewDismissed?.toItem()
        ?: node.threadHeadRefForcePushed?.toItem()
        ?: node.threadHeadRefDeleted?.toItem()
        ?: node.threadReadyForReview?.toItem()
        ?: node.threadConvertToDraft?.toItem()
        ?: node.threadMilestoned?.toItem()
        ?: node.threadDemilestoned?.toItem()
        ?: node.threadMarkedAsDuplicate?.toItem()
        ?: node.threadLocked?.toItem()
        ?: node.threadUnlocked?.toItem()
        ?: node.threadBaseRefChanged?.toItem()
        ?: node.threadAutoMergeEnabled?.toItem()
        ?: node.threadAutoMergeDisabled?.toItem()
    },
  )
