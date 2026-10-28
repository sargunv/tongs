package dev.sargunv.tongs.forge.api

/**
 * Something that happened on an issue or PR, from its timeline. Adapters turn their forge's
 * timeline into these and [summarizeWhatsNew] words them, so every forge's rows read alike. [actor]
 * is a login, or null for a deleted user.
 */
sealed interface SubjectActivity {
  val actor: String?

  data class Commit(override val actor: String?) : SubjectActivity

  data class ForcePush(override val actor: String?) : SubjectActivity

  data class Comment(override val actor: String?) : SubjectActivity

  /** A submitted review; [state] is raw: `APPROVED`, `CHANGES_REQUESTED`, `COMMENTED`. */
  data class Review(override val actor: String?, val state: String) : SubjectActivity

  /** A review request; [reviewer] is a user's login or a team's `org/slug`. */
  data class ReviewRequested(override val actor: String?, val reviewer: String?) : SubjectActivity

  data class Merged(override val actor: String?) : SubjectActivity

  data class Closed(override val actor: String?) : SubjectActivity

  data class Reopened(override val actor: String?) : SubjectActivity

  data class ReadyForReview(override val actor: String?) : SubjectActivity

  data class ConvertedToDraft(override val actor: String?) : SubjectActivity

  data class Assigned(override val actor: String?, val assignee: String?) : SubjectActivity
}

/**
 * Words what happened on a thread since you last read it as one short line, most important first:
 * "Review requested from you", "Merged by alice", "2 new comments from bob", "3 new commits".
 *
 * Your own actions aren't news, so [viewer]'s are left out, except that a failing check run on a
 * commit you haven't seen yet ([checksFailed]) is news whoever pushed it. [truncated] means the
 * forge had more activity than it sent, so counts are lower bounds ("20+ new comments"). Returns
 * null when there's nothing to say.
 */
fun summarizeWhatsNew(
  activity: List<SubjectActivity>,
  viewer: String,
  checksFailed: Boolean = false,
  truncated: Boolean = false,
): String? {
  fun mine(a: SubjectActivity) = a.actor.equals(viewer, ignoreCase = true)
  val others = activity.filterNot(::mine)
  val plus = if (truncated) "+" else ""
  val parts = buildList {
    val requests = activity.filterIsInstance<SubjectActivity.ReviewRequested>()
    if (requests.any { it.reviewer.equals(viewer, ignoreCase = true) }) {
      add("Review requested from you")
    } else {
      requests.filterNot(::mine).lastOrNull()?.reviewer?.let { add("Review requested from $it") }
    }
    // Only the latest change of state matters: closed then reopened is just reopened.
    when (val state = activity.lastOrNull { it.isStateChange }?.takeUnless(::mine)) {
      is SubjectActivity.Merged -> add("Merged".by(state.actor))
      is SubjectActivity.Closed -> add("Closed".by(state.actor))
      is SubjectActivity.Reopened -> add("Reopened".by(state.actor))
      else -> {}
    }
    val reviews = others.filterIsInstance<SubjectActivity.Review>()
    fun reviewed(state: String, label: String) {
      val matching = reviews.filter { it.state == state }
      if (matching.isNotEmpty()) add(label.by(names(matching.mapNotNull { it.actor }.distinct())))
    }
    reviewed("CHANGES_REQUESTED", "Changes requested")
    reviewed("APPROVED", "Approved")
    if (checksFailed) add("Checks failed")
    val comments = others.filterIsInstance<SubjectActivity.Comment>()
    if (comments.isNotEmpty()) {
      val authors = comments.mapNotNull { it.actor }.distinct()
      val count = comments.size
      val noun = if (count == 1 && !truncated) "New comment" else "$count$plus new comments"
      add(if (authors.size in 1..2) "$noun from ${names(authors)}" else noun)
    }
    reviewed("COMMENTED", "Reviewed")
    val commits = others.count { it is SubjectActivity.Commit }
    when {
      commits > 0 -> add("$commits$plus new commit${if (commits == 1 && !truncated) "" else "s"}")
      others.any { it is SubjectActivity.ForcePush } -> add("Force-pushed")
    }
    when (
      others.lastOrNull {
        it is SubjectActivity.ReadyForReview || it is SubjectActivity.ConvertedToDraft
      }
    ) {
      is SubjectActivity.ReadyForReview -> add("Ready for review")
      is SubjectActivity.ConvertedToDraft -> add("Converted to draft")
      else -> {}
    }
    val assigned = others.filterIsInstance<SubjectActivity.Assigned>()
    if (assigned.any { it.assignee.equals(viewer, ignoreCase = true) }) add("Assigned to you")
  }
  if (parts.isEmpty()) return null
  // Sentence case: only the line's first part keeps its capital.
  return parts
    .mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar { it.lowercase() } }
    .joinToString(", ")
}

private val SubjectActivity.isStateChange
  get() =
    this is SubjectActivity.Merged ||
      this is SubjectActivity.Closed ||
      this is SubjectActivity.Reopened

private fun String.by(who: String?) = if (who.isNullOrEmpty()) this else "$this by $who"

private fun names(logins: List<String>): String =
  when (logins.size) {
    0 -> ""
    1 -> logins[0]
    2 -> "${logins[0]} and ${logins[1]}"
    else -> "${logins[0]} and ${logins.size - 1} others"
  }
