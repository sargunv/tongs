package dev.sargunv.tongs.feature.thread

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.sargunv.tongs.core.navigation.LocalNavigator
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.core.navigation.RouteEntries
import dev.sargunv.tongs.core.navigation.isBesideList
import dev.sargunv.tongs.core.navigation.route
import dev.sargunv.tongs.forge.api.SubjectType
import dev.sargunv.tongs.forge.api.ThreadKind
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject

@ContributesIntoSet(AppScope::class)
@Inject
class ThreadEntries(
  private val threads: ThreadViewModel.Factory,
  private val replies: ReplyViewModel.Factory,
) : RouteEntries {
  override fun EntryProviderScope<NavKey>.install() {
    route<Route.Thread> { route ->
      val navigator = LocalNavigator.current
      ThreadScreen(
        viewModel =
          viewModel(key = route.toString()) {
            threads.create(route) { navigator.open(Route.ThreadReply(route)) }
          },
        onBack = if (isBesideList()) null else ({ navigator.back() }),
        // Shown only once the thread loads as a PR: a link to `/issues/N` may be one.
        onOpenChanges =
          route.number?.let { number ->
            { navigator.open(Route.PullRequestChanges(route.accountId, route.repo, number)) }
          },
        onOpenLink = { link ->
          val type =
            if (link.kind == ThreadKind.PullRequest) SubjectType.PullRequest else SubjectType.Issue
          navigator.push(Route.Thread(route.accountId, link.repo, type.raw, link.number))
        },
      )
    }
    route<Route.ThreadReply> { route ->
      val navigator = LocalNavigator.current
      ReplyScreen(
        viewModel = viewModel(key = route.toString()) { replies.create(route) },
        onClose = { if (navigator.current == route) navigator.back() },
      )
    }
  }
}
