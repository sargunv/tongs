package dev.sargunv.tongs.core.markdown

import dev.sargunv.tongs.core.markdown.SuggestionLine.Kind.Added
import dev.sargunv.tongs.core.markdown.SuggestionLine.Kind.Context
import dev.sargunv.tongs.core.markdown.SuggestionLine.Kind.Removed
import kotlin.test.Test
import kotlin.test.assertEquals

class SuggestionsTest {
  @Test
  fun sharedLinesAtEitherEndAreContext() {
    val base = "fun a() {\n  old()\n}"
    assertEquals(
      listOf(
        SuggestionLine(Context, "fun a() {"),
        SuggestionLine(Removed, "  old()"),
        SuggestionLine(Added, "  new()"),
        SuggestionLine(Added, "  more()"),
        SuggestionLine(Context, "}"),
      ),
      suggestionDiff(base, "fun a() {\n  new()\n  more()\n}\n"),
    )
  }

  @Test
  fun withoutABaseEveryLineIsAdded() {
    assertEquals(listOf(SuggestionLine(Added, "x")), suggestionDiff(null, "x\n"))
  }

  @Test
  fun deletingTheLinesRemovesThemAll() {
    assertEquals(
      listOf(SuggestionLine(Removed, "a"), SuggestionLine(Removed, "b")),
      suggestionDiff("a\nb", ""),
    )
  }
}
