package dev.sargunv.tongs.core.navigation

import androidx.navigation3.runtime.NavKey
import dev.sargunv.tongs.forge.api.RepoRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json

class ThreadNavigationTest {
  private val repo = RepoRef("octo", "hello")

  private fun thread(number: Int) = Route.Thread(1, repo, "Issue", number)

  @Test
  fun linksStackThreadsSoBackReturns() {
    val stack = mutableListOf<NavKey>(Route.Inbox, thread(1))
    val navigator = Navigator(stack)
    navigator.push(thread(2))
    assertEquals(listOf<NavKey>(Route.Inbox, thread(1), thread(2)), stack)
    navigator.back()
    assertEquals(thread(1), navigator.current)
  }

  @Test
  fun aReplyOpensBesideItsThreadAndLinksLeaveIt() {
    val stack = mutableListOf<NavKey>(Route.Inbox, thread(1))
    val navigator = Navigator(stack)
    navigator.open(Route.ThreadReply(thread(1)))
    navigator.open(Route.ThreadReply(thread(1)))
    assertEquals(listOf<NavKey>(Route.Inbox, thread(1), Route.ThreadReply(thread(1))), stack)

    navigator.push(thread(2))
    assertEquals(listOf<NavKey>(Route.Inbox, thread(1), thread(2)), stack)
  }

  @Test
  fun replyRoutesRoundTrip() {
    val json = Json { serializersModule = RouteSerializers }
    val serializer = PolymorphicSerializer(NavKey::class)
    val route = Route.ThreadReply(thread(3))
    assertEquals<NavKey>(
      route,
      json.decodeFromString(serializer, json.encodeToString(serializer, route)),
    )
  }
}
