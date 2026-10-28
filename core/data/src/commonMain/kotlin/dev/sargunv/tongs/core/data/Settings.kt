package dev.sargunv.tongs.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What swiping an inbox row does. */
enum class SwipeAction {
  Done,
  Snooze,
  /** Read if it's unread, unread if it's read. */
  ToggleRead,
  None,
}

/** Swipe actions by the direction the finger moves, whatever the layout direction. */
data class InboxSettings(
  val swipeLeft: SwipeAction = SwipeAction.Done,
  val swipeRight: SwipeAction = SwipeAction.Snooze,
)

/** User preferences, in a DataStore beside the database. */
@SingleIn(AppScope::class)
@Inject
class SettingsRepository(private val store: DataStore<Preferences>) {
  val inbox: Flow<InboxSettings> =
    store.data.map { prefs ->
      val defaults = InboxSettings()
      InboxSettings(
        swipeLeft = prefs[SWIPE_LEFT].toSwipeAction() ?: defaults.swipeLeft,
        swipeRight = prefs[SWIPE_RIGHT].toSwipeAction() ?: defaults.swipeRight,
      )
    }

  suspend fun setSwipeLeft(action: SwipeAction) {
    store.edit { it[SWIPE_LEFT] = action.name }
  }

  suspend fun setSwipeRight(action: SwipeAction) {
    store.edit { it[SWIPE_RIGHT] = action.name }
  }

  private fun String?.toSwipeAction() = SwipeAction.entries.find { it.name == this }

  private companion object {
    val SWIPE_LEFT = stringPreferencesKey("inbox.swipeLeft")
    val SWIPE_RIGHT = stringPreferencesKey("inbox.swipeRight")
  }
}
