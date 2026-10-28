package dev.sargunv.tongs.codeview

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.text.TextRunShaper
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.ceil

actual fun TextPainter(fontSizePx: Float): TextPainter = GlyphGridTextPainter(fontSizePx)

/**
 * Monospace fast path, like the desktop one: look up each character's glyph once, then place glyphs
 * on the column grid with `Canvas.drawGlyphs`, without per-line layout. Lines with a character the
 * monospace font can't draw at one column wide go through `Canvas.drawText`, which lays them out
 * through Minikin and handles font fallback.
 */
private class GlyphGridTextPainter(fontSizePx: Float) : TextPainter {
  private val paint =
    Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
      typeface = Typeface.MONOSPACE
      textSize = fontSizePx
    }
  private val font: Font = shape('0').getFont(0)
  /** Glyph IDs by UTF-16 unit, in pages of 256 allocated on first use. */
  private val glyphPages = arrayOfNulls<IntArray>(256)
  private var glyphIds = IntArray(256)
  private var positions = FloatArray(512)

  override val lineHeight: Float = ceil(fontSizePx * 1.5f)
  override val advance: Float = paint.measureText("0")
  override val baseline: Float =
    paint.fontMetrics.let { (lineHeight - (it.descent - it.ascent)) / 2 - it.ascent }

  override fun DrawScope.drawText(text: String, x: Float, top: Float, color: Color) {
    if (text.isEmpty()) return
    paint.color = color.toArgb()
    drawIntoCanvas { draw(it.nativeCanvas, text, x, top + baseline) }
  }

  private fun draw(canvas: Canvas, text: String, x: Float, y: Float) {
    if (glyphIds.size < text.length) {
      glyphIds = IntArray(text.length * 2)
      positions = FloatArray(text.length * 4)
    }
    for (i in text.indices) {
      val glyph = glyph(text[i])
      if (glyph == MISSING) return canvas.drawText(text, x, y, paint)
      glyphIds[i] = glyph
      positions[i * 2] = x + i * advance
      positions[i * 2 + 1] = y
    }
    canvas.drawGlyphs(glyphIds, 0, positions, 0, text.length, font, paint)
  }

  private fun glyph(char: Char): Int {
    val page = glyphPages[char.code ushr 8] ?: IntArray(256) { UNKNOWN }
    glyphPages[char.code ushr 8] = page
    val index = char.code and 0xFF
    if (page[index] == UNKNOWN) {
      val glyphs = shape(char)
      val onGrid =
        glyphs.glyphCount() == 1 &&
          glyphs.getGlyphId(0) != 0 &&
          glyphs.getFont(0) == font &&
          abs(glyphs.advance - advance) < 0.01f
      page[index] = if (onGrid) glyphs.getGlyphId(0) else MISSING
    }
    return page[index]
  }

  private fun shape(char: Char) =
    TextRunShaper.shapeTextRun(charArrayOf(char), 0, 1, 0, 1, 0f, 0f, false, paint)

  private companion object {
    const val UNKNOWN = -1
    const val MISSING = -2
  }
}
