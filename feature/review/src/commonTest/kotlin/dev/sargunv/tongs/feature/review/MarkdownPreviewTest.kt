package dev.sargunv.tongs.feature.review

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownPreviewTest {
  @Test
  fun keepsTheWordsOfABotsBadgeAndLinks() {
    assertEquals(
      "P2 Badge Apply provider routes\n\nSee the docs.",
      markdownPreview(
        "**<sub><sub>![P2 Badge](https://img.shields.io/badge/P2-yellow)</sub></sub> " +
          "Apply provider routes**\n\n\n\nSee [the docs](https://example.com).",
      ),
    )
  }

  @Test
  fun dropsFencesButKeepsCode() {
    assertEquals("Try:\n\nfoo()", markdownPreview("Try:\n```suggestion\nfoo()\n```"))
  }
}
