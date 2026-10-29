package dev.sargunv.tongs.feature.thread

import dev.sargunv.tongs.core.data.TimelineEntry
import dev.sargunv.tongs.forge.api.Actor
import dev.sargunv.tongs.forge.api.EventKind
import dev.sargunv.tongs.forge.api.Label
import dev.sargunv.tongs.forge.api.ReviewState
import dev.sargunv.tongs.forge.api.TimelineItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class TimelineRowsTest {
  private val t = Fixtures.now
  private val mona = Actor("mona")

  private fun event(
    id: String,
    kind: EventKind,
    at: kotlin.time.Instant,
    label: Label? = null,
    actor: Actor = mona,
  ) = TimelineItem.Event(id, at, kind, actor, label = label)

  private fun commit(id: String) = TimelineItem.Commit(id, t, id, "Commit $id", mona)

  private fun rows(vararg items: TimelineItem, gapIndex: Int = items.size, hidden: Int = 0) =
    timelineRows(
      Fixtures.issueSnapshot.copy(
        items = items.map { TimelineEntry(it) },
        gapIndex = gapIndex,
        hiddenCount = hidden,
      ),
    )

  @Test
  fun commitRunsCollapseIntoOneRow() {
    val rows = rows(commit("a"), commit("b"), event("e", EventKind.ReadyForReview, t), commit("c"))
    assertEquals(listOf(2, 1), rows.filterIsInstance<TimelineRow.Commits>().map { it.commits.size })
  }

  @Test
  fun oneActorsLabelChangesReadAsOne() {
    val bug = Label("bug")
    val ui = Label("ui")
    val rows =
      rows(
        event("1", EventKind.Labeled, t, bug),
        event("2", EventKind.Labeled, t + 1.minutes, ui),
        event("3", EventKind.Unlabeled, t + 2.minutes, Label("triage")),
        event("4", EventKind.Labeled, t + 3.hours, bug),
      )
    val labels = rows.filterIsInstance<TimelineRow.Labels>()
    assertEquals(listOf(listOf(bug, ui), listOf(bug)), labels.map { it.added })
    assertEquals(listOf("triage"), labels.first().removed.map { it.name })
  }

  @Test
  fun aMergeHidesItsClosedEvent() {
    val rows = rows(event("m", EventKind.Merged, t), event("c", EventKind.Closed, t))
    assertEquals(listOf("m"), rows.map { it.key })
  }

  @Test
  fun theGapSitsBetweenTheStartAndTheEnd() {
    val rows =
      rows(commit("a"), event("x", EventKind.Reopened, t), commit("b"), gapIndex = 1, hidden = 12)
    assertIs<TimelineRow.Gap>(rows[1])
    assertEquals(12, (rows[1] as TimelineRow.Gap).count)
  }

  @Test
  fun onlyEmptyCommentReviewsAreReplies() {
    fun review(id: String, state: ReviewState) = TimelineItem.Review(id, t, mona, state, body = "")
    val rows =
      rows(
          review("approved", ReviewState.Approved),
          review("changes", ReviewState.ChangesRequested),
          review("dismissed", ReviewState.Dismissed),
          review("replied", ReviewState.Commented),
        )
        .filterIsInstance<TimelineRow.Review>()
        .associateBy { it.key }
    assertFalse(rows.getValue("approved").isReplyOnly)
    assertFalse(rows.getValue("changes").isReplyOnly)
    assertFalse(rows.getValue("dismissed").isReplyOnly)
    assertTrue(rows.getValue("replied").isReplyOnly)
  }
}
