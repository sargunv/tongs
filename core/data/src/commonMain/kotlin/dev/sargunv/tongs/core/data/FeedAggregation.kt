package dev.sargunv.tongs.core.data

import dev.sargunv.tongs.forge.api.FeedAction
import dev.sargunv.tongs.forge.api.FeedCommit
import dev.sargunv.tongs.forge.api.FeedEventKind
import dev.sargunv.tongs.forge.api.RefType
import dev.sargunv.tongs.forge.api.RepoRef
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** Someone in the feed. */
data class FeedPerson(val login: String, val avatarUrl: String?) {
  val isBot: Boolean
    get() = login.endsWith("[bot]")
}

/** An issue or PR, with its latest known title and state. */
data class FeedSubjectInfo(
  /** Raw: `Issue` or `PullRequest`. */
  val type: String,
  val number: Int,
  val title: String?,
  /** Raw: `OPEN`, `CLOSED`, `MERGED` once hydrated; the event's lowercase state before. */
  val state: String?,
  val stateReason: String?,
  val isDraft: Boolean,
)

/** One person's part in a burst on an issue or PR: what they did, and how many times. */
data class FeedActivity(
  val actor: FeedPerson,
  val kind: FeedEventKind,
  val action: FeedAction?,
  val actionRaw: String?,
  val reviewState: String?,
  val count: Int,
)

/** The latest comment, review, or description in an item, for a peek. */
data class FeedExcerpt(val actor: FeedPerson, val text: String)

/**
 * One row of the feed: a single event, or a burst of related ones aggregated, as GitHub's own feed
 * does for stars. Every item has its newest time, the repos and people involved (people most recent
 * first, humans before bots), and the events behind it.
 */
sealed interface FeedItem {
  /** Stable across updates: the item's kind and its oldest event. */
  val key: String
  /** The account whose feed it's from, for opening it. */
  val accountId: Long
  /** The newest event's time, in epoch milliseconds. */
  val at: Long
  val repos: List<RepoRef>
  val actors: List<FeedPerson>
  val events: List<FeedEventEntity>
  /** Where it opens in a browser. */
  val webUrl: String?

  /** Events on one issue or PR in a burst: opened, labeled, commented, reviewed, merged. */
  data class Subject(
    override val key: String,
    override val accountId: Long,
    override val at: Long,
    override val actors: List<FeedPerson>,
    override val events: List<FeedEventEntity>,
    override val webUrl: String?,
    val repo: RepoRef,
    val subject: FeedSubjectInfo,
    /** Who did what, in the order they first did it. */
    val activity: List<FeedActivity>,
    val excerpt: FeedExcerpt?,
  ) : FeedItem {
    override val repos
      get() = listOf(repo)
  }

  /** Pushes to one branch in a burst, with their commits, and the branch's creation. */
  data class Push(
    override val key: String,
    override val accountId: Long,
    override val at: Long,
    override val actors: List<FeedPerson>,
    override val events: List<FeedEventEntity>,
    override val webUrl: String?,
    val repo: RepoRef,
    val ref: String?,
    /** Newest first, deduplicated; only what hydration found. */
    val commits: List<FeedCommit>,
    /** How many commits the pushes had, or null while any is unknown. */
    val commitCount: Int?,
    val pushes: Int,
    /** Whether the burst created the branch. */
    val createdBranch: Boolean,
    /** Whether every push has been hydrated, so [commits] is all there is to show. */
    val hydrated: Boolean,
  ) : FeedItem {
    override val repos
      get() = listOf(repo)
  }

  /** Stars: many people on one repo, or one person on many repos. */
  data class Starred(
    override val key: String,
    override val accountId: Long,
    override val at: Long,
    override val actors: List<FeedPerson>,
    override val events: List<FeedEventEntity>,
    override val webUrl: String?,
    override val repos: List<RepoRef>,
  ) : FeedItem

  /** Forks of one repo. */
  data class Forked(
    override val key: String,
    override val accountId: Long,
    override val at: Long,
    override val actors: List<FeedPerson>,
    override val events: List<FeedEventEntity>,
    override val webUrl: String?,
    val repo: RepoRef,
    /** The forks, `owner/name`. */
    val forks: List<String>,
  ) : FeedItem {
    override val repos
      get() = listOf(repo)
  }

  /** Branches or tags one person created or deleted in a repo, in a burst. */
  data class Refs(
    override val key: String,
    override val accountId: Long,
    override val at: Long,
    override val actors: List<FeedPerson>,
    override val events: List<FeedEventEntity>,
    override val webUrl: String?,
    val repo: RepoRef,
    val created: Boolean,
    /** A `RefType` name. */
    val refType: String?,
    val refs: List<String>,
  ) : FeedItem {
    override val repos
      get() = listOf(repo)
  }

  /** Anything else, one event a row: a release, a new repo, a member added, a wiki edit. */
  data class Single(
    override val key: String,
    override val accountId: Long,
    override val at: Long,
    override val actors: List<FeedPerson>,
    override val events: List<FeedEventEntity>,
    override val webUrl: String?,
    val event: FeedEventEntity,
  ) : FeedItem {
    override val repos
      get() = listOf(RepoRef(event.repoOwner, event.repoName))
  }
}

/** What's muted, lowercase: `owner/name` repos and logins. */
data class FeedMutes(val repos: Set<String> = emptySet(), val actors: Set<String> = emptySet()) {
  fun mutes(event: FeedEventEntity) =
    "${event.repoOwner}/${event.repoName}".lowercase() in repos ||
      event.actorLogin.lowercase() in actors
}

/**
 * Turns events into feed items, newest first. Muted repos and people are left out first. Then
 * bursts aggregate: events with the same key join one item while each is within [burstGap] of the
 * next (stars and forks within [starGap]), and the item sits at its newest event.
 * - Events on one issue or PR become one item that says who did what (`alice opened`, `bot labeled
 *   ×3`), showing the subject's latest title and state from [subjects].
 * - Pushes to one branch, and its creation, become one item with their commits.
 * - Stars on one repo become one item; a person's single stars on several repos then become one.
 *   Forks of one repo become one.
 * - A person's branch or tag creations, or deletions, in one repo become one. Tags a release made
 *   are left to the release.
 *
 * Events the same forge reported to more than one account show once.
 *
 * @param subjects issues and PRs by `accountId:owner/name#number`; see [subjectKey].
 * @param webUrls each account's instance web URL, for links to compares.
 */
fun aggregateFeed(
  events: List<FeedEventEntity>,
  subjects: Map<String, FeedSubjectEntity>,
  mutes: Map<Long, FeedMutes> = emptyMap(),
  webUrls: Map<Long, String> = emptyMap(),
  burstGap: Duration = 6.hours,
  starGap: Duration = 24.hours,
): List<FeedItem> {
  val visible =
    events
      .filterNot { mutes[it.accountId]?.mutes(it) == true }
      .distinctBy { instanceKey(it.accountId, webUrls) to it.id }
      .sortedWith(compareByDescending<FeedEventEntity> { it.createdAt }.thenByDescending { it.id })
  val releasedTags =
    visible
      .filter { it.eventKind == FeedEventKind.Release && it.ref != null }
      .mapTo(HashSet()) {
        Triple(
          instanceKey(it.accountId, webUrls),
          "${it.repoOwner}/${it.repoName}".lowercase(),
          it.ref,
        )
      }

  // Each burst collects its events, newest first; bursts keep the order of their newest event.
  class Burst(val key: String, val events: MutableList<FeedEventEntity>)
  val bursts = mutableListOf<Burst>()
  val open = mutableMapOf<String, Burst>()
  for (event in visible) {
    val instance = instanceKey(event.accountId, webUrls)
    val repoName = "${event.repoOwner}/${event.repoName}".lowercase()
    val repo = "$instance:$repoName"
    val kind = event.eventKind
    val key =
      when {
        event.subjectNumber != null &&
          kind in
            setOf(
              FeedEventKind.Issue,
              FeedEventKind.PullRequest,
              FeedEventKind.Comment,
              FeedEventKind.Review,
              FeedEventKind.ReviewComment,
              FeedEventKind.Other,
            ) -> "subject:$repo#${event.subjectNumber}"
        kind == FeedEventKind.Push -> "push:$repo:${event.ref}"
        kind == FeedEventKind.RefCreated && event.refType == RefType.Branch.name ->
          "push:$repo:${event.ref}"
        kind == FeedEventKind.RefCreated &&
          event.refType == RefType.Tag.name &&
          Triple(instance, repoName, event.ref) in releasedTags -> continue
        (kind == FeedEventKind.RefCreated && event.refType == RefType.Tag.name) ||
          kind == FeedEventKind.RefDeleted ->
          "refs:$repo:${event.actorLogin.lowercase()}:${kind.name}:${event.refType}"
        kind == FeedEventKind.Star -> "star:$repo"
        kind == FeedEventKind.Fork -> "fork:$repo"
        else -> "single:$instance:${event.id}"
      }
    val gap = if (kind == FeedEventKind.Star || kind == FeedEventKind.Fork) starGap else burstGap
    val burst = open[key]
    if (
      burst != null && burst.events.last().createdAt - event.createdAt <= gap.inWholeMilliseconds
    ) {
      burst.events += event
    } else {
      val new = Burst(key, mutableListOf(event))
      open[key] = new
      bursts += new
    }
  }

  val items = bursts.map { burst -> toItem(burst.key, burst.events, subjects, webUrls) }
  return mergeSingleStars(items, starGap, webUrls)
}

private fun instanceKey(accountId: Long, webUrls: Map<Long, String>): String =
  webUrls[accountId] ?: "account:$accountId"

/** The key [aggregateFeed] looks subjects up by. */
fun subjectKey(accountId: Long, owner: String, name: String, number: Int) =
  "$accountId:${owner.lowercase()}/${name.lowercase()}#$number"

private fun toItem(
  key: String,
  events: List<FeedEventEntity>,
  subjects: Map<String, FeedSubjectEntity>,
  webUrls: Map<Long, String>,
): FeedItem {
  val newest = events.first()
  val oldest = events.last()
  val itemKey = "${key.substringBefore(':')}:${oldest.accountId}:${oldest.id}"
  val repo = RepoRef(newest.repoOwner, newest.repoName)
  val actors = people(events)
  val at = newest.createdAt
  return when (key.substringBefore(':')) {
    "subject" -> {
      val number = newest.subjectNumber!!
      val stored = subjects[subjectKey(newest.accountId, repo.owner, repo.name, number)]
      val type = stored?.type ?: events.firstNotNullOfOrNull { it.subjectType } ?: "Issue"
      val chronological = events.asReversed()
      val activity = mutableListOf<FeedActivity>()
      for (e in chronological) {
        val person = FeedPerson(e.actorLogin, e.actorAvatarUrl)
        val i = activity.indexOfFirst {
          it.actor.login == e.actorLogin &&
            it.kind == e.eventKind &&
            it.actionRaw == e.actionRaw &&
            it.reviewState == e.reviewState
        }
        if (i >= 0) activity[i] = activity[i].copy(count = activity[i].count + 1)
        else
          activity +=
            FeedActivity(
              person,
              e.eventKind,
              e.action?.let { name -> FeedAction.entries.find { it.name == name } },
              e.actionRaw,
              e.reviewState,
              1,
            )
      }
      val excerpt =
        events
          .firstOrNull { it.excerpt != null }
          ?.let {
            FeedExcerpt(FeedPerson(it.actorLogin, it.actorAvatarUrl), it.excerpt!!)
          }
      FeedItem.Subject(
        key = itemKey,
        accountId = newest.accountId,
        at = at,
        actors = actors,
        events = events,
        webUrl =
          webUrls[newest.accountId]?.let {
            "$it/$repo/${if (type == "PullRequest") "pull" else "issues"}/$number"
          } ?: newest.webUrl,
        repo = repo,
        subject =
          FeedSubjectInfo(
            type = type,
            number = number,
            title = stored?.title ?: events.firstNotNullOfOrNull { it.subjectTitle },
            state = stored?.state ?: newest.subjectState,
            stateReason = stored?.stateReason,
            isDraft = stored?.isDraft ?: false,
          ),
        activity = activity,
        excerpt = excerpt,
      )
    }
    "push" -> {
      val pushes = events.filter { it.eventKind == FeedEventKind.Push }
      val allCommits = pushes.flatMap { it.commitList.orEmpty() }
      val commits = allCommits.distinctBy { it.sha }
      val counts = pushes.map { it.commitCount }
      val first = pushes.lastOrNull()
      val last = pushes.firstOrNull()
      val base = webUrls[newest.accountId]
      val compare =
        if (
          base != null &&
            first?.before != null &&
            last?.head != null &&
            first.before.any { it != '0' }
        ) {
          "$base/$repo/compare/${first.before.take(12)}...${last.head.take(12)}"
        } else null
      FeedItem.Push(
        key = itemKey,
        accountId = newest.accountId,
        at = at,
        actors = actors,
        events = events,
        webUrl = compare ?: last?.webUrl ?: newest.webUrl,
        repo = repo,
        ref = newest.ref,
        commits = commits,
        commitCount =
          if (counts.any { it == null }) null
          else counts.sumOf { it!! } - (allCommits.size - commits.size),
        pushes = pushes.size,
        createdBranch = events.any { it.eventKind == FeedEventKind.RefCreated },
        hydrated = pushes.all { it.pushHydrated },
      )
    }
    "star" ->
      FeedItem.Starred(
        key = itemKey,
        accountId = newest.accountId,
        at = at,
        actors = actors,
        events = events,
        webUrl = newest.webUrl,
        repos = listOf(repo),
      )
    "fork" ->
      FeedItem.Forked(
        key = itemKey,
        accountId = newest.accountId,
        at = at,
        actors = actors,
        events = events,
        webUrl =
          if (events.size == 1) newest.webUrl
          else webUrls[newest.accountId]?.let { "$it/$repo/forks" } ?: newest.webUrl,
        repo = repo,
        forks = events.mapNotNull { it.target },
      )
    "refs" ->
      FeedItem.Refs(
        key = itemKey,
        accountId = newest.accountId,
        at = at,
        actors = actors,
        events = events,
        webUrl = newest.webUrl,
        repo = repo,
        created = newest.eventKind == FeedEventKind.RefCreated,
        refType = newest.refType,
        refs = events.mapNotNull { it.ref }.distinct(),
      )
    else ->
      FeedItem.Single(
        key = itemKey,
        accountId = newest.accountId,
        at = at,
        actors = actors,
        events = events,
        webUrl = newest.webUrl,
        event = newest,
      )
  }
}

/** Distinct people, most recent first, humans before bots. */
private fun people(events: List<FeedEventEntity>): List<FeedPerson> {
  val all = events.map { FeedPerson(it.actorLogin, it.actorAvatarUrl) }.distinctBy { it.login }
  return all.filterNot { it.isBot } + all.filter { it.isBot }
}

/** Merges one person's single stars on different repos, within [gap] of each other, into one. */
private fun mergeSingleStars(
  items: List<FeedItem>,
  gap: Duration,
  webUrls: Map<Long, String>,
): List<FeedItem> {
  val singles =
    items
      .filter { it is FeedItem.Starred && it.events.size == 1 }
      .groupBy {
        instanceKey(it.accountId, webUrls) to it.events.single().actorLogin.lowercase()
      }
  val replace = mutableMapOf<FeedItem, FeedItem?>()
  for ((_, stars) in singles) {
    if (stars.size < 2) continue
    // Newest first already; split where the gap between two stars is too long.
    var run = mutableListOf<FeedItem>()
    fun flush() {
      if (run.size >= 2) {
        val events = run.flatMap { it.events }
        val newest = run.first()
        replace[newest] =
          FeedItem.Starred(
            key = "stars:${events.last().accountId}:${events.last().id}",
            accountId = newest.accountId,
            at = newest.at,
            actors = newest.actors,
            events = events,
            webUrl =
              webUrls[newest.accountId]?.let { "$it/${newest.actors.first().login}?tab=stars" },
            repos = run.flatMap { it.repos }.distinct(),
          )
        for (other in run.drop(1)) replace[other] = null
      }
      run = mutableListOf()
    }
    for (star in stars) {
      if (run.isNotEmpty() && run.last().at - star.at > gap.inWholeMilliseconds) flush()
      run += star
    }
    flush()
  }
  if (replace.isEmpty()) return items
  return items.mapNotNull { if (it in replace) replace[it] else it }
}

/** How the feed's rows are grouped: one list, or under each repo or person. */
enum class FeedGrouping {
  None,
  Repo,
  Person,
}

/** A group of items under a header, for a grouped feed. */
data class FeedSection(
  val key: String,
  val title: String,
  /** The person's avatar, for a person's section. */
  val avatarUrl: String?,
  val items: List<FeedItem>,
)

/**
 * Groups [items] by their first repo or first person, groups ordered by their newest item and items
 * newest first within. With [FeedGrouping.None], one untitled section.
 */
fun groupFeed(
  items: List<FeedItem>,
  grouping: FeedGrouping,
  webUrls: Map<Long, String> = emptyMap(),
): List<FeedSection> =
  when (grouping) {
    FeedGrouping.None -> listOf(FeedSection("all", "", null, items))
    FeedGrouping.Repo ->
      items
        .groupBy {
          "${instanceKey(it.accountId, webUrls)}:${it.repos.first().toString().lowercase()}"
        }
        .map { (key, group) ->
          FeedSection("repo:$key", group.first().repos.first().toString(), null, group)
        }
    FeedGrouping.Person ->
      items
        .groupBy { "${instanceKey(it.accountId, webUrls)}:${it.actors.first().login.lowercase()}" }
        .map { (key, group) ->
          val person = group.first().actors.first()
          FeedSection("person:$key", person.login, person.avatarUrl, group)
        }
  }
