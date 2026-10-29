package dev.sargunv.tongs.forge.github

import dev.sargunv.tongs.core.net.createHttpClient
import dev.sargunv.tongs.forge.api.FeedEventKind
import dev.sargunv.tongs.forge.api.FeedPoll
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.PushRef
import dev.sargunv.tongs.forge.api.SubjectRef
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Polls and hydrates the real activity feed of `GH_TOKEN`'s user, read-only, and prints what each
 * step cost: REST requests that counted against the rate limit, and GraphQL points. Then polls
 * again with the cursor, at once and after `TONGS_FEED_WAIT` seconds (default 0). Runs only with
 * `TONGS_LIVE_TESTS=1`: `TONGS_LIVE_TESTS=1 GH_TOKEN=$(gh auth token) ./gradlew
 * :forge:github:jvmTest --tests '*LiveFeed*' -i`
 */
class LiveFeedTest {
  @Test
  fun pollsAndHydratesTheRealFeed() = runBlocking {
    if (System.getenv("TONGS_LIVE_TESTS") != "1") return@runBlocking
    val token = checkNotNull(System.getenv("GH_TOKEN")) { "Set GH_TOKEN" }
    val login = System.getenv("GH_LOGIN") ?: "sargunv"
    val http = createHttpClient()
    val source = GitHubEvents(http, Instance.GitHubDotCom, token, login)
    var start = TimeSource.Monotonic.markNow()
    val cold = source.poll(null)
    check(cold is FeedPoll.Changed)
    println(
      "Cold poll: ${cold.events.size} events in ${start.elapsedNow()}, ${cold.requests} REST " +
        "requests, poll interval ${cold.pollInterval}, SSO-hidden orgs ${cold.ssoRequired.map { it.organization }}",
    )
    println("By kind: " + cold.events.groupingBy { it.kind }.eachCount())

    val subjects =
      cold.events.mapNotNull { e -> e.subject?.let { SubjectRef(e.repo, it.number) } }.distinct()
    val pushes =
      cold.events
        .filter { it.kind == FeedEventKind.Push && it.head != null }
        .map { PushRef(it.repo, it.ref, it.before, it.head!!) }
        .distinct()
    val hydrator = GitHubFeedHydrator(http, Instance.GitHubDotCom, token)
    start = TimeSource.Monotonic.markNow()
    // As sync does on a cold start: 100 subjects and 50 pushes a poll.
    val hydration = hydrator.hydrate(subjects.take(100), pushes.take(50))
    println(
      "Hydrated ${hydration.subjects.size} of ${subjects.size} subjects and " +
        "${hydration.pushes.size} of ${pushes.size} pushes in ${start.elapsedNow()}: " +
        "${hydration.points} GraphQL points, ${hydration.requests} compare requests",
    )
    val placed = hydration.pushes.count { it.count != null }
    println(
      "Pushes with commits: $placed; ${hydration.pushes.count { it.commits == null }} unknown",
    )
    for (p in hydration.pushes.take(8)) {
      println(
        "  ${p.push.repo}@${p.push.ref}: ${p.count} commits, ${p.commits?.firstOrNull()?.title}",
      )
    }
    for (s in hydration.subjects.take(8)) {
      println("  ${s.subject.repo}#${s.subject.number} ${s.type?.raw} ${s.state} ${s.title}")
    }

    start = TimeSource.Monotonic.markNow()
    val again = source.poll(cold.cursor)
    println(
      "Poll with cursor at once: ${again::class.simpleName}, ${again.requests} REST requests in ${start.elapsedNow()}",
    )
    val wait = System.getenv("TONGS_FEED_WAIT")?.toIntOrNull() ?: 0
    if (wait > 0) {
      delay(wait.seconds)
      val cursor = (again as? FeedPoll.Changed)?.cursor ?: cold.cursor
      val later = source.poll(cursor)
      val events = (later as? FeedPoll.Changed)?.events.orEmpty()
      println(
        "Poll after ${wait}s: ${later::class.simpleName}, ${later.requests} REST requests, ${events.size} events",
      )
    }
  }
}
