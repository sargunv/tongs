package dev.sargunv.tongs.forge.github

import dev.sargunv.tongs.engine.FetchOptions
import dev.sargunv.tongs.engine.FetchSession
import dev.sargunv.tongs.engine.ReviewLoader
import dev.sargunv.tongs.forge.api.Account
import dev.sargunv.tongs.forge.api.Capability
import dev.sargunv.tongs.forge.api.ForgeAdapter
import dev.sargunv.tongs.forge.api.ForgeClient
import dev.sargunv.tongs.forge.api.ForgeKind
import dev.sargunv.tongs.forge.api.ForgeKindKey
import dev.sargunv.tongs.forge.api.InboxHydrator
import dev.sargunv.tongs.forge.api.InboxSource
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.ReviewSource
import dev.sargunv.tongs.forge.api.ThreadSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import io.ktor.client.HttpClient

/** GitHub and GitHub Enterprise Server. */
@ContributesIntoMap(AppScope::class)
@ForgeKindKey(ForgeKind.GitHub)
@Inject
class GitHubAdapter(private val http: HttpClient) : ForgeAdapter {
  override val kind = ForgeKind.GitHub
  override val auth = GitHubAuth(http)

  override fun client(account: Account, token: String): ForgeClient =
    GitHubClient(http, account, token)
}

private class GitHubClient(http: HttpClient, account: Account, token: String) : ForgeClient {
  override val inbox: InboxSource? =
    if (Capability.Inbox in account.capabilities) {
      GitHubNotifications(http, account.instance, token)
    } else null

  override val inboxHydrator: InboxHydrator? =
    if (inbox != null) GitHubHydrator(http, account.instance, token, account.login) else null

  override val threads: ThreadSource = GitHubThreads(http, account.instance, token)

  override val reviews: ReviewSource =
    object : ReviewSource {
      private val github =
        GitHub(http, token, apiUrl = account.instance.apiUrl, webUrl = account.instance.webUrl)

      override suspend fun loadPullRequest(
        repo: RepoRef,
        number: Int,
        openSession: (FetchOptions) -> FetchSession,
        onLoader: (ReviewLoader) -> Unit,
        log: (String) -> Unit,
      ) =
        loadPullRequestReview(
          github,
          PullRequestRef(repo.owner, repo.name, number),
          openSession,
          onLoader,
          log,
        )
    }

  // The HTTP client is the app's, shared by every account.
  override fun close() {}
}
