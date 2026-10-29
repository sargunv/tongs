package dev.sargunv.tongs.core.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** The fence language GitHub, Forgejo, and GitLab read as a suggested change. */
internal const val SUGGESTION = "suggestion"

/** A line of a suggested change: kept as it is, removed, or added. */
internal data class SuggestionLine(val kind: Kind, val text: String) {
  enum class Kind {
    Context,
    Removed,
    Added,
  }
}

/**
 * [suggested] against [base], as a forge shows it: lines both share at the start and end as
 * context, and the rest of [base] removed, then the rest of [suggested] added. Without a base,
 * every line is added. Lines are compared whole; that's how short suggestions read best.
 */
internal fun suggestionDiff(base: String?, suggested: String): List<SuggestionLine> {
  // An empty suggestion deletes the lines.
  val new = suggested.removeSuffix("\n").let { if (it.isEmpty()) emptyList() else it.split('\n') }
  if (base == null) return new.map { SuggestionLine(SuggestionLine.Kind.Added, it) }
  val old = base.removeSuffix("\n").split('\n')
  var head = 0
  while (head < old.size && head < new.size && old[head] == new[head]) head++
  var tail = 0
  while (
    tail < old.size - head &&
      tail < new.size - head &&
      old[old.size - 1 - tail] == new[new.size - 1 - tail]
  ) tail++
  return buildList {
    for (i in 0 until head) add(SuggestionLine(SuggestionLine.Kind.Context, old[i]))
    for (i in head until old.size - tail) add(SuggestionLine(SuggestionLine.Kind.Removed, old[i]))
    for (i in head until new.size - tail) add(SuggestionLine(SuggestionLine.Kind.Added, new[i]))
    for (i in old.size - tail until old.size) add(
      SuggestionLine(SuggestionLine.Kind.Context, old[i]),
    )
  }
}

/** A ```` ```suggestion ```` block: a small diff of the lines it would change. */
@Composable
internal fun SuggestedChange(code: String, base: String?, style: TextStyle) {
  val lines = remember(code, base) { suggestionDiff(base, code) }
  val colors = MaterialTheme.colorScheme
  val dark = colors.surface.luminance() < 0.5f
  val added = if (dark) Color(0x332EA043) else Color(0xFFE6FFEC)
  val removed = if (dark) Color(0x33F85149) else Color(0xFFFFEBE9)
  Surface(
    color = colors.surfaceContainerHighest,
    shape = RoundedCornerShape(10.dp),
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
  ) {
    Column {
      Text(
        "Suggested change",
        style = MaterialTheme.typography.labelMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
      )
      BoxWithConstraints(Modifier.fillMaxWidth()) {
        val min = maxWidth
        Column(
          Modifier.horizontalScroll(rememberScrollState())
            .widthIn(min = min)
            .width(IntrinsicSize.Max)
            .padding(bottom = 6.dp),
        ) {
          for (line in lines) {
            val (mark, background) =
              when (line.kind) {
                SuggestionLine.Kind.Context -> " " to Color.Transparent
                SuggestionLine.Kind.Removed -> "−" to removed
                SuggestionLine.Kind.Added -> "+" to added
              }
            Row(Modifier.fillMaxWidth().background(background).padding(horizontal = 12.dp)) {
              Text(mark, style = style, color = colors.onSurfaceVariant)
              Text(
                line.text,
                style = style,
                softWrap = false,
                modifier = Modifier.padding(start = 8.dp),
              )
            }
          }
        }
      }
    }
  }
}
