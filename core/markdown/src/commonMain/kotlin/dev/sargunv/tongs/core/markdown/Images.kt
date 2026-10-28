package dev.sargunv.tongs.core.markdown

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.utils.resolveImageAlt
import com.mikepenz.markdown.utils.resolveImageLink

/**
 * An image on its own line, as screenshots in descriptions are: as wide as the text at most, no
 * taller than a phone screen's worth, with rounded corners. Tapping opens it full size in the
 * browser. Failed loads show the alt text.
 */
@Composable
internal fun BlockImage(model: MarkdownComponentModel) {
  val link = model.node.resolveImageLink(model.content, LocalReferenceLinkHandler.current) ?: return
  val alt = model.node.resolveImageAlt(model.content)
  val uriHandler = LocalUriHandler.current
  var failed by remember(link) { mutableStateOf(false) }
  Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    if (failed) {
      Text(
        "🖼 ${alt?.takeIf { it.isNotBlank() } ?: "Image"}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable { uriHandler.openUri(link) },
      )
    } else {
      AsyncImage(
        model = link,
        contentDescription = alt,
        contentScale = ContentScale.Fit,
        alignment = Alignment.CenterStart,
        onError = { failed = true },
        modifier =
          Modifier.heightIn(max = 420.dp).clip(RoundedCornerShape(8.dp)).clickable {
            uriHandler.openUri(link)
          },
      )
    }
  }
}
