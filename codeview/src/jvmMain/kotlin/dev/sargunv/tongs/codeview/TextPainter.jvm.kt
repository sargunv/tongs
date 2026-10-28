package dev.sargunv.tongs.codeview

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.ceil
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.TextBlob
import org.jetbrains.skia.TextLine

actual fun TextPainter(fontSizePx: Float): TextPainter = SkiaTextPainter(fontSizePx)

/**
 * Monospace fast path: map characters straight to glyphs and place them on a fixed column grid,
 * skipping HarfBuzz shaping, which costs milliseconds per line. Lines with characters the font
 * lacks fall back to fully shaped [TextLine]s, which handle font fallback.
 */
private class SkiaTextPainter(fontSizePx: Float) : TextPainter {
  private val font =
    Font(FontMgr.default.matchFamilyStyle("monospace", FontStyle.NORMAL), fontSizePx).apply {
      isSubpixel = true
    }
  private val cache = LruCache<String, Drawable>(4096) { it.close() }
  private val paints = HashMap<Int, Paint>()

  override val lineHeight: Float = ceil(fontSizePx * 1.5f)
  override val advance: Float = font.measureTextWidth("0")
  override val baseline: Float =
    font.metrics.let { (lineHeight - (it.descent - it.ascent)) / 2 - it.ascent }

  override fun DrawScope.drawText(text: String, x: Float, top: Float, color: Color) {
    if (text.isEmpty()) return
    val drawable = cache.getOrPut(text) { layout(text) }
    val paint = paints.getOrPut(color.toArgb()) { Paint().apply { this.color = color.toArgb() } }
    drawIntoCanvas {
      when (drawable) {
        is Drawable.Blob -> it.nativeCanvas.drawTextBlob(drawable.blob, x, top + baseline, paint)
        is Drawable.Line -> it.nativeCanvas.drawTextLine(drawable.line, x, top + baseline, paint)
      }
    }
  }

  private fun layout(text: String): Drawable {
    val glyphs = font.getStringGlyphs(text)
    if (glyphs.any { it == 0.toShort() }) return Drawable.Line(TextLine.make(text, font))
    val positions = FloatArray(glyphs.size) { it * advance }
    return Drawable.Blob(checkNotNull(TextBlob.makeFromPosH(glyphs, positions, 0f, font)))
  }
}

private sealed interface Drawable : AutoCloseable {
  class Blob(val blob: TextBlob) : Drawable {
    override fun close() = blob.close()
  }

  class Line(val line: TextLine) : Drawable {
    override fun close() = line.close()
  }
}

private class LruCache<K, V>(private val capacity: Int, private val onEvict: (V) -> Unit) :
  LinkedHashMap<K, V>(capacity, 0.75f, true) {
  override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
    (size > capacity).also { if (it) onEvict(eldest.value) }
}
