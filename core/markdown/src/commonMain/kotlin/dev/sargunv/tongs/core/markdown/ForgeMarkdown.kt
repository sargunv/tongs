package dev.sargunv.tongs.core.markdown

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.mikepenz.markdown.model.PlaceholderConfig
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bodies shorter than this are prepared and parsed during composition, so they never jump. */
private const val SYNC_PARSE_CHARS = 4_000

/**
 * Renders a comment, description, or draft the way the forge would: GitHub-flavored Markdown with
 * references linked ([prepareForgeMarkdown]), single newlines as line breaks like GitHub comments,
 * images through Coil, and code fences highlighted by the engine. A ```` ```suggestion ```` block
 * shows as a diff against [MarkdownContext.suggestionBase]. Link clicks go through
 * `LocalUriHandler`; wrap a screen in [ProvideForgeLinks] to open threads in the app.
 *
 * Short bodies render on first composition. Long ones are prepared and parsed off the main thread
 * and show once they're ready, keeping the previous text while a changed one parses.
 *
 * @param textStyle the body text; headings, code, and quotes scale from it.
 */
@Composable
fun ForgeMarkdown(
  markdown: String,
  context: MarkdownContext,
  modifier: Modifier = Modifier,
  textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
  val short = markdown.length < SYNC_PARSE_CHARS
  val prepared =
    if (short) {
      remember(markdown, context) { prepareForgeMarkdown(markdown, context) }
    } else {
      produceState("", markdown, context) {
          value = withContext(Dispatchers.Default) { prepareForgeMarkdown(markdown, context) }
        }
        .value
    }
  val state = rememberMarkdownState(prepared, retainState = true, immediate = short)
  val colors = MaterialTheme.colorScheme
  val text = textStyle.copy(color = colors.onSurface)
  val code =
    text.copy(
      fontFamily = FontFamily.Monospace,
      fontSize = text.fontSize * 0.88f,
      lineHeight = text.lineHeight * 0.95f,
    )
  fun heading(scale: Float) =
    text.copy(
      fontSize = text.fontSize * scale,
      lineHeight = text.lineHeight * scale,
      fontWeight = FontWeight.SemiBold,
    )
  Markdown(
    markdownState = state,
    modifier = modifier.fillMaxWidth(),
    colors =
      markdownColor(
        text = colors.onSurface,
        codeBackground = colors.surfaceContainerHighest,
        inlineCodeBackground = colors.onSurface.copy(alpha = 0.08f),
        dividerColor = colors.outlineVariant,
        tableBackground = colors.onSurface.copy(alpha = 0.03f),
      ),
    typography =
      markdownTypography(
        h1 = heading(1.45f),
        h2 = heading(1.25f),
        h3 = heading(1.12f),
        h4 = heading(1f),
        h5 = heading(0.95f),
        h6 = heading(0.9f).copy(color = colors.onSurfaceVariant),
        text = text,
        code = code,
        inlineCode = code.copy(fontSize = 0.9.em, lineHeight = text.lineHeight),
        quote = text.copy(color = colors.onSurfaceVariant),
        paragraph = text,
        ordered = text,
        bullet = text,
        list = text,
        textLink =
          TextLinkStyles(style = SpanStyle(color = colors.primary, fontWeight = FontWeight.Medium)),
        table = text.copy(fontSize = text.fontSize * 0.94f),
        alertTitle = text.copy(fontWeight = FontWeight.SemiBold),
      ),
    padding =
      markdownPadding(
        block = 4.dp,
        list = 2.dp,
        listItemTop = 2.dp,
        listItemBottom = 2.dp,
        listIndent = 6.dp,
        codeBlock = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        blockQuote = PaddingValues(start = 14.dp, end = 4.dp),
        blockQuoteText = PaddingValues(vertical = 2.dp),
        blockQuoteBar =
          PaddingValues.Absolute(left = 2.dp, top = 2.dp, right = 2.dp, bottom = 2.dp),
      ),
    dimens =
      markdownDimens(
        codeBackgroundCornerSize = 10.dp,
        blockQuoteThickness = 3.dp,
        tableCellPadding = 10.dp,
        tableCellWidth = 140.dp,
        tableCornerSize = 10.dp,
      ),
    imageTransformer = FittedImages,
    annotator =
      remember { markdownAnnotator(config = markdownAnnotatorConfig(eolAsNewLine = true)) },
    components =
      remember(context.suggestionBase) {
        markdownComponents(
          codeFence = { model ->
            MarkdownCodeFence(
              model.content,
              model.node,
              block = { source, language, style ->
                if (language == SUGGESTION) {
                  SuggestedChange(source, context.suggestionBase, style)
                } else {
                  HighlightedCode(source, language, style)
                }
              },
            )
          },
          codeBlock = { model ->
            MarkdownCodeBlock(
              model.content,
              model.node,
              block = { source, language, style ->
                HighlightedCode(source, language, style)
              },
            )
          },
          checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
          image = { BlockImage(it) },
        )
      },
  )
}

/** Coil, with images no wider than the text and no taller than [MAX_IMAGE_HEIGHT_DP]. */
private object FittedImages : ImageTransformer {
  private const val MAX_IMAGE_HEIGHT_DP = 420f

  @Composable override fun transform(link: String) = Coil3ImageTransformerImpl.transform(link)

  @Composable
  override fun intrinsicSize(painter: Painter): Size =
    Coil3ImageTransformerImpl.intrinsicSize(painter)

  override fun placeholderConfig(
    link: String,
    density: Density,
    containerSize: Size,
    imageWidth: ImageWidth,
    imageSize: Size,
    imageSizeChanged: ((link: String, Size) -> Unit)?,
  ): PlaceholderConfig {
    val fitted =
      super.placeholderConfig(
        link,
        density,
        containerSize,
        ImageWidth.IMAGE_WIDTH,
        imageSize,
        imageSizeChanged,
      )
    val size = fitted.size
    if (size.height <= MAX_IMAGE_HEIGHT_DP) return fitted
    val scale = MAX_IMAGE_HEIGHT_DP / size.height
    return fitted.copy(size = Size(size.width * scale, MAX_IMAGE_HEIGHT_DP))
  }
}
