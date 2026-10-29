package dev.sargunv.tongs.core.data

import dev.sargunv.tongs.forge.api.Account
import dev.sargunv.tongs.forge.api.AuthMethod
import dev.sargunv.tongs.forge.api.AuthProvider
import dev.sargunv.tongs.forge.api.Capability
import dev.sargunv.tongs.forge.api.FeedActor
import dev.sargunv.tongs.forge.api.FeedCommit
import dev.sargunv.tongs.forge.api.FeedEvent
import dev.sargunv.tongs.forge.api.FeedEventKind
import dev.sargunv.tongs.forge.api.FeedHydration
import dev.sargunv.tongs.forge.api.FeedHydrator
import dev.sargunv.tongs.forge.api.FeedPoll
import dev.sargunv.tongs.forge.api.FeedSource
import dev.sargunv.tongs.forge.api.FeedSubject
import dev.sargunv.tongs.forge.api.ForgeAdapter
import dev.sargunv.tongs.forge.api.ForgeClient
import dev.sargunv.tongs.forge.api.ForgeException
import dev.sargunv.tongs.forge.api.ForgeKind
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.PushCommits
import dev.sargunv.tongs.forge.api.PushRef
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.SsoAuthorization
import dev.sargunv.tongs.forge.api.SubjectRef
import dev.sargunv.tongs.forge.api.SubjectState
import dev.sargunv.tongs.forge.api.SubjectType
import dev.sargunv.tongs.forge.api.VerifiedToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/** A feed that answers polls from a queue, and records the cursors it was sent. */
private class FakeFeed : FeedSource {
  val polls = ArrayDeque<Any>()
  val cursors = mutableListOf<String?>()

  override suspend fun poll(cursor: String?): FeedPoll {
    cursors += cursor
    return when (val next = polls.removeFirstOrNull()) {
      is FeedPoll -> next
      is Exception -> throw next
      else -> FeedPoll.NotModified(60.seconds)
    }
  }
}

/** Says every subject is open and titled by its number, and gives each push one commit. */
private class FakeFeedHydrator : FeedHydrator {
  val subjects = mutableListOf<List<Int>>()
  val pushes = mutableListOf<List<String>>()
  val failures = ArrayDeque<Exception>()
  var missingCommits = false

  override suspend fun hydrate(subjects: List<SubjectRef>, pushes: List<PushRef>): FeedHydration {
    this.subjects += subjects.map { it.number }
    this.pushes += pushes.map { it.head }
    failures.removeFirstOrNull()?.let { throw it }
    return FeedHydration(
      subjects =
        subjects.map { SubjectState(it, SubjectType.PullRequest, "PR ${it.number}", "MERGED") },
      pushes =
        pushes.map {
          if (missingCommits) PushCommits(it, null, null)
          else PushCommits(it, listOf(FeedCommit(it.head, "Commit ${it.head}")), 1)
        },
      points = 1,
    )
  }
}

private class FeedAdapter(private val feed: FeedSource, private val hydrator: FeedHydrator) :
  ForgeAdapter {
  override val kind = ForgeKind.GitHub
  override val auth: AuthProvider =
    object : AuthProvider {
      override val tokenScopes = listOf("repo")

      override fun newTokenUrl(instance: Instance) = ""

      override suspend fun verify(instance: Instance, token: String) =
        VerifiedToken(
          "U_1",
          "octocat",
          null,
          null,
          AuthMethod.ClassicToken,
          setOf("repo"),
          emptySet(),
          setOf(Capability.Inbox),
        )
    }

  override fun client(account: Account, token: String): ForgeClient =
    object : ForgeClient {
      override val inbox = null
      override val reviews = null
      override val feed = this@FeedAdapter.feed
      override val feedHydrator = this@FeedAdapter.hydrator

      override fun close() {}
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FeedSyncTest {
  private val feed = FakeFeed()
  private val hydrator = FakeFeedHydrator()

  private suspend fun setUp(
    db: TongsDatabase,
    instance: Instance = Instance.GitHubDotCom,
  ): Pair<Long, ForgeClients> {
    val tokens = InMemoryTokenStore()
    val adapters = mapOf<ForgeKind, ForgeAdapter>(ForgeKind.GitHub to FeedAdapter(feed, hydrator))
    val clients = ForgeClients(db, tokens, adapters)
    val account = AccountRepository(db, tokens, adapters, clients).signIn(instance, "t")
    return account.account.id.value to clients
  }

  private fun pr(id: String, number: Int, at: Instant) =
    FeedEvent(
      id = id,
      kind = FeedEventKind.PullRequest,
      kindRaw = "PullRequestEvent",
      action = null,
      actionRaw = "opened",
      actor = FeedActor("alice", null),
      repo = RepoRef("octo", "hello"),
      createdAt = at,
      subject = FeedSubject(SubjectType.PullRequest, number),
    )

  private fun push(id: String, head: String, at: Instant) =
    FeedEvent(
      id = id,
      kind = FeedEventKind.Push,
      kindRaw = "PushEvent",
      action = null,
      actionRaw = null,
      actor = FeedActor("alice", null),
      repo = RepoRef("octo", "hello"),
      createdAt = at,
      ref = "main",
      before = "before-$head",
      head = head,
    )

  private fun changed(vararg events: FeedEvent, cursor: String = "c1") =
    FeedPoll.Changed(events.toList(), cursor, 60.seconds, requests = 1)

  @Test
  fun storesEventsOnceAndSendsTheCursor() = dbTest { db ->
    val (account, clients) = setUp(db)
    val sync = FeedSync(db, clients, virtualClock())
    feed.polls += changed(pr("1", 7, START - 1.hours), cursor = "etag-1")
    feed.polls += changed(pr("1", 7, START - 1.hours), pr("2", 8, START), cursor = "etag-2")
    feed.polls += FeedPoll.NotModified(90.seconds)

    sync.poll(account)
    sync.poll(account)
    sync.poll(account)

    assertEquals(listOf(null, "etag-1", "etag-2"), feed.cursors)
    assertEquals(listOf("2", "1"), db.feed().events(account).map { it.id })
    val state = db.feed().syncState(account)!!
    assertEquals("etag-2", state.cursor)
    assertEquals(90, state.pollIntervalSeconds)
    assertNull(state.lastError)
    assertEquals(FeedCost(polls = 3, requests = 2, points = 2), sync.cost.value)
  }

  @Test
  fun hydratesNewPushesAndSubjectsOnceUntilTheyChange() = dbTest { db ->
    val (account, clients) = setUp(db)
    val sync = FeedSync(db, clients, virtualClock())
    feed.polls += changed(pr("1", 7, START - 1.hours), push("2", "abc", START))
    feed.polls += FeedPoll.NotModified(60.seconds)
    feed.polls += changed(pr("3", 7, START + 1.minutes))

    sync.poll(account)
    sync.poll(account)
    sync.poll(account)

    assertEquals(listOf(listOf(7), listOf(7)), hydrator.subjects)
    assertEquals(listOf(listOf("abc"), emptyList()), hydrator.pushes)
    val subject = db.feed().subject(account, "octo", "hello", 7)!!
    assertEquals("PR 7", subject.title)
    assertEquals("MERGED", subject.state)
    val pushed = db.feed().events(account).first { it.id == "2" }
    assertEquals(listOf("Commit abc"), pushed.commitList?.map { it.title })

    val items = FeedRepository(db, InMemoryPreferences(), virtualClock()).feed(100).first().items
    assertEquals(listOf("subject", "push"), items.map { it.key.substringBefore(':') })
  }

  @Test
  fun aRefreshRehydratesRecentSubjects() = dbTest { db ->
    val (account, clients) = setUp(db)
    val sync = FeedSync(db, clients, virtualClock())
    feed.polls += changed(pr("1", 7, START))

    sync.poll(account)
    sync.refresh()

    assertEquals(listOf(listOf(7), listOf(7)), hydrator.subjects)
  }

  @Test
  fun recordsFailuresByKindAndWaitsForARateLimitsReset() = dbTest { db ->
    val (account, clients) = setUp(db)
    val clock = virtualClock()
    val sync = FeedSync(db, clients, clock)
    val reset = START + 30.minutes
    feed.polls += ForgeException.RateLimited(reset)
    feed.polls += ForgeException.NoAccess("This token can't read activity")

    sync.poll(account)
    val limited = FeedRepository(db, InMemoryPreferences(), clock).syncStatus().first().single()
    assertEquals(SyncProblem.Kind.RateLimited, limited.problem?.kind)
    assertEquals(reset.toEpochMilliseconds(), limited.problem?.retryAt)
    assertEquals(reset, sync.dueAt(account))

    sync.poll(account)
    val noAccess = FeedRepository(db, InMemoryPreferences(), clock).syncStatus().first().single()
    assertEquals(SyncProblem.Kind.NoAccess, noAccess.problem?.kind)
  }

  @Test
  fun keepsSsoNoticesFromAPoll() = dbTest { db ->
    val (account, clients) = setUp(db)
    val sync = FeedSync(db, clients, virtualClock())
    feed.polls +=
      FeedPoll.Changed(
        emptyList(),
        "c",
        null,
        1,
        ssoRequired = listOf(SsoAuthorization("corp", "https://github.com/orgs/corp/sso")),
      )

    sync.poll(account)

    val status = FeedRepository(db, InMemoryPreferences(), virtualClock()).syncStatus().first()
    assertEquals(listOf("corp"), status.single().ssoHidden.map { it.organization })
  }

  @Test
  fun refreshDiscardsACursorWithCachedSsoButAutomaticPollingKeepsIt() = dbTest { db ->
    val (account, clients) = setUp(db)
    val sync = FeedSync(db, clients, virtualClock())
    feed.polls +=
      changed()
        .copy(ssoRequired = listOf(SsoAuthorization("corp", "https://github.com/orgs/corp/sso")))
    sync.poll(account)
    sync.poll(account)
    assertEquals(listOf(null, "c1"), feed.cursors)
    assertEquals(
      1,
      FeedRepository(db, InMemoryPreferences(), virtualClock())
        .syncStatus()
        .first()
        .single()
        .ssoHidden
        .size,
    )

    sync.refresh()

    assertEquals(listOf(null, "c1", null), feed.cursors)
    assertNull(db.feed().syncState(account)?.sso)
  }

  @Test
  fun refreshRetriesAndClearsEachStoredPollFailure() {
    val failures =
      listOf(
        ForgeException.RateLimited(START + 1.hours),
        ForgeException.SsoRequired(SsoAuthorization("corp", "https://github.com/orgs/corp/sso")),
        ForgeException.NoAccess("No access"),
        IllegalStateException("Offline"),
      )
    for (failure in failures) dbTest { db ->
      val (account, clients) = setUp(db)
      val sync = FeedSync(db, clients, virtualClock())
      feed.polls += failure
      sync.poll(account)
      assertEquals(failure.message, db.feed().syncState(account)?.lastError)

      sync.refresh()

      val state = db.feed().syncState(account)!!
      assertNull(state.lastError)
      assertNull(state.errorKind)
      assertNull(state.retryAt)
      assertNull(state.sso)
      assertEquals(START + 60.seconds, sync.dueAt(account))
    }
  }

  @Test
  fun refreshBypassesHydrationSsoAndRateLimitBackoff() {
    for (failure in
      listOf(
        ForgeException.RateLimited(START + 1.hours),
        ForgeException.SsoRequired(SsoAuthorization("corp", "https://github.com/orgs/corp/sso")),
      )) dbTest { db ->
      val (account, clients) = setUp(db)
      val sync = FeedSync(db, clients, virtualClock())
      val attempts = hydrator.subjects.size
      hydrator.failures += failure
      feed.polls += changed(pr("1", 7, START), push("2", "abc", START))
      sync.poll(account)
      sync.poll(account)
      assertEquals(attempts + 1, hydrator.subjects.size)

      sync.refresh()

      assertEquals(attempts + 2, hydrator.subjects.size)
      assertEquals("MERGED", db.feed().subject(account, "octo", "hello", 7)?.state)
      assertEquals(
        "Commit abc",
        db.feed().events(account).first { it.id == "2" }.commitList?.single()?.title,
      )
    }
  }

  @Test
  fun refreshRetriesPushesWhoseCommitsWereInaccessible() = dbTest { db ->
    val (account, clients) = setUp(db)
    val sync = FeedSync(db, clients, virtualClock())
    hydrator.missingCommits = true
    feed.polls += changed(push("1", "abc", START))
    sync.poll(account)
    sync.poll(account)
    assertEquals(1, hydrator.pushes.size)
    assertEquals(true, db.feed().events(account).single().pushHydrated)
    assertNull(db.feed().events(account).single().commitList)
    hydrator.missingCommits = false

    sync.refresh()

    assertEquals(2, hydrator.pushes.size)
    assertEquals("Commit abc", db.feed().events(account).single().commitList?.single()?.title)
    sync.refresh()
    assertEquals(2, hydrator.pushes.size)
  }

  @Test
  fun roomAndMutesKeepMatchingIdsAndNamesOnDifferentInstancesSeparate() = dbTest { db ->
    val (first, firstClients) = setUp(db)
    val (second, secondClients) =
      setUp(
        db,
        Instance(ForgeKind.GitHub, "https://git.example.com", "https://git.example.com/api/v3"),
      )
    for ((account, clients) in listOf(first to firstClients, second to secondClients)) {
      feed.polls += changed(pr("1", 7, START), push("2", "abc", START), cursor = "cursor-$account")
      FeedSync(db, clients, virtualClock()).poll(account)
      assertEquals(2, db.feed().events(account).size)
      assertEquals("MERGED", db.feed().subject(account, "octo", "hello", 7)?.state)
      assertEquals("cursor-$account", db.feed().syncState(account)?.cursor)
    }
    val repository = FeedRepository(db, InMemoryPreferences(), virtualClock())
    assertEquals(4, repository.feed(100).first().items.size)
    val repoMute = repository.mute(first, MuteKind.Repo, "octo/hello")
    assertEquals(setOf(second), repository.feed(100).first().items.map { it.accountId }.toSet())
    repoMute.undo()
    val actorMute = repository.mute(first, MuteKind.Actor, "alice")
    assertEquals(setOf(second), repository.feed(100).first().items.map { it.accountId }.toSet())
    actorMute.undo()
    assertEquals(4, repository.feed(100).first().items.size)
    db.feed().pruneEvents(first, 0)
    db.feed().pruneSubjects(first)
    assertEquals(2, db.feed().events(second).size)
    assertEquals("MERGED", db.feed().subject(second, "octo", "hello", 7)?.state)
  }

  @Test
  fun pollsNoMoreOftenThanTheForgeAsks() = dbTest { db ->
    val (account, clients) = setUp(db)
    val sync = FeedSync(db, clients, virtualClock())
    feed.polls += changed(pr("1", 7, START))
    val polling = launch { sync.pollWhileActive() }

    runCurrent()
    assertEquals(1, feed.cursors.size)
    advanceTimeBy(59.seconds)
    assertEquals(1, feed.cursors.size)
    advanceTimeBy(2.seconds)
    assertEquals(2, feed.cursors.size)
    polling.cancel()
    assertEquals("c1", db.feed().syncState(account)?.cursor)
  }

  @Test
  fun keepsOnlyTheNewestEvents() = dbTest { db ->
    val (account, _) = setUp(db)
    val dao = db.feed()
    dao.insertEvents((1..10).map { pr("$it", it, START + it.minutes).toEntity(account) })

    dao.pruneEvents(account, keep = 4)

    assertEquals(listOf("10", "9", "8", "7"), dao.events(account).map { it.id })
  }

  @Test
  fun pruningKeepsTheLimitWhenTimestampsTie() = dbTest { db ->
    val (account, _) = setUp(db)
    db.feed().insertEvents((1..10).map { pr("$it", it, START).toEntity(account) })
    db.feed().pruneEvents(account, keep = 4)
    assertEquals(4, db.feed().events(account).size)
  }

  @Test
  fun mutesHideActivityUntilUndone() = dbTest { db ->
    val (account, clients) = setUp(db)
    val sync = FeedSync(db, clients, virtualClock())
    feed.polls += changed(pr("1", 7, START))
    sync.poll(account)
    val repository = FeedRepository(db, InMemoryPreferences(), virtualClock())

    val undo = repository.mute(account, MuteKind.Repo, "Octo/Hello")
    val muted = repository.feed(100).first()
    assertEquals(0, muted.items.size)
    assertEquals(1, muted.mutedEvents)

    undo.undo()
    assertEquals(1, repository.feed(100).first().items.size)
  }

  @Test
  fun remembersTheGrouping() = dbTest { db ->
    val repository = FeedRepository(db, InMemoryPreferences(), virtualClock())
    assertEquals(FeedGrouping.None, repository.grouping.first())
    repository.setGrouping(FeedGrouping.Person)
    assertEquals(FeedGrouping.Person, repository.grouping.first())
  }
}

/** Preferences held in memory, for repositories that keep settings. */
class InMemoryPreferences :
  androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
  private val state =
    kotlinx.coroutines.flow.MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())

  override val data = state

  override suspend fun updateData(
    transform:
      suspend (
        androidx.datastore.preferences.core.Preferences,
      ) -> androidx.datastore.preferences.core.Preferences,
  ) = transform(state.value).also { state.value = it }
}
