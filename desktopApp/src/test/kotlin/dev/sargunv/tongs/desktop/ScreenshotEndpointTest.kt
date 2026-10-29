package dev.sargunv.tongs.desktop

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ScreenshotEndpointTest {
  @Test
  fun anExistingRealAccountCannotMutateEvenWithAFakeUrlArgument() {
    assertFailsWith<IllegalArgumentException> {
      requireScreenshotMutationEndpoint(true, "http://127.0.0.1:8787", "https://api.github.com")
    }
    assertFailsWith<IllegalArgumentException> {
      requireScreenshotMutationEndpoint(true, "https://api.github.com", "https://api.github.com")
    }
    requireScreenshotMutationEndpoint(true, "http://127.0.0.1:8787", "http://127.0.0.1:8787")
    requireScreenshotMutationEndpoint(false, null, "https://api.github.com")
  }
}
