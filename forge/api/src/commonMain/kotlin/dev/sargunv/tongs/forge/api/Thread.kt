package dev.sargunv.tongs.forge.api

import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * An issue or PR conversation: what the thread screen shows above the timeline. The data layer
 * stores it as JSON, so every type here is serializable, and open enums serialize as their raw
 * value. Fields a forge doesn't report are null, not zero.
 */
@Serializable
data class ThreadDetail(
  /** The forge's ID for the issue or PR, which mutations take. */
  val id: String,
  val repo: RepoRef,
  val number: Int,
  val kind: ThreadKind,
  val title: String,
  /** Markdown, as written. */
  val body: String,
  val author: Actor?,
  val createdAt: Instant,
  val updatedAt: Instant,
  /** When the body was last edited, if it was. */
  val editedAt: Instant? = null,
  val webUrl: String,
  val state: ThreadState,
  /** Why it was closed or reopened, where the forge says. */
  val stateReason: StateReason? = null,
  val labels: List<Label> = emptyList(),
  val assignees: List<Actor> = emptyList(),
  val milestone: String? = null,
  val reactions: List<Reactions> = emptyList(),
  val commentCount: Int? = null,
  val locked: Boolean = false,
  val viewer: ViewerAbilities = ViewerAbilities(),
  /** Set for pull requests. */
  val pullRequest: PullRequestSummary? = null,
)

/** What the signed-in account may do to a thread. */
@Serializable
data class ViewerAbilities(
  val canComment: Boolean = false,
  val canReact: Boolean = false,
  val canClose: Boolean = false,
  val canReopen: Boolean = false,
  /** Merge methods the repo allows, when the account can merge at all. */
  val mergeMethods: List<MergeMethod> = emptyList(),
  val defaultMergeMethod: MergeMethod? = null,
)

@Serializable
data class PullRequestSummary(
  val baseRef: String,
  val headRef: String,
  val headOid: String,
  /** The head repo's owner when it isn't the base repo's, as for forks. */
  val headOwner: String? = null,
  val isDraft: Boolean = false,
  val additions: Int,
  val deletions: Int,
  val changedFiles: Int,
  val commitCount: Int? = null,
  /** Files the account marked viewed on the forge, if it tracks that. */
  val viewedFiles: Int? = null,
  val reviewDecision: ReviewDecision? = null,
  val mergeable: Mergeable? = null,
  /** The forge's raw merge state, like GitHub's `mergeStateStatus`: `CLEAN`, `BLOCKED`, … */
  val mergeStateRaw: String? = null,
  val checks: ChecksSummary? = null,
  /** Each reviewer's latest review that approved or requested changes. */
  val reviews: List<ReviewerVerdict> = emptyList(),
  /** Users and teams asked to review who haven't yet. */
  val requestedReviewers: List<String> = emptyList(),
  val mergedAt: Instant? = null,
  val mergedBy: Actor? = null,
  val autoMerge: Boolean = false,
)

@Serializable data class ReviewerVerdict(val reviewer: Actor, val state: ReviewState)

/** The checks on a PR's head commit, rolled up. */
@Serializable
data class ChecksSummary(
  val state: CheckState,
  val passed: Int,
  val failed: Int,
  val pending: Int,
  val skipped: Int,
  /** Individual checks, failing and running ones first. May be fewer than the counts. */
  val checks: List<Check> = emptyList(),
) {
  val total: Int
    get() = passed + failed + pending + skipped
}

@Serializable
data class Check(
  val name: String,
  val state: CheckState,
  val url: String? = null,
  val summary: String? = null,
)

@Serializable
data class Actor(
  val login: String,
  val avatarUrl: String? = null,
  /** True for apps and bots, which the timeline can show more quietly. */
  val isBot: Boolean = false,
)

@Serializable
data class Label(
  val name: String,
  /** `rrggbb`, as GitHub reports it, if the forge colors labels. */
  val color: String? = null,
  val description: String? = null,
)

/** One emoji on a comment or body, with how many reacted and whether the account did. */
@Serializable
data class Reactions(val reaction: Reaction, val count: Int, val viewerReacted: Boolean)

/**
 * One timeline item. Comments, reviews, and commits carry content; everything else is an [Event],
 * which the UI renders as one line.
 */
@Serializable
sealed interface TimelineItem {
  /** The forge's ID for the item. */
  val id: String
  val createdAt: Instant

  @Serializable
  data class Comment(
    override val id: String,
    override val createdAt: Instant,
    val author: Actor?,
    val body: String,
    val editedAt: Instant? = null,
    val webUrl: String? = null,
    val reactions: List<Reactions> = emptyList(),
    val viewerCanReact: Boolean = false,
    /** Hidden by a moderator, with GitHub's reason, like `OUTDATED` or `SPAM`. */
    val minimizedReason: String? = null,
    val isMinimized: Boolean = false,
  ) : TimelineItem

  @Serializable
  data class Review(
    override val id: String,
    override val createdAt: Instant,
    val author: Actor?,
    val state: ReviewState,
    val body: String,
    val webUrl: String? = null,
    val reactions: List<Reactions> = emptyList(),
    val viewerCanReact: Boolean = false,
  ) : TimelineItem

  @Serializable
  data class Commit(
    override val id: String,
    override val createdAt: Instant,
    val oid: String,
    val headline: String,
    /** The git author's forge account, or just their name if it has none. */
    val author: Actor?,
    val authorName: String? = null,
    val checks: CheckState? = null,
    val webUrl: String? = null,
  ) : TimelineItem

  /**
   * Something that happened: a label, a state change, a cross-reference. Which fields are set
   * depends on [kind]; see each [EventKind].
   */
  @Serializable
  data class Event(
    override val id: String,
    override val createdAt: Instant,
    val kind: EventKind,
    val actor: Actor?,
    val label: Label? = null,
    /** The user, team, milestone, or ref the event names: an assignee, a reviewer, a branch. */
    val subject: String? = null,
    /** For renames and base changes: the old value; [subject] is the new one. */
    val previous: String? = null,
    /** For cross-references and duplicates: the other thread. */
    val reference: ThreadReference? = null,
    /** For merges, references, and force pushes: the commit. */
    val commitOid: String? = null,
    val stateReason: StateReason? = null,
    /** For cross-references: whether merging the source closes this thread. */
    val willClose: Boolean = false,
  ) : TimelineItem
}

/** Another issue or PR, as a timeline event mentions it. */
@Serializable
data class ThreadReference(
  val repo: RepoRef,
  val number: Int,
  val title: String,
  val kind: ThreadKind,
  val state: ThreadState,
  val webUrl: String? = null,
)

/** A review comment thread on a PR's diff, shown under the review that started it. */
@Serializable
data class ReviewThread(
  val id: String,
  /** The review whose comment started the thread. */
  val reviewId: String?,
  val path: String,
  /** The line on the current diff, or null when the thread is outdated. */
  val line: Int?,
  val originalLine: Int? = null,
  val isResolved: Boolean,
  val isOutdated: Boolean,
  /** The tail of the diff hunk the thread is on, for context. */
  val diffHunk: String? = null,
  val comments: List<TimelineItem.Comment>,
  /** Comments on the forge, which may be more than [comments]. */
  val totalComments: Int = comments.size,
)

/** Part of a thread's timeline, oldest first, with cursors to page from. */
data class TimelinePage(
  val items: List<TimelineItem>,
  /** Before the first item, to page backward from. Null when it's the start. */
  val startCursor: String?,
  /** After the last item, to page forward from. */
  val endCursor: String?,
  val hasPrevious: Boolean,
  val hasNext: Boolean,
)

/**
 * A thread as one load returns it: the detail, the start and the end of its timeline, and for PRs
 * its review threads. [head] and [tail] may overlap, when the timeline is short; items between them
 * load with [ThreadSource.loadTimeline] from [head]'s end cursor.
 */
data class ThreadLoad(
  val detail: ThreadDetail,
  val head: TimelinePage,
  val tail: TimelinePage,
  /** Timeline items on the forge, counting only kinds the adapter asks for. */
  val timelineCount: Int,
  val reviewThreads: List<ReviewThread> = emptyList(),
)

/**
 * An account's view of issue and PR conversations: reading them, and the small writes the thread
 * screen makes. Mutations take the forge's IDs from [ThreadDetail.id] and [TimelineItem.id].
 */
interface ThreadSource {
  /** Loads an issue or PR by number, whichever it is. */
  suspend fun load(repo: RepoRef, number: Int, headCount: Int = 15, tailCount: Int = 40): ThreadLoad

  /** Loads up to [count] timeline items after [cursor]. */
  suspend fun loadTimeline(
    repo: RepoRef,
    number: Int,
    cursor: String,
    count: Int = 50,
  ): TimelinePage

  /** Adds a comment to the issue or PR [threadId], and returns it as the forge stored it. */
  suspend fun addComment(threadId: String, body: String): TimelineItem.Comment

  suspend fun close(threadId: String, kind: ThreadKind, reason: StateReason? = null)

  suspend fun reopen(threadId: String, kind: ThreadKind)

  /** Adds or removes the account's [reaction] on a body, comment, or review. */
  suspend fun react(subjectId: String, reaction: Reaction, add: Boolean)

  /** Merges a PR, if its head is still [expectedHeadOid]. */
  suspend fun merge(pullRequestId: String, method: MergeMethod, expectedHeadOid: String)
}

// Open enums. Each keeps the forge's raw value, unknown values become `Other`, and they serialize
// as the raw value.

@Serializable(ThreadKind.Serializer::class)
sealed class ThreadKind(val raw: String) {
  data object Issue : ThreadKind("Issue")

  data object PullRequest : ThreadKind("PullRequest")

  class Other(raw: String) : ThreadKind(raw) {
    override fun equals(other: Any?) = other is Other && other.raw == raw

    override fun hashCode() = raw.hashCode()

    override fun toString() = "Other($raw)"
  }

  companion object {
    fun of(raw: String): ThreadKind =
      when (raw) {
        Issue.raw -> Issue
        PullRequest.raw -> PullRequest
        else -> Other(raw)
      }
  }

  internal object Serializer : OpenEnumSerializer<ThreadKind>("ThreadKind", ::of, { it.raw })
}

/** Open, closed, or merged, by the forge's raw state: GitHub's `OPEN`, `CLOSED`, `MERGED`. */
@Serializable(ThreadState.Serializer::class)
sealed class ThreadState(val raw: String) {
  data object Open : ThreadState("OPEN")

  data object Closed : ThreadState("CLOSED")

  data object Merged : ThreadState("MERGED")

  class Other(raw: String) : ThreadState(raw) {
    override fun equals(other: Any?) = other is Other && other.raw == raw

    override fun hashCode() = raw.hashCode()

    override fun toString() = "Other($raw)"
  }

  companion object {
    private val known by lazy { listOf(Open, Closed, Merged) }

    fun of(raw: String): ThreadState = known.firstOrNull { it.raw == raw } ?: Other(raw)
  }

  internal object Serializer : OpenEnumSerializer<ThreadState>("ThreadState", ::of, { it.raw })
}

/** Why an issue was closed or reopened: GitHub's `IssueStateReason`. */
@Serializable(StateReason.Serializer::class)
sealed class StateReason(val raw: String) {
  data object Completed : StateReason("COMPLETED")

  data object NotPlanned : StateReason("NOT_PLANNED")

  data object Duplicate : StateReason("DUPLICATE")

  data object Reopened : StateReason("REOPENED")

  class Other(raw: String) : StateReason(raw) {
    override fun equals(other: Any?) = other is Other && other.raw == raw

    override fun hashCode() = raw.hashCode()

    override fun toString() = "Other($raw)"
  }

  companion object {
    private val known by lazy { listOf(Completed, NotPlanned, Duplicate, Reopened) }

    fun of(raw: String): StateReason = known.firstOrNull { it.raw == raw } ?: Other(raw)
  }

  internal object Serializer : OpenEnumSerializer<StateReason>("StateReason", ::of, { it.raw })
}

/** A review's verdict: GitHub's `PullRequestReviewState`. */
@Serializable(ReviewState.Serializer::class)
sealed class ReviewState(val raw: String) {
  data object Approved : ReviewState("APPROVED")

  data object ChangesRequested : ReviewState("CHANGES_REQUESTED")

  data object Commented : ReviewState("COMMENTED")

  data object Dismissed : ReviewState("DISMISSED")

  data object Pending : ReviewState("PENDING")

  class Other(raw: String) : ReviewState(raw) {
    override fun equals(other: Any?) = other is Other && other.raw == raw

    override fun hashCode() = raw.hashCode()

    override fun toString() = "Other($raw)"
  }

  companion object {
    private val known by lazy { listOf(Approved, ChangesRequested, Commented, Dismissed, Pending) }

    fun of(raw: String): ReviewState = known.firstOrNull { it.raw == raw } ?: Other(raw)
  }

  internal object Serializer : OpenEnumSerializer<ReviewState>("ReviewState", ::of, { it.raw })
}

/** Where a PR's required reviews stand: GitHub's `PullRequestReviewDecision`. */
@Serializable(ReviewDecision.Serializer::class)
sealed class ReviewDecision(val raw: String) {
  data object Approved : ReviewDecision("APPROVED")

  data object ChangesRequested : ReviewDecision("CHANGES_REQUESTED")

  data object ReviewRequired : ReviewDecision("REVIEW_REQUIRED")

  class Other(raw: String) : ReviewDecision(raw) {
    override fun equals(other: Any?) = other is Other && other.raw == raw

    override fun hashCode() = raw.hashCode()

    override fun toString() = "Other($raw)"
  }

  companion object {
    private val known by lazy { listOf(Approved, ChangesRequested, ReviewRequired) }

    fun of(raw: String): ReviewDecision = known.firstOrNull { it.raw == raw } ?: Other(raw)
  }

  internal object Serializer :
    OpenEnumSerializer<ReviewDecision>("ReviewDecision", ::of, { it.raw })
}

/** Whether a PR merges cleanly: GitHub's `MergeableState`. */
@Serializable(Mergeable.Serializer::class)
sealed class Mergeable(val raw: String) {
  data object Yes : Mergeable("MERGEABLE")

  data object Conflicting : Mergeable("CONFLICTING")

  data object Unknown : Mergeable("UNKNOWN")

  class Other(raw: String) : Mergeable(raw) {
    override fun equals(other: Any?) = other is Other && other.raw == raw

    override fun hashCode() = raw.hashCode()

    override fun toString() = "Other($raw)"
  }

  companion object {
    private val known by lazy { listOf(Yes, Conflicting, Unknown) }

    fun of(raw: String): Mergeable = known.firstOrNull { it.raw == raw } ?: Other(raw)
  }

  internal object Serializer : OpenEnumSerializer<Mergeable>("Mergeable", ::of, { it.raw })
}

/** A check or a rollup of them. Raw values are GitHub's `StatusState`. */
@Serializable(CheckState.Serializer::class)
sealed class CheckState(val raw: String) {
  data object Success : CheckState("SUCCESS")

  data object Failure : CheckState("FAILURE")

  data object Pending : CheckState("PENDING")

  data object Error : CheckState("ERROR")

  data object Expected : CheckState("EXPECTED")

  /** Skipped, cancelled, or neutral: done, and neither passed nor failed. */
  data object Skipped : CheckState("SKIPPED")

  class Other(raw: String) : CheckState(raw) {
    override fun equals(other: Any?) = other is Other && other.raw == raw

    override fun hashCode() = raw.hashCode()

    override fun toString() = "Other($raw)"
  }

  companion object {
    private val known by lazy { listOf(Success, Failure, Pending, Error, Expected, Skipped) }

    fun of(raw: String): CheckState = known.firstOrNull { it.raw == raw } ?: Other(raw)
  }

  internal object Serializer : OpenEnumSerializer<CheckState>("CheckState", ::of, { it.raw })
}

@Serializable(MergeMethod.Serializer::class)
sealed class MergeMethod(val raw: String) {
  data object Merge : MergeMethod("MERGE")

  data object Squash : MergeMethod("SQUASH")

  data object Rebase : MergeMethod("REBASE")

  class Other(raw: String) : MergeMethod(raw) {
    override fun equals(other: Any?) = other is Other && other.raw == raw

    override fun hashCode() = raw.hashCode()

    override fun toString() = "Other($raw)"
  }

  companion object {
    private val known by lazy { listOf(Merge, Squash, Rebase) }

    fun of(raw: String): MergeMethod = known.firstOrNull { it.raw == raw } ?: Other(raw)
  }

  internal object Serializer : OpenEnumSerializer<MergeMethod>("MergeMethod", ::of, { it.raw })
}

/**
 * An emoji reaction. GitHub's set is fixed, and these are its raw `ReactionContent` values; a forge
 * with custom emoji maps the rest to [Other].
 */
@Serializable(Reaction.Serializer::class)
sealed class Reaction(val raw: String, val emoji: String) {
  data object ThumbsUp : Reaction("THUMBS_UP", "👍")

  data object ThumbsDown : Reaction("THUMBS_DOWN", "👎")

  data object Laugh : Reaction("LAUGH", "😄")

  data object Hooray : Reaction("HOORAY", "🎉")

  data object Confused : Reaction("CONFUSED", "😕")

  data object Heart : Reaction("HEART", "❤️")

  data object Rocket : Reaction("ROCKET", "🚀")

  data object Eyes : Reaction("EYES", "👀")

  class Other(raw: String) : Reaction(raw, raw) {
    override fun equals(other: Any?) = other is Other && other.raw == raw

    override fun hashCode() = raw.hashCode()

    override fun toString() = "Other($raw)"
  }

  companion object {
    /** GitHub's set, in the order its picker shows them. */
    val GitHub by lazy {
      listOf(ThumbsUp, ThumbsDown, Laugh, Hooray, Confused, Heart, Rocket, Eyes)
    }

    fun of(raw: String): Reaction = GitHub.firstOrNull { it.raw == raw } ?: Other(raw)
  }

  internal object Serializer : OpenEnumSerializer<Reaction>("Reaction", ::of, { it.raw })
}

/** What a timeline [TimelineItem.Event] records. Raw values are GitHub's GraphQL type names. */
@Serializable(EventKind.Serializer::class)
sealed class EventKind(val raw: String) {
  /** [TimelineItem.Event.label] was added. */
  data object Labeled : EventKind("LabeledEvent")

  data object Unlabeled : EventKind("UnlabeledEvent")

  /** [TimelineItem.Event.subject] was assigned. */
  data object Assigned : EventKind("AssignedEvent")

  data object Unassigned : EventKind("UnassignedEvent")

  /** With [TimelineItem.Event.stateReason], and the closing commit or PR if there was one. */
  data object Closed : EventKind("ClosedEvent")

  data object Reopened : EventKind("ReopenedEvent")

  /** [TimelineItem.Event.commitOid] into [TimelineItem.Event.subject], the base branch. */
  data object Merged : EventKind("MergedEvent")

  /** [TimelineItem.Event.reference] mentioned this thread. */
  data object CrossReferenced : EventKind("CrossReferencedEvent")

  /** A commit, [TimelineItem.Event.commitOid], mentioned this thread. */
  data object Referenced : EventKind("ReferencedEvent")

  /** Retitled from [TimelineItem.Event.previous] to [TimelineItem.Event.subject]. */
  data object Renamed : EventKind("RenamedTitleEvent")

  data object ReviewRequested : EventKind("ReviewRequestedEvent")

  data object ReviewRequestRemoved : EventKind("ReviewRequestRemovedEvent")

  /** [TimelineItem.Event.subject]'s review was dismissed. */
  data object ReviewDismissed : EventKind("ReviewDismissedEvent")

  data object HeadRefForcePushed : EventKind("HeadRefForcePushedEvent")

  data object HeadRefDeleted : EventKind("HeadRefDeletedEvent")

  data object ReadyForReview : EventKind("ReadyForReviewEvent")

  data object ConvertToDraft : EventKind("ConvertToDraftEvent")

  data object Milestoned : EventKind("MilestonedEvent")

  data object Demilestoned : EventKind("DemilestonedEvent")

  data object MarkedAsDuplicate : EventKind("MarkedAsDuplicateEvent")

  data object Locked : EventKind("LockedEvent")

  data object Unlocked : EventKind("UnlockedEvent")

  data object BaseRefChanged : EventKind("BaseRefChangedEvent")

  data object AutoMergeEnabled : EventKind("AutoMergeEnabledEvent")

  data object AutoMergeDisabled : EventKind("AutoMergeDisabledEvent")

  class Other(raw: String) : EventKind(raw) {
    override fun equals(other: Any?) = other is Other && other.raw == raw

    override fun hashCode() = raw.hashCode()

    override fun toString() = "Other($raw)"
  }

  companion object {
    private val known by lazy {
      listOf(
        Labeled,
        Unlabeled,
        Assigned,
        Unassigned,
        Closed,
        Reopened,
        Merged,
        CrossReferenced,
        Referenced,
        Renamed,
        ReviewRequested,
        ReviewRequestRemoved,
        ReviewDismissed,
        HeadRefForcePushed,
        HeadRefDeleted,
        ReadyForReview,
        ConvertToDraft,
        Milestoned,
        Demilestoned,
        MarkedAsDuplicate,
        Locked,
        Unlocked,
        BaseRefChanged,
        AutoMergeEnabled,
        AutoMergeDisabled,
      )
    }

    fun of(raw: String): EventKind = known.firstOrNull { it.raw == raw } ?: Other(raw)
  }

  internal object Serializer : OpenEnumSerializer<EventKind>("EventKind", ::of, { it.raw })
}

/** Serializes an open enum as its raw string, so unknown values round-trip. */
internal open class OpenEnumSerializer<T : Any>(
  name: String,
  private val of: (String) -> T,
  private val raw: (T) -> String,
) : KSerializer<T> {
  override val descriptor =
    PrimitiveSerialDescriptor("dev.sargunv.tongs.forge.api.$name", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(raw(value))

  override fun deserialize(decoder: Decoder): T = of(decoder.decodeString())
}
