package dev.sargunv.tongs.core.markdown

import dev.sargunv.tongs.forge.api.RepoRef
import kotlin.test.Test
import kotlin.test.assertEquals

class ReferencesTest {
  private val context = MarkdownContext("https://github.com", RepoRef("octo", "hello"))

  private fun linkify(text: String) = linkifyReferences(text, context)

  @Test
  fun linksIssueReferencesInProse() {
    assertEquals(
      "Fixes [#12](https://github.com/octo/hello/issues/12), see [#3](https://github.com/octo/hello/issues/3).",
      linkify("Fixes #12, see #3."),
    )
  }

  @Test
  fun linksAtLineStartAndInEmphasis() {
    assertEquals(
      "[#7](https://github.com/octo/hello/issues/7) is *[#8](https://github.com/octo/hello/issues/8)* and **[#9](https://github.com/octo/hello/issues/9)**",
      linkify("#7 is *#8* and **#9**"),
    )
  }

  @Test
  fun linksCrossRepoReferences() {
    assertEquals(
      "Blocked on [maplibre/maplibre-native#123](https://github.com/maplibre/maplibre-native/issues/123)",
      linkify("Blocked on maplibre/maplibre-native#123"),
    )
  }

  @Test
  fun linksMentionsAndTeams() {
    assertEquals(
      "cc [@sargunv](https://github.com/sargunv) and [@octo-org/core](https://github.com/orgs/octo-org/teams/core)!",
      linkify("cc @sargunv and @octo-org/core!"),
    )
  }

  @Test
  fun leavesEmailsAlone() {
    assertEquals("mail user@example.com", linkify("mail user@example.com"))
  }

  @Test
  fun linksShasButNotNumbersOrWords() {
    assertEquals(
      "In [abc1234](https://github.com/octo/hello/commit/abc1234def5678), not 1234567 or deadbeef or facade1x",
      linkify("In abc1234def5678, not 1234567 or deadbeef or facade1x"),
    )
    assertEquals(
      "[octo/other@0a1b2c3](https://github.com/octo/other/commit/0a1b2c3d)",
      linkify("octo/other@0a1b2c3d"),
    )
  }

  @Test
  fun leavesCodeAlone() {
    assertEquals("`#12 @x abc1234` and", linkify("`#12 @x abc1234` and"))
    val fence = "```\n#12 @user\n```"
    assertEquals(fence, linkify(fence))
    val indented = "Text\n\n    #12 @user\n"
    assertEquals(indented, linkify(indented))
  }

  @Test
  fun leavesLinksAndUrlsAlone() {
    assertEquals("[#10](https://example.com) here", linkify("[#10](https://example.com) here"))
    assertEquals(
      "https://github.com/a/b/pull/1#issuecomment-12",
      linkify("https://github.com/a/b/pull/1#issuecomment-12"),
    )
    assertEquals("<https://x.dev/#12>", linkify("<https://x.dev/#12>"))
    assertEquals("![#1](a.png)", linkify("![#1](a.png)"))
  }

  @Test
  fun leavesHeadingMarkersAlone() {
    assertEquals(
      "# Title [#12](https://github.com/octo/hello/issues/12)\n\n## 12",
      linkify("# Title #12\n\n## 12"),
    )
  }

  @Test
  fun leavesEscapesAndEntitiesAlone() {
    assertEquals("&#123; and \\#4", linkify("&#123; and \\#4"))
  }

  @Test
  fun linksInTablesAndLists() {
    assertEquals(
      "| a |\n|---|\n| [#1](https://github.com/octo/hello/issues/1) |\n\n- [ ] [@x](https://github.com/x)",
      linkify("| a |\n|---|\n| #1 |\n\n- [ ] @x"),
    )
  }

  @Test
  fun withoutARepoOnlyQualifiedReferencesLink() {
    val context = MarkdownContext("https://github.com", repo = null)
    assertEquals(
      "#1 abc1234 [a/b#2](https://github.com/a/b/issues/2)",
      linkifyReferences("#1 abc1234 a/b#2", context),
    )
  }

  @Test
  fun leavesTagsAndCommentsAloneButLinksTextBetweenTags() {
    assertEquals(
      "Press <kbd>[#1](https://github.com/octo/hello/issues/1)</kbd>",
      linkify("Press <kbd>#1</kbd>"),
    )
    assertEquals("<!-- #1 @x -->", linkify("<!-- #1 @x -->"))
    assertEquals("<a href=\"#12\">x</a> y", linkify("<a href=\"#12\">x</a> y"))
  }
}
