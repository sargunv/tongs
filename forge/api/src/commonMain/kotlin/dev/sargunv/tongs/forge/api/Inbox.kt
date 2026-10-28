package dev.sargunv.tongs.forge.api

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * One notification thread, as the forge reports it. GitHub's semantics are kept as is: [reason]
 * says why you're subscribed and is sticky, and [unread] and done mean what the API means.
 */
data class InboxThread(
  /** The forge's thread ID. */
  val id: String,
  val repo: RepoRef,
  val subject: Subject,
  val reason: NotificationReason,
  val unread: Boolean,
  val updatedAt: Instant,
  val lastReadAt: Instant?,
)

data class Subject(
  val title: String,
  val type: SubjectType,
  /** The issue or PR number, for subjects that have one. */
  val number: Int?,
  /** Where the subject opens in a browser, if the forge says or it can be derived. */
  val webUrl: String?,
)

/** What a notification is about. Open: unknown types keep their raw value. */
sealed class SubjectType(val raw: String) {
  data object Issue : SubjectType("Issue")

  data object PullRequest : SubjectType("PullRequest")

  data object Discussion : SubjectType("Discussion")

  data object Release : SubjectType("Release")

  data object Commit : SubjectType("Commit")

  data object CheckSuite : SubjectType("CheckSuite")

  class Other(raw: String) : SubjectType(raw) {
    override fun equals(other: Any?) = other is Other && other.raw == raw

    override fun hashCode() = raw.hashCode()

    override fun toString() = "Other($raw)"
  }

  companion object {
    private val known by lazy {
      listOf(Issue, PullRequest, Discussion, Release, Commit, CheckSuite)
    }

    fun of(raw: String): SubjectType = known.firstOrNull { it.raw == raw } ?: Other(raw)
  }
}

/** Why you're subscribed to a thread, by GitHub's reasons. Open, like [SubjectType]. */
sealed class NotificationReason(val raw: String) {
  data object ApprovalRequested : NotificationReason("approval_requested")

  data object Assign : NotificationReason("assign")

  data object Author : NotificationReason("author")

  data object CiActivity : NotificationReason("ci_activity")

  data object Comment : NotificationReason("comment")

  data object Invitation : NotificationReason("invitation")

  data object Manual : NotificationReason("manual")

  data object MemberFeatureRequested : NotificationReason("member_feature_requested")

  data object Mention : NotificationReason("mention")

  data object ReviewRequested : NotificationReason("review_requested")

  data object SecurityAdvisoryCredit : NotificationReason("security_advisory_credit")

  data object SecurityAlert : NotificationReason("security_alert")

  data object StateChange : NotificationReason("state_change")

  data object Subscribed : NotificationReason("subscribed")

  data object TeamMention : NotificationReason("team_mention")

  class Other(raw: String) : NotificationReason(raw) {
    override fun equals(other: Any?) = other is Other && other.raw == raw

    override fun hashCode() = raw.hashCode()

    override fun toString() = "Other($raw)"
  }

  companion object {
    private val known by lazy {
      listOf(
        ApprovalRequested,
        Assign,
        Author,
        CiActivity,
        Comment,
        Invitation,
        Manual,
        MemberFeatureRequested,
        Mention,
        ReviewRequested,
        SecurityAdvisoryCredit,
        SecurityAlert,
        StateChange,
        Subscribed,
        TeamMention,
      )
    }

    fun of(raw: String): NotificationReason = known.firstOrNull { it.raw == raw } ?: Other(raw)
  }
}

/** The result of one inbox poll. */
sealed interface InboxPoll {
  /** How long the forge asks clients to wait before polling again, if it says. */
  val pollInterval: Duration?

  /** Nothing changed since [InboxSource.poll]'s cursor. */
  data class NotModified(override val pollInterval: Duration?) : InboxPoll

  data class Changed(
    /**
     * Unread threads, newest update first. Only unread: GitHub can't list what's still in the inbox
     * once it's read, since its read threads include those marked done.
     */
    val threads: List<InboxThread>,
    /**
     * True if [threads] are all the unread threads. Otherwise they cover every thread updated at or
     * after the oldest one in it, and older threads weren't fetched.
     */
    val complete: Boolean,
    /** Pass to the next poll to ask only for changes. Opaque; GitHub's is `Last-Modified`. */
    val cursor: String?,
    override val pollInterval: Duration?,
    /**
     * Organizations whose threads the forge left out because the token isn't authorized for their
     * SAML single sign-on. Threads there aren't done, just hidden, so a poll with any of these
     * doesn't count as covering them.
     */
    val ssoRequired: List<SsoAuthorization> = emptyList(),
  ) : InboxPoll
}

/**
 * A forge's notification inbox for one account. Threads missing from a [InboxPoll.Changed] that it
 * covers aren't unread anymore: read or marked done elsewhere, which the forge may not tell apart.
 * Failures throw a [ForgeException] where the cause is known.
 */
interface InboxSource {
  /** Fetches the inbox, or reports it unchanged since [cursor]. */
  suspend fun poll(cursor: String?): InboxPoll

  suspend fun markRead(threadId: String)

  /** Removes the thread from the inbox until something new happens on it. */
  suspend fun markDone(threadId: String)
}

/**
 * Adds what each row needs beyond the notification itself: what happened since you last read it,
 * the subject's state, checks, and review state. The inbox calls it with threads whose update it
 * hasn't hydrated yet and stores what it returns. For GitHub, that's one GraphQL query per 50
 * threads, with `timelineItems(since: lastReadAt)` per thread under an alias.
 */
interface InboxHydrator {
  /**
   * Hydrates [threads]. Threads the forge can't describe (deleted, or behind SSO) come back with
   * nothing set, so they aren't asked about again until they change.
   */
  suspend fun hydrate(threads: List<InboxThread>): Hydration
}

data class Hydration(
  val threads: List<ThreadHydration>,
  /** What it cost in the forge's rate limit units, if it says: GitHub's GraphQL points. */
  val cost: Int? = null,
)

data class ThreadHydration(
  val threadId: String,
  /** The subject's state, raw from the forge: `OPEN`, `CLOSED`, or `MERGED` on GitHub. */
  val subjectState: String? = null,
  /** Why an issue was closed, raw: `COMPLETED`, `NOT_PLANNED`, `DUPLICATE` on GitHub. */
  val stateReason: String? = null,
  val isDraft: Boolean = false,
  /** The latest commit's combined checks, raw: `SUCCESS`, `FAILURE`, `PENDING`, and so on. */
  val checks: String? = null,
  /** A PR's review decision, raw: `APPROVED`, `CHANGES_REQUESTED`, `REVIEW_REQUIRED`. */
  val reviewDecision: String? = null,
  /** What happened since `lastReadAt`, as one line: "2 new commits, checks failed". */
  val whatsNew: String? = null,
)
