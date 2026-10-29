package dev.sargunv.tongs.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InboxQueryTest {
  private fun row(
    id: String,
    reason: String = "author",
    type: String = "PullRequest",
    owner: String = "octo",
    repo: String = "hello",
    unread: Boolean = true,
    state: String? = "OPEN",
    draft: Boolean = false,
    title: String = "Thread $id",
    readElsewhere: Boolean = false,
  ) =
    InboxRow(
      accountId = 1,
      accountLogin = "me",
      id = id,
      repoOwner = owner,
      repoName = repo,
      title = title,
      subjectType = type,
      subjectNumber = id.toIntOrNull(),
      webUrl = null,
      reason = reason,
      isUnread = unread,
      updatedAt = 0,
      sortAt = 0,
      subjectState = state,
      whatsNew = null,
      stateReason = null,
      isDraft = draft,
      checks = null,
      reviewDecision = null,
      snoozedUntil = null,
      readElsewhere = readElsewhere,
    )

  private val rows =
    listOf(
      row("1", reason = "review_requested"),
      row("2", reason = "mention", type = "Issue", unread = false, state = "CLOSED"),
      row("3", reason = "team_mention", owner = "maplibre", repo = "martin", state = "MERGED"),
      row("4", draft = true, title = "Draw wide characters"),
      row("5", type = "Release", state = null, unread = false, readElsewhere = true),
    )

  private fun ids(query: String) = rows.filter(InboxQuery.parse(query)::matches).map { it.id }

  @Test
  fun takesGitHubsVocabulary() {
    assertEquals(listOf("1"), ids("reason:review-requested"))
    assertEquals(listOf("1"), ids("reason:review_requested"))
    assertEquals(listOf("2", "3"), ids("reason:mention reason:team-mention"))
    assertEquals(listOf("2", "3"), ids("reason:mention,team-mention"))
    assertEquals(listOf("1", "3", "4"), ids("is:pr"))
    assertEquals(listOf("2"), ids("is:issue"))
    assertEquals(listOf("1", "2", "3", "4"), ids("is:issue-or-pull-request"))
    assertEquals(listOf("1", "3", "4"), ids("is:unread"))
    assertEquals(listOf("5"), ids("is:read-elsewhere"))
    assertEquals(listOf("2"), ids("is:read -is:read-elsewhere"))
    assertEquals(listOf("3"), ids("repo:maplibre/martin"))
    assertEquals(listOf("3"), ids("org:MapLibre"))
    assertEquals(listOf("3"), ids("is:merged"))
    assertEquals(listOf("4"), ids("is:draft"))
  }

  @Test
  fun combinesKindsAndNegates() {
    assertEquals(listOf("1", "4"), ids("is:pr is:unread is:open"))
    assertEquals(listOf("2", "3", "4", "5"), ids("-reason:review-requested"))
    assertEquals(listOf("1", "4"), ids("is:pr -is:merged"))
    assertEquals(listOf("4"), ids("wide"))
    assertEquals(listOf("3"), ids("#3"))
  }

  @Test
  fun keepsUnknownQualifiersAside() {
    val query = InboxQuery.parse("is:unread label:bug is:nonsense")
    assertEquals(listOf("label:bug", "is:nonsense"), query.unknown)
    assertEquals(listOf("1", "3", "4"), rows.filter(query::matches).map { it.id })
    assertTrue(InboxQuery.parse("").isEmpty)
    assertTrue(InboxQuery.parse("is:snoozed").wantsSnoozed)
  }

  @Test
  fun snoozedPicksTheListAndNegatedKeepsTheInbox() {
    // `is:snoozed` lists the snoozed threads, and `-is:snoozed` the inbox, which already leaves
    // them out; the rows come from the list the query picks.
    assertEquals(listOf("1", "2", "3", "4", "5"), ids("-is:snoozed"))
    assertEquals(listOf("1", "3", "4"), ids("-is:snoozed is:unread"))
    assertEquals(listOf("1", "2", "3", "4", "5"), ids("is:snoozed"))
    assertEquals(emptyList(), ids("is:snoozed -is:snoozed"))
    assertEquals(false, InboxQuery.parse("-is:snoozed").wantsSnoozed)
  }

  @Test
  fun togglesTerms() {
    val query = InboxQuery.parse("is:unread repo:\"a/b\"")
    assertEquals(listOf("is:unread", "repo:\"a/b\""), InboxQuery.tokenize(query.text))
    assertEquals("repo:\"a/b\"", query.toggle("is:unread"))
    assertEquals("is:unread repo:\"a/b\" is:pr", query.toggle("is:pr"))
  }
}
