package dev.sargunv.tongs.core.net

import kotlin.test.Test
import kotlin.test.assertEquals

class ConditionalTest {
  @Test
  fun parsesLinkHeaders() {
    val header =
      """<https://api.github.com/notifications?page=2>; rel="next", """ +
        """<https://api.github.com/notifications?page=5>; rel="last""""
    assertEquals(
      mapOf(
        "next" to "https://api.github.com/notifications?page=2",
        "last" to "https://api.github.com/notifications?page=5",
      ),
      parseLinks(header),
    )
    assertEquals(emptyMap(), parseLinks(null))
  }
}
