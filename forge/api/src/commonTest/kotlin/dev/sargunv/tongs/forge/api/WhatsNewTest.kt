package dev.sargunv.tongs.forge.api

import dev.sargunv.tongs.forge.api.SubjectActivity.Assigned
import dev.sargunv.tongs.forge.api.SubjectActivity.Closed
import dev.sargunv.tongs.forge.api.SubjectActivity.Comment
import dev.sargunv.tongs.forge.api.SubjectActivity.Commit
import dev.sargunv.tongs.forge.api.SubjectActivity.ForcePush
import dev.sargunv.tongs.forge.api.SubjectActivity.Merged
import dev.sargunv.tongs.forge.api.SubjectActivity.Reopened
import dev.sargunv.tongs.forge.api.SubjectActivity.Review
import dev.sargunv.tongs.forge.api.SubjectActivity.ReviewRequested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WhatsNewTest {
  private fun summary(vararg activity: SubjectActivity, checksFailed: Boolean = false) =
    summarizeWhatsNew(activity.toList(), viewer = "me", checksFailed = checksFailed)

  @Test
  fun countsCommitsAndComments() {
    assertEquals("2 new commits", summary(Commit("al"), Commit("al")))
    assertEquals("1 new commit", summary(Commit("al")))
    assertEquals("New comment from bo", summary(Comment("bo")))
    assertEquals("2 new comments from bo and al", summary(Comment("bo"), Comment("al")))
    assertEquals("3 new comments", summary(Comment("bo"), Comment("al"), Comment("cy")))
  }

  @Test
  fun leavesOutYourOwnActivity() {
    assertNull(summary(Commit("me"), Comment("ME"), Merged("me")))
    assertEquals("New comment from bo", summary(Commit("me"), Comment("bo")))
  }

  @Test
  fun putsWhatMattersMostFirst() {
    assertEquals(
      "Review requested from you, 1 new commit",
      summary(Commit("al"), ReviewRequested("al", "me")),
    )
    assertEquals(
      "Merged by al, approved by bo, new comment from cy",
      summary(Review("bo", "APPROVED"), Comment("cy"), Merged("al")),
    )
    assertEquals(
      "Changes requested by bo, checks failed, 1 new commit",
      summary(Commit("al"), Review("bo", "CHANGES_REQUESTED"), checksFailed = true),
    )
  }

  @Test
  fun onlyTheLatestStateChangeCounts() {
    assertEquals("Reopened by bo", summary(Closed("al"), Reopened("bo")))
    // Reopened by yourself: nothing to say about the state.
    assertNull(summary(Closed("al"), Reopened("me")))
  }

  @Test
  fun wordsTheRest() {
    assertEquals("Force-pushed", summary(ForcePush("al")))
    assertEquals("Assigned to you", summary(Assigned("al", "me")))
    assertEquals("Review requested from org/team", summary(ReviewRequested("al", "org/team")))
    assertEquals("Reviewed by bo", summary(Review("bo", "COMMENTED")))
    assertEquals("Checks failed", summary(Commit("me"), checksFailed = true))
  }

  @Test
  fun marksTruncatedCounts() {
    assertEquals(
      "20+ new comments",
      summarizeWhatsNew(List(20) { Comment("u$it") }, "me", truncated = true),
    )
  }
}
