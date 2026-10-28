package dev.sargunv.tongs.forge.github

import dev.sargunv.tongs.core.net.createHttpClient
import dev.sargunv.tongs.forge.api.InboxPoll
import dev.sargunv.tongs.forge.api.Instance
import kotlin.test.Test
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking

/**
 * Polls and hydrates the real inbox of `GH_TOKEN`'s user, read-only, and prints each row and what
 * hydration cost. Runs only with `TONGS_LIVE_TESTS=1`: `TONGS_LIVE_TESTS=1 GH_TOKEN=$(gh auth
 * token) ./gradlew :forge:github:jvmTest --tests '*Live*' -i`
 */
class LiveInboxTest {
  @Test
  fun hydratesTheRealInbox() = runBlocking {
    if (System.getenv("TONGS_LIVE_TESTS") != "1") return@runBlocking
    val token = checkNotNull(System.getenv("GH_TOKEN")) { "Set GH_TOKEN" }
    val login = System.getenv("GH_LOGIN") ?: "sargunv"
    val http = createHttpClient()
    val poll = GitHubNotifications(http, Instance.GitHubDotCom, token).poll(null)
    check(poll is InboxPoll.Changed)
    println("Polled ${poll.threads.size} threads, complete=${poll.complete}")
    val hydrator = GitHubHydrator(http, Instance.GitHubDotCom, token, login)
    val start = TimeSource.Monotonic.markNow()
    val hydration = hydrator.hydrate(poll.threads)
    println(
      "Hydrated ${hydration.threads.size} threads in ${start.elapsedNow()}, " +
        "cost ${hydration.cost} GraphQL points",
    )
    val byId = hydration.threads.associateBy { it.threadId }
    for (thread in poll.threads) {
      val h = byId[thread.id]
      println(
        "${thread.repo}#${thread.subject.number} [${thread.reason.raw}] " +
          "${h?.subjectState}${if (h?.isDraft == true) " draft" else ""} " +
          "checks=${h?.checks} review=${h?.reviewDecision} :: ${h?.whatsNew}",
      )
    }
  }
}
