package dev.sargunv.tongs.forge.api

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * One thing that happened on the forge, as its activity feed reports it: a push, a comment, a star.
 * Kinds and actions are normalized, each with the forge's raw value beside it, and fields the forge
 * doesn't report are null rather than guessed.
 */
data class FeedEvent(
  /** The forge's event ID. */
  val id: String,
  val kind: FeedEventKind,
  /** The forge's own name for the kind: GitHub's `PushEvent`, Forgejo's `commit_repo`. */
  val kindRaw: String,
  val action: FeedAction?,
  /** The forge's action, raw: `opened`, `merged`, `started`. */
  val actionRaw: String?,
  val actor: FeedActor,
  val repo: RepoRef,
  val createdAt: Instant,
  /** Whether the repo was public when it happened, if the forge says. */
  val isPublic: Boolean? = null,
  /** The issue or PR it happened on, for issue, PR, comment, and review events. */
  val subject: FeedSubject? = null,
  /** A push's, create's, or delete's ref, short: `main`, `v1.2.0`. */
  val ref: String? = null,
  val refType: RefType? = null,
  /** A push's previous and new head. The previous head is all zeros for a new branch. */
  val before: String? = null,
  val head: String? = null,
  /**
   * A push's commits, newest first, if the forge includes them. GitHub stopped in October 2025;
   * [FeedHydrator] fills them in.
   */
  val commits: List<FeedCommit>? = null,
  /** How many commits a push had, if known; [commits] may hold only the newest. */
  val commitCount: Int? = null,
  /** A release's name, or its tag if it has none. */
  val title: String? = null,
  /** The start of a comment's or review's body, or a release's notes. */
  val excerpt: String? = null,
  /** A review's verdict, raw: `approved`, `changes_requested`, `commented` on GitHub. */
  val reviewState: String? = null,
  /** Whom or what the event acted on: the member added, the fork created (`owner/name`). */
  val target: String? = null,
  /** Where the event opens in a browser, if it has its own page. */
  val webUrl: String? = null,
)

/** Who did it. */
data class FeedActor(val login: String, val avatarUrl: String?) {
  /** Apps and bots, which GitHub names `name[bot]`. */
  val isBot: Boolean
    get() = login.endsWith("[bot]")
}

/** The issue or PR an event is on, as the event describes it. */
data class FeedSubject(
  val type: SubjectType,
  val number: Int,
  /** The title, if the event carries it; GitHub's PR events no longer do. */
  val title: String? = null,
  /** The state as of the event, raw: `open`, `closed`. Hydration says what it is now. */
  val state: String? = null,
)

data class FeedCommit(val sha: String, val title: String)

/** What a feed event is about. Unknown kinds are [Other], with the raw kind on the event. */
enum class FeedEventKind {
  Push,
  /** A branch, tag, or repository was created; see [FeedEvent.refType]. */
  RefCreated,
  RefDeleted,
  Issue,
  PullRequest,
  /** A comment on an issue or a PR's conversation. */
  Comment,
  /** A PR review was submitted. */
  Review,
  /** A comment on a PR's diff. */
  ReviewComment,
  CommitComment,
  Release,
  Star,
  Fork,
  /** Someone was added to the repo; [FeedEvent.target] says who. */
  Member,
  /** The repo was made public. */
  Publicized,
  Wiki,
  Discussion,
  Other,
}

/** What an issue, PR, or other event did. Unknown actions are [Other], raw on the event. */
enum class FeedAction(val raw: String) {
  Opened("opened"),
  Closed("closed"),
  Reopened("reopened"),
  Merged("merged"),
  Labeled("labeled"),
  Unlabeled("unlabeled"),
  Assigned("assigned"),
  Unassigned("unassigned"),
  Edited("edited"),
  Created("created"),
  Published("published"),
  Added("added"),
  Other("other");

  companion object {
    /** GitHub's action names. Other forges map theirs. */
    fun of(raw: String?): FeedAction? = raw?.let { r ->
      entries.firstOrNull { it.raw == r && it != Other } ?: Other
    }
  }
}

enum class RefType {
  Branch,
  Tag,
  Repository,
}

/** The result of one feed poll. */
sealed interface FeedPoll {
  /** How long the forge asks clients to wait before polling again, if it says. */
  val pollInterval: Duration?

  /** Requests that counted against the rate limit; a GitHub 304 doesn't. */
  val requests: Int

  /** Nothing changed since [FeedSource.poll]'s cursor. */
  data class NotModified(override val pollInterval: Duration?, override val requests: Int = 0) :
    FeedPoll

  data class Changed(
    /**
     * Events new since the cursor, and maybe some already seen; each source's newest first. They
     * don't add up to the whole feed: the forge keeps only so many, so store them to go further
     * back.
     */
    val events: List<FeedEvent>,
    /** Pass to the next poll to ask only for changes. Opaque. */
    val cursor: String?,
    override val pollInterval: Duration?,
    override val requests: Int,
    /** Organizations whose activity the forge left out until the token is authorized for SSO. */
    val ssoRequired: List<SsoAuthorization> = emptyList(),
  ) : FeedPoll
}

/**
 * An account's activity feed: what happened in the repos it watches, by the people it follows, in
 * its organizations, and by itself. GitHub has no API for its "For you" feed, so its adapter merges
 * those sources; Forgejo has one, `/users/{me}/activities/feeds`. Failures throw a [ForgeException]
 * where the cause is known.
 */
interface FeedSource {
  /** Fetches what's new since [cursor], or reports nothing has changed. */
  suspend fun poll(cursor: String?): FeedPoll
}

/** An issue or PR, by repo and number. */
data class SubjectRef(val repo: RepoRef, val number: Int)

/** A push to hydrate: the commits from [before] to [head] on [ref]. */
data class PushRef(val repo: RepoRef, val ref: String?, val before: String?, val head: String)

/** An issue's or PR's current state. Fields the forge didn't say are null. */
data class SubjectState(
  val subject: SubjectRef,
  val type: SubjectType? = null,
  val title: String? = null,
  /** Raw: `OPEN`, `CLOSED`, `MERGED` on GitHub. */
  val state: String? = null,
  /** Why an issue was closed, raw: `COMPLETED`, `NOT_PLANNED`. */
  val stateReason: String? = null,
  val isDraft: Boolean? = null,
)

data class PushCommits(
  val push: PushRef,
  /** Newest first; may be only the newest few of [count]. Null if the forge couldn't say. */
  val commits: List<FeedCommit>?,
  val count: Int?,
)

data class FeedHydration(
  val subjects: List<SubjectState>,
  val pushes: List<PushCommits>,
  /** REST requests it made. */
  val requests: Int = 0,
  /** GraphQL points it cost, if the forge says. */
  val points: Int? = null,
)

/**
 * Adds what feed events leave out: the current state and title of issues and PRs, and the commits
 * of pushes. The feed calls it with what it hasn't hydrated yet and stores what it returns. For
 * GitHub, GraphQL batches subject state and REST compare supplies push commits.
 */
interface FeedHydrator {
  /**
   * Hydrates [subjects] and [pushes]. Those the forge can't describe (deleted, or behind SSO) come
   * back with nothing set, so they aren't asked about again until they change.
   */
  suspend fun hydrate(subjects: List<SubjectRef>, pushes: List<PushRef>): FeedHydration
}
