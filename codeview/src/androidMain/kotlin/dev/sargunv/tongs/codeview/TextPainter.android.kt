package dev.sargunv.tongs.codeview

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.text.TextRunShaper
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.ceil

/** How Android [TextPainter]s put text on the canvas. */
enum class AndroidTextPath {
  /** Cached glyph IDs on a fixed column grid through `Canvas.drawGlyphs`. Needs API 31. */
  GlyphGrid,
  /** `Canvas.drawText`, which lays out every line through Minikin and its layout cache. */
  DrawText,
}

/**
 * The path for [TextPainter]s created from now on. Falls back to [AndroidTextPath.DrawText] below
 * API 31. Exposed so benchmarks can compare the two.
 */
var androidTextPath: AndroidTextPath = AndroidTextPath.GlyphGrid

actual fun TextPainter(fontSizePx: Float): TextPainter =
  if (androidTextPath == AndroidTextPath.GlyphGrid && Build.VERSION.SDK_INT >= 31) {
    GlyphGridTextPainter(fontSizePx)
  } else {
    CanvasTextPainter(fontSizePx)
  }

private open class CanvasTextPainter(fontSizePx: Float) : TextPainter {
  protected val paint =
    Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
      typeface = Typeface.MONOSPACE
      textSize = fontSizePx
    }

  override val lineHeight: Float = ceil(fontSizePx * 1.5f)
  override val advance: Float = paint.measureText("0")
  override val baseline: Float =
    paint.fontMetrics.let { (lineHeight - (it.descent - it.ascent)) / 2 - it.ascent }

  override fun DrawScope.drawText(text: String, x: Float, top: Float, color: Color) {
    if (text.isEmpty()) return
    paint.color = color.toArgb()
    drawIntoCanvas { draw(it.nativeCanvas, text, x, top + baseline) }
  }

  protected open fun draw(canvas: Canvas, text: String, x: Float, y: Float) =
    canvas.drawText(text, x, y, paint)
}

/**
 * Monospace fast path, like the desktop one: look up each character's glyph once, then place glyphs
 * on the column grid without per-line layout. Lines with a character the monospace font can't draw
 * at one column wide go through `drawText`, which handles font fallback.
 */
@RequiresApi(31)
private class GlyphGridTextPainter(fontSizePx: Float) : CanvasTextPainter(fontSizePx) {
  private val font: Font = shape('0').getFont(0)
  /** Glyph IDs by UTF-16 unit, in pages of 256 allocated on first use. */
  private val glyphPages = arrayOfNulls<IntArray>(256)
  private var glyphIds = IntArray(256)
  private var positions = FloatArray(512)

  override fun draw(canvas: Canvas, text: String, x: Float, y: Float) {
    if (glyphIds.size < text.length) {
      glyphIds = IntArray(text.length * 2)
      positions = FloatArray(text.length * 4)
    }
    for (i in text.indices) {
      val glyph = glyph(text[i])
      if (glyph == MISSING) return super.draw(canvas, text, x, y)
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
