package dev.sargunv.tongs.feature.review

import dev.sargunv.tongs.engine.DiffSide
import dev.sargunv.tongs.forge.api.ReviewAnchor
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposerTargetTest {
  private val range =
    ComposerTarget.Line("src/a b:c.kt", DiffSide.Old, 3, DiffSide.New, 7, commitOid = "head1")

  @Test
  fun aNewCommentKeepsTheSelectedCommit() {
    assertEquals(
      ReviewAnchor("head1", "src/a b:c.kt", DiffSide.New, 3, 7, DiffSide.Old),
      range.anchor(),
    )
  }

  @Test
  fun anEditedDraftKeepsItsCommit() {
    val edited = range.copy(draftId = 4, commitOid = "head1")
    assertEquals("head1", edited.anchor().commitOid)
  }
}
