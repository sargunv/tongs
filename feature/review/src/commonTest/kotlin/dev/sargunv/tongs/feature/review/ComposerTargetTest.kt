package dev.sargunv.tongs.feature.review

import dev.sargunv.tongs.engine.DiffSide
import dev.sargunv.tongs.forge.api.ReviewAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComposerTargetTest {
  private val range = ComposerTarget.Line("src/a b:c.kt", DiffSide.Old, 3, DiffSide.New, 7)

  @Test
  fun aNewCommentGoesOnTheCommitOnScreen() {
    assertEquals(
      ReviewAnchor("head2", "src/a b:c.kt", DiffSide.New, 3, 7, DiffSide.Old),
      range.anchor("head2"),
    )
  }

  @Test
  fun anEditedDraftKeepsItsCommit() {
    val edited = range.copy(draftId = 4, body = "Typo", commitOid = "head1")
    assertEquals("head1", edited.anchor("head2").commitOid)
  }

  @Test
  fun lineSlotsRoundTrip() {
    val parsed = ComposerTarget.parseLineSlot(range.slot)
    assertEquals(range, parsed)
    assertNull(ComposerTarget.parseLineSlot("review/reply:T1"))
  }
}
