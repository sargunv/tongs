package dev.sargunv.tongs.core.data

import dev.sargunv.tongs.forge.api.SubjectType

/**
 * An inbox filter in GitHub's vocabulary, as its web inbox and search take it:
 * - `reason:review-requested` (or `review_requested`): GitHub's reason, raw
 * - `is:pr`, `is:issue`, `is:release`, `is:discussion`, `is:commit`, `is:check-suite`: the subject
 * - `is:unread`, `is:read`
 * - `is:read-elsewhere`: read or done elsewhere, which GitHub doesn't tell apart (Tongs' own)
 * - `is:open`, `is:closed`, `is:merged`, `is:draft`: the subject's state, once hydrated
 * - `is:snoozed`: threads snoozed for later, which the inbox otherwise hides
 * - `repo:owner/name` (or just `name`), `org:owner`
 * - anything else matches the title, repo, or `#number`
 *
 * Values of one kind are alternatives (`reason:mention reason:team-mention` is either), different
 * kinds all apply, a leading `-` negates a term, and `reason:a,b` lists alternatives. Unknown
 * qualifiers are kept in [unknown] and ignored, so the UI can point them out.
 */
data class InboxQuery(
  val text: String,
  private val include: Map<Kind, Set<String>>,
  private val exclude: Map<Kind, Set<String>>,
  private val words: List<String>,
  val unknown: List<String>,
) {
  /** Kinds of term; terms of one kind are alternatives. */
  enum class Kind {
    Reason,
    Type,
    Read,
    ReadElsewhere,
    State,
    Snoozed,
    Repo,
    Org,
  }

  val isEmpty: Boolean
    get() = include.isEmpty() && exclude.isEmpty() && words.isEmpty()

  /** Whether the filter asks for snoozed threads, which come from elsewhere than the inbox. */
  val wantsSnoozed: Boolean
    get() = Kind.Snoozed in include

  fun matches(row: InboxRow): Boolean {
    for ((kind, values) in include) if (values.none { kind.test(it, row) }) return false
    for ((kind, values) in exclude) if (values.any { kind.test(it, row) }) return false
    if (words.isNotEmpty()) {
      val haystack =
        "${row.title} ${row.repoOwner}/${row.repoName} #${row.subjectNumber ?: ""}".lowercase()
      if (words.any { it !in haystack }) return false
    }
    return true
  }

  /** Whether [kind]'s normalized [value] holds for [row]. */
  private fun Kind.test(value: String, row: InboxRow): Boolean =
    when (this) {
      Kind.Reason -> row.reason.lowercase() == value
      Kind.Type ->
        if (value == ISSUE_OR_PR) {
          row.subjectType == SubjectType.Issue.raw || row.subjectType == SubjectType.PullRequest.raw
        } else {
          row.subjectType == value
        }
      Kind.Read -> row.isUnread == (value == "unread")
      Kind.ReadElsewhere -> row.readElsewhere
      Kind.State ->
        when (value) {
          "draft" -> row.isDraft && row.subjectState == "OPEN"
          // An open draft isn't open for review, but GitHub's is:open includes drafts; so do we.
          else -> row.subjectState.equals(value, ignoreCase = true)
        }
      // The query picks the list rows come from: the snoozed ones, or the inbox, which hides them.
      Kind.Snoozed -> wantsSnoozed
      Kind.Repo ->
        if ('/' in value) "${row.repoOwner}/${row.repoName}".lowercase() == value
        else row.repoName.lowercase() == value
      Kind.Org -> row.repoOwner.lowercase() == value
    }

  /** The query with [term] added, or removed if it's already there. For suggestion chips. */
  fun toggle(term: String): String {
    val tokens = tokenize(text)
    return if (tokens.any { it.equals(term, ignoreCase = true) }) {
      tokens.filterNot { it.equals(term, ignoreCase = true) }.joinToString(" ")
    } else {
      (tokens + term).joinToString(" ")
    }
  }

  /** Whether the query has exactly [term], as a suggestion chip shows. */
  fun has(term: String): Boolean = tokenize(text).any { it.equals(term, ignoreCase = true) }

  companion object {
    private const val ISSUE_OR_PR = "issue-or-pull-request"

    /** The term for threads read or done elsewhere, which "Mark done: read elsewhere" clears. */
    const val READ_ELSEWHERE = "read-elsewhere"

    val Empty = parse("")

    fun parse(text: String): InboxQuery {
      val include = mutableMapOf<Kind, MutableSet<String>>()
      val exclude = mutableMapOf<Kind, MutableSet<String>>()
      val words = mutableListOf<String>()
      val unknown = mutableListOf<String>()
      for (token in tokenize(text)) {
        val negated = token.startsWith('-') && ':' in token
        val term = token.removePrefix("-").takeIf { negated } ?: token
        val key = term.substringBefore(':', "").lowercase()
        val rawValue = term.substringAfter(':', "").trim('"')
        if (key.isEmpty()) {
          words += token.trim('"').lowercase()
          continue
        }
        // A qualifier still being typed, like `is:`, filters nothing yet.
        if (rawValue.isEmpty()) continue
        for (value in
          rawValue.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }) {
          val (kind, normalized) = classify(key, value) ?: (null to null)
          if (kind == null || normalized == null) {
            unknown += token
            break
          }
          (if (negated) exclude else include).getOrPut(kind) { mutableSetOf() } += normalized
        }
      }
      return InboxQuery(text, include, exclude, words, unknown.distinct())
    }

    private fun classify(key: String, value: String): Pair<Kind, String>? =
      when (key) {
        "reason" -> Kind.Reason to value.replace('-', '_')
        "repo" -> Kind.Repo to value
        "org",
        "user",
        "owner" -> Kind.Org to value
        "is" ->
          when (value) {
            "pr",
            "pull-request",
            "pullrequest" -> Kind.Type to SubjectType.PullRequest.raw
            "issue" -> Kind.Type to SubjectType.Issue.raw
            "release" -> Kind.Type to SubjectType.Release.raw
            "discussion" -> Kind.Type to SubjectType.Discussion.raw
            "commit" -> Kind.Type to SubjectType.Commit.raw
            "check-suite" -> Kind.Type to SubjectType.CheckSuite.raw
            ISSUE_OR_PR -> Kind.Type to ISSUE_OR_PR
            "unread",
            "read" -> Kind.Read to value
            READ_ELSEWHERE -> Kind.ReadElsewhere to value
            "open",
            "closed",
            "merged",
            "draft" -> Kind.State to value
            "snoozed" -> Kind.Snoozed to value
            else -> null
          }
        else -> null
      }

    /** Splits on whitespace, keeping `"quoted values"` together. */
    internal fun tokenize(text: String): List<String> =
      Regex("""(?:[^\s"]+|"[^"]*")+""").findAll(text).map { it.value }.toList()
  }
}
