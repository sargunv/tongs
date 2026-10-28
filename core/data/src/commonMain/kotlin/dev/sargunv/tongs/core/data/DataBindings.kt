package dev.sargunv.tongs.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.Path.Companion.toPath

/** The data layer's part of the app graph. The platform shell supplies the [AppEnvironment]. */
@ContributesTo(AppScope::class)
interface DataBindings {
  @Provides
  @SingleIn(AppScope::class)
  fun database(environment: AppEnvironment): TongsDatabase =
    openDatabase("${environment.dataDir}/tongs.db")

  @Provides
  @SingleIn(AppScope::class)
  fun preferences(environment: AppEnvironment): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath {
      "${environment.dataDir}/settings.preferences_pb".toPath()
    }

  @Provides fun tokenStore(environment: AppEnvironment): TokenStore = environment.tokenStore

  @Provides fun clock(): Clock = Clock.System

  /** For work that outlives a screen, like a forge call behind an optimistic update. */
  @Provides
  @SingleIn(AppScope::class)
  fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
