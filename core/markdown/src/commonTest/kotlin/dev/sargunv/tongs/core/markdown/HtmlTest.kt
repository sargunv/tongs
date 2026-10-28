package dev.sargunv.tongs.core.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlTest {
  private val context = MarkdownContext("https://github.com", repo = null)

  private fun prepare(text: String) = prepareForgeMarkdown(text, context)

  @Test
  fun dropsComments() {
    assertEquals("Intro\n\n\nBody", prepare("Intro\n\n<!-- Describe your change -->\nBody"))
  }

  @Test
  fun turnsSummaryIntoBoldText() {
    assertEquals(
      "**Logs**\n\nline **one**\n",
      prepare("<details><summary>Logs</summary>\n\nline **one**\n</details>"),
    )
  }

  @Test
  fun turnsImagesIntoMarkdownImages() {
    assertEquals(
      "![shot](https://x/y.png)",
      prepare("<p align=\"center\"><img width=\"300\" alt=\"shot\" src=\"https://x/y.png\"></p>"),
    )
    assertEquals("See ![](a.png) here", prepare("See <img src='a.png'/> here"))
  }

  @Test
  fun keepsTextAndBreaksLines() {
    assertEquals("Line\\\ntwo **Ctrl**", prepare("Line<br>two <kbd>**Ctrl**</kbd>"))
    assertEquals("| a |\n|---|\n| x y |", prepare("| a |\n|---|\n| x<br>y |"))
  }

  @Test
  fun normalizesLineEndings() {
    assertEquals(
      "Before\n![image](a.png)\n\nAfter",
      prepare("Before\r\n<img alt=\"image\" src=\"a.png\">\r\n\r\nAfter"),
    )
  }

  @Test
  fun keepsLinks() {
    assertEquals(
      "[Docs](https://d.dev)\\\nmore",
      prepare("<div><a href=\"https://d.dev\">Docs</a><br/>more</div>"),
    )
  }
}
