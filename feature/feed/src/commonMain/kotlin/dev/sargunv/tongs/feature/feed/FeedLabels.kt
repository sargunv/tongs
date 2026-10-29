package dev.sargunv.tongs.feature.feed

import dev.sargunv.tongs.core.data.FeedActivity
import dev.sargunv.tongs.core.data.FeedItem
import dev.sargunv.tongs.core.data.FeedPerson
import dev.sargunv.tongs.forge.api.FeedEventKind
import dev.sargunv.tongs.forge.api.RefType

/** A person as the feed names them: "You" for the account itself, bots without `[bot]`. */
internal fun personName(login: String, viewer: String?): String =
  if (viewer != null && login.equals(viewer, ignoreCase = true)) "You"
  else login.removeSuffix("[bot]")

/** "alice", "alice and bob", "alice, bob, and 3 others". */
internal fun people(actors: List<FeedPerson>, viewer: String?, max: Int = 2): String {
  val names = actors.map { personName(it.login, viewer) }
  return when {
    names.size <= 1 -> names.firstOrNull().orEmpty()
    names.size == 2 -> "${names[0]} and ${names[1]}"
    names.size <= max + 1 -> names.dropLast(1).joinToString(", ") + ", and ${names.last()}"
    else -> names.take(max).joinToString(", ") + ", and ${names.size - max} others"
  }
}

private fun plural(n: Int, one: String, many: String = one + "s") =
  "$n ${if (n == 1) one else many}"

/** What one activity did, in the past tense: "opened", "requested changes", "labeled ×3". */
internal fun activityPhrase(activity: FeedActivity): String {
  val raw = activity.actionRaw
  val phrase =
    when (activity.kind) {
      FeedEventKind.Comment -> "commented"
      FeedEventKind.ReviewComment -> "commented on the diff"
      FeedEventKind.Review ->
        when (activity.reviewState) {
          "approved" -> "approved"
          "changes_requested" -> "requested changes"
          "dismissed" -> "dismissed a review"
          else -> "reviewed"
        }
      FeedEventKind.Issue,
      FeedEventKind.PullRequest ->
        when (raw) {
          null -> "updated"
          "review_requested" -> "requested a review"
          "review_request_removed" -> "removed a review request"
          "ready_for_review" -> "marked ready for review"
          "converted_to_draft" -> "converted to draft"
          "synchronize" -> "pushed"
          else -> raw.replace('_', ' ')
        }
      else -> raw?.replace('_', ' ') ?: "updated"
    }
  return if (activity.count > 1) "$phrase ×${activity.count}" else phrase
}

/**
 * Who did what on an issue or PR, in order, a person's consecutive actions together: "alice opened
 * · bot labeled ×3 · bob approved".
 */
internal fun activityLine(activity: List<FeedActivity>, viewer: String?): String {
  val runs = mutableListOf<Pair<String, MutableList<String>>>()
  for (a in activity) {
    val name = personName(a.actor.login, viewer)
    val last = runs.lastOrNull()
    if (last != null && last.first == name) last.second += activityPhrase(a)
    else runs += name to mutableListOf(activityPhrase(a))
  }
  return runs.joinToString(" · ") { (name, phrases) -> "$name ${phrases.joinToString(", ")}" }
}

private fun refNoun(refType: String?, count: Int = 1) =
  when (refType) {
    RefType.Tag.name -> if (count == 1) "tag" else "tags"
    RefType.Repository.name -> if (count == 1) "repository" else "repositories"
    else -> if (count == 1) "branch" else "branches"
  }

/** The row's headline: the subject's title, or what happened. */
internal fun headline(item: FeedItem, viewer: String?): String =
  when (item) {
    is FeedItem.Subject ->
      item.subject.title
        ?: "${if (item.subject.type == "PullRequest") "Pull request" else "Issue"} #${item.subject.number}"
    is FeedItem.Push -> {
      val ref = item.ref ?: "a branch"
      val count = item.commitCount
      when {
        item.pushes == 0 -> "Created branch $ref"
        count != null && count > 0 -> "Pushed ${plural(count, "commit")} to $ref"
        else -> "Pushed to $ref"
      }
    }
    is FeedItem.Starred ->
      if (item.repos.size > 1) "Starred ${plural(item.repos.size, "repository", "repositories")}"
      else "Starred by ${people(item.actors, viewer)}"
    is FeedItem.Forked -> "Forked by ${people(item.actors, viewer)}"
    is FeedItem.Refs -> {
      val verb = if (item.created) "Created" else "Deleted"
      if (item.refs.size == 1) "$verb ${refNoun(item.refType)} ${item.refs.single()}"
      else "$verb ${item.refs.size} ${refNoun(item.refType, item.refs.size)}"
    }
    is FeedItem.Single -> singleHeadline(item, viewer)
  }

private fun singleHeadline(item: FeedItem.Single, viewer: String?): String {
  val e = item.event
  return when (e.eventKind) {
    FeedEventKind.Release -> e.title ?: e.ref ?: "New release"
    FeedEventKind.RefCreated ->
      if (e.refType == RefType.Repository.name) "Created the repository"
      else "Created ${refNoun(e.refType)} ${e.ref}"
    FeedEventKind.Member ->
      e.target?.let { "Added ${personName(it, viewer)} as a collaborator" }
        ?: "Added a collaborator"
    FeedEventKind.Publicized -> "Made the repository public"
    FeedEventKind.Wiki -> e.title?.let { "Edited the wiki: $it" } ?: "Edited the wiki"
    FeedEventKind.CommitComment -> "Commented on ${e.head?.take(7) ?: "a commit"}"
    FeedEventKind.Discussion -> e.title ?: "Discussion"
    else ->
      e.kindRaw
        .removeSuffix("Event")
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .lowercase()
        .replaceFirstChar { it.uppercase() }
  }
}

/** The row's line under the headline: who, and what they did. */
internal fun byline(item: FeedItem, viewer: String?): String =
  when (item) {
    is FeedItem.Subject -> activityLine(item.activity, viewer)
    is FeedItem.Push -> {
      val who = people(item.actors, viewer)
      buildList {
          add(who)
          if (item.createdBranch && item.pushes > 0) add("new branch")
          if (item.pushes > 1) add("${item.pushes} pushes")
        }
        .joinToString(" · ")
    }
    is FeedItem.Starred ->
      if (item.repos.size > 1) {
        val names = item.repos.map { it.toString() }
        people(item.actors, viewer) +
          " · " +
          names.take(3).joinToString(", ") +
          if (names.size > 3) ", …" else ""
      } else if (item.actors.size > 3) "${item.actors.size} people"
      else people(item.actors, viewer, max = 3)
    is FeedItem.Forked ->
      item.forks.take(3).joinToString(", ") + if (item.forks.size > 3) ", …" else ""
    is FeedItem.Refs ->
      people(item.actors, viewer) +
        (if (item.refs.size > 1)
          " · " + item.refs.take(4).joinToString(", ") + if (item.refs.size > 4) ", …" else ""
        else "")
    is FeedItem.Single -> {
      val e = item.event
      val who = people(item.actors, viewer)
      when (e.eventKind) {
        FeedEventKind.Release -> "$who published ${e.ref ?: "a release"}"
        else -> who
      }
    }
  }

/** The overline's start: the repo, or how many for an item across repos. */
internal fun repoLabel(item: FeedItem): String =
  if (item.repos.size > 1) "${item.repos.size} repositories" else item.repos.first().toString()
