package dev.sargunv.tongs.feature.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SummarySyncTest {
  @Test
  fun aDraftThatLoadsAfterTheFieldShowsFillsIt() {
    val sync = SummarySync()
    assertNull(sync.adopt(null, ""))
    assertEquals("Looks good", sync.adopt("Looks good", ""))
    // Filling the field isn't typing: it doesn't save back.
    assertFalse(sync.shouldSave("Looks good"))
  }

  @Test
  fun typingWinsOverALateLoad() {
    val sync = SummarySync()
    assertTrue(sync.shouldSave("Mine"))
    assertNull(sync.adopt("Older", "Mine"))
  }

  @Test
  fun aChangeElsewhereReplacesSavedText() {
    val sync = SummarySync()
    sync.adopt("", "")
    assertTrue(sync.shouldSave("Draft"))
    assertNull(sync.adopt("Draft", "Draft"))
    assertEquals("From the web", sync.adopt("From the web", "Draft"))
  }

  @Test
  fun submittedTextCanBeUsedInAnotherReview() {
    val sync = SummarySync()
    sync.adopt("", "")
    assertTrue(sync.shouldSave("Looks good"))
    sync.adopt("Looks good", "Looks good")
    assertEquals("", sync.adopt("", "Looks good"))
    assertTrue(sync.shouldSave("Looks good"))
  }

  @Test
  fun anEmptyFieldBeforeLoadingSavesNothing() {
    assertFalse(SummarySync().shouldSave(""))
  }
}
