package dev.sargunv.tongs.app

import dev.sargunv.tongs.core.data.AccountRepository
import dev.sargunv.tongs.core.data.AppEnvironment
import dev.sargunv.tongs.core.data.FeedSync
import dev.sargunv.tongs.core.data.InboxRepository
import dev.sargunv.tongs.core.data.InboxSync
import dev.sargunv.tongs.core.navigation.RouteEntries
import dev.sargunv.tongs.core.net.createHttpClient
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory
import io.ktor.client.HttpClient

/**
 * The app's dependency graph. Modules contribute to [AppScope]: features their [RouteEntries],
 * forge adapters their `ForgeAdapter`s, and the data layer its stores. The platform shell supplies
 * only the [AppEnvironment].
 */
@DependencyGraph(AppScope::class)
interface AppGraph {
  val routeEntries: Set<RouteEntries>
  val accounts: AccountRepository
  val inbox: InboxRepository
  val inboxSync: InboxSync
  val feedSync: FeedSync
  val environment: AppEnvironment
  val httpClient: HttpClient

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides environment: AppEnvironment): AppGraph
  }
}

fun createAppGraph(environment: AppEnvironment): AppGraph =
  createGraphFactory<AppGraph.Factory>().create(environment)

@ContributesTo(AppScope::class)
interface NetBindings {
  /** One HTTP client for every account and forge. */
  @Provides @SingleIn(AppScope::class) fun httpClient(): HttpClient = createHttpClient()
}
