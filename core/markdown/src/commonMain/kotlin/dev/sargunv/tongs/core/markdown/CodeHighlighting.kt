package dev.sargunv.tongs.core.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import dev.sargunv.tongs.engine.TokenStyle
import dev.sargunv.tongs.engine.highlightSnippet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Highlights code fences. Returns style changes packed as `utf16Offset shl 8 or styleId`, with
 * [TokenStyle] ordinals for IDs, or null to leave the code plain. Called off the main thread.
 */
fun interface CodeHighlighter {
  fun highlight(language: String, code: String): IntArray?

  companion object {
    /** The app's engine: tree-sitter with Helix queries, the same as the diff view. */
    val Engine = CodeHighlighter(::highlightSnippet)

    val Plain = CodeHighlighter { _, _ -> null }
  }
}

/** The highlighter [ForgeMarkdown] uses for code fences; screenshot tests can swap it. */
val LocalCodeHighlighter = staticCompositionLocalOf { CodeHighlighter.Engine }

/** A code fence or indented block: plain at once, then highlighted when the engine is done. */
@Composable
internal fun HighlightedCode(code: String, language: String?, style: TextStyle) {
  val highlighter = LocalCodeHighlighter.current
  val palette =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) DarkPalette else LightPalette
  val spans =
    produceState<IntArray?>(null, code, language, highlighter) {
        if (!language.isNullOrBlank()) {
          value = withContext(Dispatchers.Default) { highlighter.highlight(language, code) }
        }
      }
      .value
  val text = if (spans == null) AnnotatedString(code) else styled(code, spans, palette)
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
    shape = RoundedCornerShape(10.dp),
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
  ) {
    Text(
      text,
      style = style,
      softWrap = false,
      modifier =
        Modifier.horizontalScroll(rememberScrollState())
          .padding(horizontal = 12.dp, vertical = 10.dp),
    )
  }
}

private fun styled(code: String, spans: IntArray, palette: List<Color>): AnnotatedString =
  buildAnnotatedString {
    append(code)
    for (i in spans.indices) {
      val start = (spans[i] ushr 8).coerceAtMost(code.length)
      val end =
        if (i + 1 < spans.size) (spans[i + 1] ushr 8).coerceAtMost(code.length) else code.length
      val color = palette.getOrNull(spans[i] and 0xFF) ?: continue
      if (end > start && color != Color.Unspecified) addStyle(SpanStyle(color = color), start, end)
    }
  }

/** Colors by [TokenStyle]; [Color.Unspecified] keeps the text color. */
private fun palette(colors: Map<TokenStyle, Color>) =
  TokenStyle.entries.map { colors[it] ?: Color.Unspecified }

/** codeview's palette (One Dark), so fences match the diff view. */
private val DarkPalette =
  palette(
    mapOf(
      TokenStyle.Comment to Color(0xFF7F848E),
      TokenStyle.Keyword to Color(0xFFC678DD),
      TokenStyle.Operator to Color(0xFF56B6C2),
      TokenStyle.Punctuation to Color(0xFFA0A8B7),
      TokenStyle.String to Color(0xFF98C379),
      TokenStyle.StringSpecial to Color(0xFF56B6C2),
      TokenStyle.Number to Color(0xFFD19A66),
      TokenStyle.Constant to Color(0xFFD19A66),
      TokenStyle.Function to Color(0xFF61AFEF),
      TokenStyle.Type to Color(0xFFE5C07B),
      TokenStyle.Property to Color(0xFFE06C75),
      TokenStyle.VariableBuiltin to Color(0xFFE06C75),
      TokenStyle.Attribute to Color(0xFFD19A66),
      TokenStyle.Namespace to Color(0xFFE5C07B),
      TokenStyle.Label to Color(0xFFE06C75),
      TokenStyle.Tag to Color(0xFFE06C75),
      TokenStyle.Heading to Color(0xFFE06C75),
      TokenStyle.Link to Color(0xFF61AFEF),
      TokenStyle.Emphasis to Color(0xFFD19A66),
    ),
  )

/** One Light, the same roles as [DarkPalette] on a light background. */
private val LightPalette =
  palette(
    mapOf(
      TokenStyle.Comment to Color(0xFF8A8C93),
      TokenStyle.Keyword to Color(0xFFA626A4),
      TokenStyle.Operator to Color(0xFF0184BC),
      TokenStyle.Punctuation to Color(0xFF5C6370),
      TokenStyle.String to Color(0xFF50A14F),
      TokenStyle.StringSpecial to Color(0xFF0184BC),
      TokenStyle.Number to Color(0xFF986801),
      TokenStyle.Constant to Color(0xFF986801),
      TokenStyle.Function to Color(0xFF4078F2),
      TokenStyle.Type to Color(0xFFC18401),
      TokenStyle.Property to Color(0xFFE45649),
      TokenStyle.VariableBuiltin to Color(0xFFE45649),
      TokenStyle.Attribute to Color(0xFF986801),
      TokenStyle.Namespace to Color(0xFFC18401),
      TokenStyle.Label to Color(0xFFE45649),
      TokenStyle.Tag to Color(0xFFE45649),
      TokenStyle.Heading to Color(0xFFE45649),
      TokenStyle.Link to Color(0xFF4078F2),
      TokenStyle.Emphasis to Color(0xFF986801),
    ),
  )
