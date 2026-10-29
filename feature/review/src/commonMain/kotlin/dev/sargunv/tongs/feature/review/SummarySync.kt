package dev.sargunv.tongs.feature.review

/**
 * Keeps the review summary's field and its stored draft in step. The field takes what's stored
 * whenever it has no unsaved typing: when the draft loads after the field is shown, or changes
 * elsewhere, as when a pending review from another client is continued. What the reader types is
 * saved, and nothing else, so loading never writes the draft back.
 */
class SummarySync {
  /** The text the field and the store last agreed on; null until the store has spoken. */
  private var synced: String? = null
  private var saved: String? = null

  /** The field's new text for [stored], given its [current] text, or null to leave it. */
  fun adopt(stored: String?, current: String): String? {
    if (stored == null) return null
    val untouched = current == (synced ?: "")
    if (untouched || current == stored) {
      synced = stored
      saved = stored
    }
    return stored.takeIf { untouched && it != current }
  }

  /** Whether [text], typed into the field, is new to the store. Marks it saved if so. */
  fun shouldSave(text: String): Boolean {
    if (text == saved || text == synced || (synced == null && text.isEmpty())) return false
    saved = text
    return true
  }
}
