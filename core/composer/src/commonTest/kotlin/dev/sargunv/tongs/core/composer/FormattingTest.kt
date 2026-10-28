package dev.sargunv.tongs.core.composer

import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

class FormattingTest {
  /** Parses `|` as the caret and `[...]` as a selection. */
  private fun edit(marked: String): TextEdit {
    val caret = marked.indexOf('|')
    if (caret >= 0) return TextEdit(marked.removeRange(caret, caret + 1), TextRange(caret))
    val start = marked.indexOf('[')
    val end = marked.indexOf(']') - 1
    return TextEdit(marked.replace("[", "").replace("]", ""), TextRange(start, end))
  }

  private fun TextEdit.marked(): String =
    if (selection.collapsed)
      text.substring(0, selection.start) + "|" + text.substring(selection.start)
    else
      text.substring(0, selection.min) +
        "[" +
        text.substring(selection.min, selection.max) +
        "]" +
        text.substring(selection.max)

  @Test
  fun boldWrapsTheSelectionAndUnwrapsIt() {
    val bold = MarkdownFormatting.wrap(edit("make [this] bold"), "**", "bold")
    assertEquals("make **[this]** bold", bold.marked())
    assertEquals("make [this] bold", MarkdownFormatting.wrap(bold, "**", "bold").marked())
  }

  @Test
  fun boldWithNothingSelectedInsertsAPlaceholder() {
    assertEquals("so **[bold]**", MarkdownFormatting.wrap(edit("so |"), "**", "bold").marked())
  }

  @Test
  fun codeUsesAFenceAcrossLines() {
    assertEquals("run `[gradle]`", MarkdownFormatting.code(edit("run [gradle]")).marked())
    assertEquals(
      "See:\n```\n[a()\nb()]\n```\n",
      MarkdownFormatting.code(edit("See:\n[a()\nb()]")).marked(),
    )
  }

  @Test
  fun linkSelectsTheUrl() {
    assertEquals("see [docs]([url])", MarkdownFormatting.link(edit("see [docs]")).marked())
  }

  @Test
  fun prefixesEveryLineTouchedAndToggles() {
    val listed = MarkdownFormatting.prefixLines(edit("intro\n[one\ntwo]"), "- ")
    assertEquals("intro\n[- one\n- two]", listed.marked())
    assertEquals("intro\n[one\ntwo]", MarkdownFormatting.prefixLines(listed, "- ").marked())
  }

  @Test
  fun numbersLines() {
    assertEquals("[1. a\n2. b]", MarkdownFormatting.prefixLines(edit("[a\nb]"), "1. ").marked())
    assertEquals("[a\nb]", MarkdownFormatting.prefixLines(edit("[1. a\n2. b]"), "1. ").marked())
  }

  @Test
  fun quotingACaretLineKeepsTheCaret() {
    assertEquals("> hello|", MarkdownFormatting.prefixLines(edit("hello|"), "> ").marked())
    assertEquals("a\n> b|", MarkdownFormatting.prefixLines(edit("a\nb|"), "> ").marked())
  }

  @Test
  fun quotesAReply() {
    assertEquals(
      "@mona wrote:\n\n> First\n>\n> Second\n\n",
      MarkdownFormatting.quote("First\n\nSecond\n", "mona"),
    )
  }

  @Test
  fun quotingDropsCommentsAndExtraBlankLines() {
    assertEquals(
      "> One\n>\n> Two\n\n",
      MarkdownFormatting.quote("<!-- template hint -->\r\nOne\r\n\r\n\r\n\r\nTwo  \r\n"),
    )
  }
}
