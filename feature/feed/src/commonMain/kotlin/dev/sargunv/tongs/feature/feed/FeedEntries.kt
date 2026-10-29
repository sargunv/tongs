package dev.sargunv.tongs.feature.feed

import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.sargunv.tongs.core.data.FeedItem
import dev.sargunv.tongs.core.navigation.LocalNavigator
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.core.navigation.RouteEntries
import dev.sargunv.tongs.core.navigation.route
import dev.sargunv.tongs.core.ui.EmptyState
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider

/**
 * The activity feed, a list whose issues and PRs open beside it in the thread screen. Everything
 * else opens in the browser until its screen exists: a push's compare (the changes screen is a
 * PR's, with threads and reviews), repos, people, releases.
 */
@ContributesIntoSet(AppScope::class)
@Inject
class FeedEntries(private val viewModel: Provider<FeedViewModel>) : RouteEntries {
  override fun EntryProviderScope<NavKey>.install() {
    route<Route.Activity>(
      detailPlaceholder = {
        EmptyState(
          TongsIcons.Activity,
          "Nothing open",
          body = "Pick an issue or pull request to read it here.",
        )
      },
    ) {
      val navigator = LocalNavigator.current
      val uri = LocalUriHandler.current
      FeedScreen(
        viewModel = viewModel { viewModel() },
        selected = { item ->
          val open = navigator.current as? Route.Thread
          val thread = threadRoute(item)
          open != null && thread != null && open.copy(notificationId = null) == thread
        },
        onOpen = { item ->
          val thread = threadRoute(item)
          if (thread != null) navigator.open(thread) else item.webUrl?.let(uri::openUri)
        },
        onSignIn = { navigator.open(Route.SignIn) },
      )
    }
  }
}

/** The thread screen's route for an issue or PR item; null for anything else. */
internal fun threadRoute(item: FeedItem): Route.Thread? {
  if (item !is FeedItem.Subject) return null
  val type = item.subject.type
  if (type != "PullRequest" && type != "Issue") return null
  return Route.Thread(item.accountId, item.repo, type, item.subject.number)
}
