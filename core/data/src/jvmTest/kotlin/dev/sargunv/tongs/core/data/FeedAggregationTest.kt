package dev.sargunv.tongs.core.data

import dev.sargunv.tongs.forge.api.FeedAction
import dev.sargunv.tongs.forge.api.FeedCommit
import dev.sargunv.tongs.forge.api.FeedEventKind
import dev.sargunv.tongs.forge.api.RepoRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class FeedAggregationTest {
  private var nextId = 1000

  private fun event(
    kind: FeedEventKind,
    ago: Duration,
    actor: String = "alice",
    repo: String = "octo/hello",
    action: FeedAction? = null,
    number: Int? = null,
    subjectType: String? = number?.let { "PullRequest" },
    ref: String? = null,
    refType: String? = null,
    before: String? = null,
    head: String? = null,
    commits: List<FeedCommit>? = null,
    excerpt: String? = null,
    accountId: Long = 1,
    id: String = (nextId++).toString(),
  ) =
    FeedEventEntity(
      accountId = accountId,
      id = id,
      kind = kind.name,
      kindRaw = kind.name,
      action = action?.name,
      actionRaw = action?.raw,
      actorLogin = actor,
      actorAvatarUrl = null,
      repoOwner = repo.substringBefore('/'),
      repoName = repo.substringAfter('/'),
      createdAt = (START - ago).toEpochMilliseconds(),
      isPublic = true,
      subjectType = subjectType,
      subjectNumber = number,
      subjectTitle = null,
      subjectState = null,
      ref = ref,
      refType = refType,
      before = before,
      head = head,
      commits = encodeCommits(commits),
      commitCount = commits?.size,
      pushHydrated = commits != null,
      title = null,
      excerpt = excerpt,
      reviewState = null,
      target = null,
      webUrl = "https://github.com/$repo",
    )

  private fun push(
    ago: Duration,
    before: String,
    head: String,
    vararg titles: String,
    ref: String = "main",
  ) =
    event(
      FeedEventKind.Push,
      ago,
      ref = ref,
      refType = "Branch",
      before = before,
      head = head,
      commits = titles.map { FeedCommit("$head-$it", it) },
    )

  private val web = mapOf(1L to "https://github.com")

  @Test
  fun pushesToOneBranchInABurstBecomeOneItemWithTheirCommits() {
    val events =
      listOf(
        push(10.minutes, "bbbbbbbbbbbbbbbb", "cccccccccccccccc", "Third", "Second"),
        push(1.hours, "aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", "First"),
        push(2.hours, "1111111111111111", "2222222222222222", "Elsewhere", ref = "feature"),
        // More than the gap before the burst: its own item.
        push(9.hours, "0000000000000001", "aaaaaaaaaaaaaaaa", "Older"),
      )

    val items = aggregateFeed(events, emptyMap(), webUrls = web)

    assertEquals(3, items.size)
    val main = assertIs<FeedItem.Push>(items[0])
    assertEquals("main", main.ref)
    assertEquals(2, main.pushes)
    assertEquals(listOf("Third", "Second", "First"), main.commits.map { it.title })
    assertEquals(3, main.commitCount)
    assertEquals(
      "https://github.com/octo/hello/compare/aaaaaaaaaaaa...cccccccccccc",
      main.webUrl,
    )
    assertEquals("feature", (items[1] as FeedItem.Push).ref)
    assertEquals(listOf("Older"), (items[2] as FeedItem.Push).commits.map { it.title })
  }

  @Test
  fun commitsRepeatedByAForcePushCountOnce() {
    val original = push(2.minutes, "base", "head", "Shared")
    val repeated =
      original.copy(id = "again", createdAt = (START - 1.minutes).toEpochMilliseconds())
    val item =
      assertIs<FeedItem.Push>(aggregateFeed(listOf(repeated, original), emptyMap()).single())
    assertEquals(2, item.pushes)
    assertEquals(1, item.commitCount)
    assertEquals(1, item.commits.size)
  }

  @Test
  fun aBranchsCreationJoinsItsPushes() {
    val events =
      listOf(
        push(1.minutes, "aaaa", "bbbb", "Work", ref = "feat/x"),
        event(FeedEventKind.RefCreated, 2.minutes, ref = "feat/x", refType = "Branch"),
      )

    val item = assertIs<FeedItem.Push>(aggregateFeed(events, emptyMap()).single())

    assertEquals(true, item.createdBranch)
    assertEquals(1, item.pushes)
  }

  @Test
  fun unhydratedPushesHaveNoCountYet() {
    val events =
      listOf(
        event(FeedEventKind.Push, 1.minutes, ref = "main", before = "a", head = "b"),
        push(2.minutes, "z", "a", "Known"),
      )

    val item = assertIs<FeedItem.Push>(aggregateFeed(events, emptyMap()).single())

    assertNull(item.commitCount)
    assertEquals(false, item.hydrated)
  }

  @Test
  fun eventsOnOneSubjectSayWhoDidWhatInOrder() {
    val events =
      listOf(
        event(FeedEventKind.Comment, 1.minutes, actor = "bob", number = 7, excerpt = "Looks good"),
        event(
          FeedEventKind.PullRequest,
          3.minutes,
          actor = "bot[bot]",
          action = FeedAction.Labeled,
          number = 7,
        ),
        event(
          FeedEventKind.PullRequest,
          3.minutes,
          actor = "bot[bot]",
          action = FeedAction.Labeled,
          number = 7,
        ),
        event(
          FeedEventKind.PullRequest,
          3.minutes,
          actor = "bot[bot]",
          action = FeedAction.Labeled,
          number = 7,
        ),
        event(
          FeedEventKind.PullRequest,
          4.minutes,
          actor = "alice",
          action = FeedAction.Opened,
          number = 7,
        ),
        event(
          FeedEventKind.PullRequest,
          5.minutes,
          actor = "alice",
          action = FeedAction.Opened,
          number = 8,
        ),
      )
    val subjects =
      mapOf(
        subjectKey(1, "octo", "hello", 7) to
          FeedSubjectEntity(
            1,
            "octo",
            "hello",
            7,
            "PullRequest",
            "Add a thing",
            "OPEN",
            null,
            true,
            true,
            0,
          ),
      )

    val items = aggregateFeed(events, subjects)

    assertEquals(2, items.size)
    val pr = assertIs<FeedItem.Subject>(items[0])
    assertEquals("Add a thing", pr.subject.title)
    assertEquals("OPEN", pr.subject.state)
    assertEquals(true, pr.subject.isDraft)
    assertEquals(
      listOf("alice Opened 1", "bot[bot] Labeled 3", "bob null 1"),
      pr.activity.map { "${it.actor.login} ${it.action} ${it.count}" },
    )
    // Humans first, most recent first; bots after.
    assertEquals(listOf("bob", "alice", "bot[bot]"), pr.actors.map { it.login })
    assertEquals(FeedExcerpt(FeedPerson("bob", null), "Looks good"), pr.excerpt)
    assertEquals(8, (items[1] as FeedItem.Subject).subject.number)
  }

  @Test
  fun starsOnARepoAggregateAndAPersonsStarsAcrossReposToo() {
    val events =
      listOf(
        event(FeedEventKind.Star, 1.minutes, actor = "a", repo = "octo/popular"),
        event(FeedEventKind.Star, 2.hours, actor = "b", repo = "octo/popular"),
        event(FeedEventKind.Star, 3.hours, actor = "c", repo = "octo/popular"),
        event(FeedEventKind.Star, 4.hours, actor = "dana", repo = "x/one"),
        event(FeedEventKind.Star, 5.hours, actor = "dana", repo = "x/two"),
        event(FeedEventKind.Star, 6.hours, actor = "dana", repo = "x/three"),
        event(FeedEventKind.Star, 7.hours, actor = "erin", repo = "x/four"),
      )

    val items = aggregateFeed(events, emptyMap(), webUrls = web)

    assertEquals(3, items.size)
    val popular = assertIs<FeedItem.Starred>(items[0])
    assertEquals(listOf("a", "b", "c"), popular.actors.map { it.login })
    assertEquals(listOf(RepoRef("octo", "popular")), popular.repos)
    val dana = assertIs<FeedItem.Starred>(items[1])
    assertEquals(listOf("dana"), dana.actors.map { it.login })
    assertEquals(listOf("one", "two", "three"), dana.repos.map { it.name })
    assertEquals("https://github.com/dana?tab=stars", dana.webUrl)
    assertEquals(listOf("erin"), items[2].actors.map { it.login })
  }

  @Test
  fun mutedReposAndPeopleAreLeftOut() {
    val events =
      listOf(
        event(FeedEventKind.Comment, 1.minutes, actor = "noisy[bot]", number = 1),
        event(
          FeedEventKind.Issue,
          2.minutes,
          actor = "alice",
          action = FeedAction.Opened,
          number = 1,
          subjectType = "Issue",
        ),
        event(FeedEventKind.Star, 3.minutes, repo = "Loud/Repo"),
      )
    val mutes = mapOf(1L to FeedMutes(repos = setOf("loud/repo"), actors = setOf("noisy[bot]")))

    val item = assertIs<FeedItem.Subject>(aggregateFeed(events, emptyMap(), mutes).single())

    assertEquals(listOf("alice"), item.actors.map { it.login })
    assertEquals("Issue", item.subject.type)
  }

  @Test
  fun aReleasesTagIsLeftToTheReleaseAndRefsAggregate() {
    val events =
      listOf(
        event(FeedEventKind.Release, 1.minutes, ref = "v1.0", refType = "Tag"),
        event(FeedEventKind.RefCreated, 2.minutes, ref = "v1.0", refType = "Tag"),
        event(FeedEventKind.RefDeleted, 3.minutes, ref = "old-1", refType = "Branch"),
        event(FeedEventKind.RefDeleted, 4.minutes, ref = "old-2", refType = "Branch"),
      )

    val items = aggregateFeed(events, emptyMap())

    assertEquals(2, items.size)
    assertEquals(FeedEventKind.Release, assertIs<FeedItem.Single>(items[0]).event.eventKind)
    val deleted = assertIs<FeedItem.Refs>(items[1])
    assertEquals(false, deleted.created)
    assertEquals(listOf("old-1", "old-2"), deleted.refs)
  }

  @Test
  fun anEventReportedToTwoAccountsShowsOnce() {
    val events =
      listOf(
        event(FeedEventKind.Star, 1.minutes, accountId = 1, id = "42"),
        event(FeedEventKind.Star, 1.minutes, accountId = 2, id = "42"),
      )

    assertEquals(1, aggregateFeed(events, emptyMap()).single().events.size)
  }

  @Test
  fun instanceEventIdsDoNotCollideAndMutesApplyBeforeDeduplicating() {
    val first = event(FeedEventKind.Star, 1.minutes, accountId = 1, id = "42")
    val second = first.copy(accountId = 2)
    val mutes = mapOf(1L to FeedMutes(actors = setOf("alice")))
    assertEquals(2L, aggregateFeed(listOf(first, second), emptyMap(), mutes).single().accountId)
    assertEquals(
      2,
      aggregateFeed(
          listOf(first, second),
          emptyMap(),
          webUrls = mapOf(1L to "https://github.com", 2L to "https://git.example.com"),
        )
        .size,
    )
  }

  @Test
  fun groupsByRepoOrPersonInOrderOfTheirNewestItem() {
    val events =
      listOf(
        event(FeedEventKind.Star, 1.minutes, actor = "a", repo = "o/two"),
        event(FeedEventKind.Fork, 2.minutes, actor = "b", repo = "o/one"),
        event(FeedEventKind.Release, 3.minutes, actor = "a", repo = "o/one"),
      )
    val items = aggregateFeed(events, emptyMap())

    assertEquals(
      listOf("o/two" to 1, "o/one" to 2),
      groupFeed(items, FeedGrouping.Repo).map { it.title to it.items.size },
    )
    assertEquals(
      listOf("a" to 2, "b" to 1),
      groupFeed(items, FeedGrouping.Person).map { it.title to it.items.size },
    )
    assertEquals(3, groupFeed(items, FeedGrouping.None).single().items.size)
  }
}
