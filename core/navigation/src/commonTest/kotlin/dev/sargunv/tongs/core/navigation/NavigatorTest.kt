package dev.sargunv.tongs.core.navigation

import androidx.navigation3.runtime.NavKey
import dev.sargunv.tongs.forge.api.RepoRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json

class NavigatorTest {
  private val repo = RepoRef("octo", "hello")

  private fun thread(id: String) = Route.Thread(1, repo, "PullRequest", 4, notificationId = id)

  @Test
  fun detailsReplaceEachOther() {
    val stack = mutableListOf<NavKey>(Route.Inbox)
    val navigator = Navigator(stack)
    navigator.open(thread("a"))
    navigator.open(thread("b"))
    assertEquals(listOf<NavKey>(Route.Inbox, thread("b")), stack)

    navigator.open(Route.PullRequestChanges(1, repo, 4))
    assertEquals(3, stack.size)
    navigator.back()
    navigator.back()
    assertEquals(false, navigator.back())
    assertEquals(listOf<NavKey>(Route.Inbox), stack)
  }

  @Test
  fun topLevelSwitchesDropTheStack() {
    val stack = mutableListOf<NavKey>(Route.Inbox, thread("a"))
    val navigator = Navigator(stack)
    navigator.selectTopLevel(Route.Settings)
    assertEquals(listOf<NavKey>(Route.Settings), stack)
    assertEquals(Route.Settings, navigator.topLevel)
  }

  @Test
  fun routesRoundTripThroughSerialization() {
    val json = Json { serializersModule = RouteSerializers }
    val serializer = PolymorphicSerializer(NavKey::class)
    for (route in
      listOf(Route.Inbox, Route.Settings, thread("a"), Route.PullRequestChanges(1, repo, 4))) {
      assertEquals<NavKey>(
        route,
        json.decodeFromString(serializer, json.encodeToString(serializer, route)),
      )
    }
  }
}
