package dev.sargunv.tongs.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlin.math.roundToInt

/**
 * A user's or app's picture in a circle. Until it loads, or if there's none, it shows [name]'s
 * initial on a tonal circle. GitHub avatar URLs are asked for at the size drawn.
 */
@Composable
fun Avatar(url: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
  val pixels = with(LocalDensity.current) { size.toPx().roundToInt() }
  val sized = remember(url, pixels) { url?.let { sizedAvatarUrl(it, pixels) } }
  var loaded by remember(sized) { mutableStateOf(false) }
  val colors = MaterialTheme.colorScheme
  Box(
    contentAlignment = Alignment.Center,
    modifier =
      modifier
        .size(size)
        .clip(CircleShape)
        .then(
          if (loaded) Modifier else Modifier.background(colors.secondaryContainer),
        ),
  ) {
    if (!loaded) {
      Text(
        name.trimStart('@').take(1).uppercase(),
        color = colors.onSecondaryContainer,
        fontWeight = FontWeight.SemiBold,
        fontSize = (size.value * 0.45f).sp,
        maxLines = 1,
      )
    }
    if (sized != null) {
      AsyncImage(
        model = sized,
        contentDescription = name,
        contentScale = ContentScale.Crop,
        onSuccess = { loaded = true },
        modifier = Modifier.size(size),
      )
    }
  }
}

/** Adds GitHub's `s` size parameter to avatar URLs, so a 24 dp avatar isn't a 460 px download. */
fun sizedAvatarUrl(url: String, pixels: Int): String {
  val isGitHub = "avatars.githubusercontent.com" in url || "/avatars/" in url
  if (!isGitHub || Regex("""[?&]s=\d+""").containsMatchIn(url)) return url
  return url + (if ('?' in url) "&" else "?") + "s=$pixels"
}
