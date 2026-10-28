package dev.sargunv.tongs.codeview

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Draws single-line monospace text with platform text APIs, caching shaped lines. Compose's own
 * text layout is too costly to run for every visible row on every frame.
 */
interface TextPainter {
  val lineHeight: Float
  /** Width of one monospace column. */
  val advance: Float
  /** Distance from a row's top to the text baseline. */
  val baseline: Float

  fun DrawScope.drawText(text: String, x: Float, top: Float, color: Color)
}

expect fun TextPainter(fontSizePx: Float): TextPainter
