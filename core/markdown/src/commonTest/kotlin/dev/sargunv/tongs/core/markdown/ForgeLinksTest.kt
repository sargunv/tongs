package dev.sargunv.tongs.core.markdown

import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.ThreadKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForgeLinksTest {
  private val web = "https://github.com"

  @Test
  fun parsesThreads() {
    val repo = RepoRef("octo", "hello")
    assertEquals(
      ForgeLink.Thread(repo, 12, ThreadKind.PullRequest),
      parseForgeLink(web, "https://github.com/octo/hello/pull/12"),
    )
    assertEquals(
      ForgeLink.Thread(repo, 12, ThreadKind.PullRequest),
      parseForgeLink(web, "https://github.com/octo/hello/pull/12/files#diff-abc"),
    )
    assertEquals(
      ForgeLink.Thread(repo, 7, kind = null),
      parseForgeLink(web, "https://github.com/octo/hello/issues/7#issuecomment-99"),
    )
    assertEquals(
      ForgeLink.Thread(repo, 7, kind = null),
      parseForgeLink(web, "http://github.com/octo/hello/issues/7?x=1"),
    )
  }

  @Test
  fun parsesCommitsAndUsers() {
    assertEquals(
      ForgeLink.Commit(RepoRef("octo", "hello"), "abc1234"),
      parseForgeLink(web, "https://github.com/octo/hello/commit/abc1234"),
    )
    assertEquals(ForgeLink.User("sargunv"), parseForgeLink(web, "https://github.com/sargunv"))
  }

  @Test
  fun ignoresOtherPagesAndHosts() {
    assertNull(parseForgeLink(web, "https://github.com/octo/hello"))
    assertNull(parseForgeLink(web, "https://github.com/octo/hello/issues"))
    assertNull(parseForgeLink(web, "https://github.com/settings"))
    assertNull(parseForgeLink(web, "https://github.com/orgs/octo/teams/core"))
    assertNull(parseForgeLink(web, "https://github.com/user-attachments/assets/1234"))
    assertNull(parseForgeLink(web, "https://gitlab.com/octo/hello/issues/1"))
    assertNull(parseForgeLink(web, "https://github.company.com/octo/hello/pull/1"))
  }

  @Test
  fun parsesOtherInstances() {
    assertEquals(
      ForgeLink.Thread(RepoRef("forgejo", "forgejo"), 5, ThreadKind.PullRequest),
      parseForgeLink("https://codeberg.org/", "https://codeberg.org/forgejo/forgejo/pulls/5"),
    )
  }
}
