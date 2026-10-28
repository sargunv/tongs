package dev.sargunv.tongs.app

import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.forge.api.RepoRef
import kotlinx.coroutines.flow.first

private val SHORT = Regex("""([\w.-]+)/([\w.-]+)#(\d+)""")
private val URL = Regex("""(?:https?://)?[\w.-]+/([\w.-]+)/([\w.-]+)/pull/(\d+).*""")

/**
 * The back stack that opens a PR's changes from `owner/repo#N` or a PR link, for the first account,
 * over the inbox. Null if [link] isn't a PR or there's no account. A stopgap for opening links,
 * until links resolve to their instance and account.
 */
suspend fun AppGraph.changesBackStack(link: String): List<Route>? {
  val match = SHORT.matchEntire(link.trim()) ?: URL.matchEntire(link.trim()) ?: return null
  val (owner, name, number) = match.destructured
  val account = accounts.accounts.first().firstOrNull()?.account ?: return null
  return listOf(
    Route.Inbox,
    Route.PullRequestChanges(account.id.value, RepoRef(owner, name), number.toInt()),
  )
}
