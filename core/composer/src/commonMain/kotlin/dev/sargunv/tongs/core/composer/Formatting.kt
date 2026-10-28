package dev.sargunv.tongs.core.composer

import androidx.compose.ui.text.TextRange

/** Text and a selection: what a formatting action reads and returns. */
data class TextEdit(val text: String, val selection: TextRange)

/** The Markdown toolbar's actions, as pure edits so they're testable without a text field. */
object MarkdownFormatting {
  /**
   * Wraps the selection in [marker], or unwraps it if it's already wrapped. With nothing selected,
   * inserts [placeholder] wrapped and selects it, ready to type over.
   */
  fun wrap(edit: TextEdit, marker: String, placeholder: String): TextEdit {
    val (text, selection) = edit
    val start = selection.min
    val end = selection.max
    val before = text.substring(0, start)
    val after = text.substring(end)
    if (before.endsWith(marker) && after.startsWith(marker)) {
      val unwrapped =
        before.dropLast(marker.length) + text.substring(start, end) + after.drop(marker.length)
      return TextEdit(unwrapped, TextRange(start - marker.length, end - marker.length))
    }
    val inner = if (start == end) placeholder else text.substring(start, end)
    val result = before + marker + inner + marker + after
    val innerStart = start + marker.length
    return TextEdit(result, TextRange(innerStart, innerStart + inner.length))
  }

  /** Wraps the selection in a code span, or in a fence when it spans lines. */
  fun code(edit: TextEdit): TextEdit {
    val selected = edit.text.substring(edit.selection.min, edit.selection.max)
    if (!selected.contains('\n')) return wrap(edit, "`", "code")
    val start = edit.selection.min
    val prefix = if (start == 0 || edit.text[start - 1] == '\n') "" else "\n"
    val fenced = "$prefix```\n$selected\n```\n"
    val text = edit.text.substring(0, start) + fenced + edit.text.substring(edit.selection.max)
    val innerStart = start + prefix.length + 4
    return TextEdit(text, TextRange(innerStart, innerStart + selected.length))
  }

  /** Makes the selection a link and selects the URL placeholder, ready to paste over. */
  fun link(edit: TextEdit): TextEdit {
    val (text, selection) = edit
    val label = text.substring(selection.min, selection.max).ifEmpty { "text" }
    val url = "url"
    val inserted = "[$label]($url)"
    val result = text.substring(0, selection.min) + inserted + text.substring(selection.max)
    val urlStart = selection.min + label.length + 3
    return TextEdit(result, TextRange(urlStart, urlStart + url.length))
  }

  /**
   * Adds [prefix] to every line the selection touches, or removes it if they all have it. A `1. `
   * prefix numbers the lines.
   */
  fun prefixLines(edit: TextEdit, prefix: String): TextEdit {
    val (text, selection) = edit
    val lineStart =
      text.lastIndexOf('\n', (selection.min - 1).coerceAtLeast(-1)).let {
        if (selection.min == 0) 0 else it + 1
      }
    val lineEndIndex = text.indexOf('\n', selection.max).let { if (it < 0) text.length else it }
    val lines = text.substring(lineStart, lineEndIndex).split('\n')
    val numbered = prefix == "1. "
    val pattern = if (numbered) Regex("""^\d+\. """) else null
    fun has(line: String) = pattern?.containsMatchIn(line) ?: line.startsWith(prefix)
    val remove = lines.all(::has)
    val changed = lines.mapIndexed { i, line ->
      when {
        remove && pattern != null -> line.replaceFirst(pattern, "")
        remove -> line.removePrefix(prefix)
        numbered -> "${i + 1}. $line"
        else -> prefix + line
      }
    }
    val block = changed.joinToString("\n")
    val result = text.substring(0, lineStart) + block + text.substring(lineEndIndex)
    val delta = block.length - (lineEndIndex - lineStart)
    // A caret stays at the end of its line; a selection covers the changed lines.
    val newSelection =
      if (selection.collapsed)
        TextRange((selection.end + delta).coerceIn(lineStart, lineStart + block.length))
      else TextRange(lineStart, lineStart + block.length)
    return TextEdit(result, newSelection)
  }

  /** Quotes [markdown] for a reply: `> ` before every line, then a blank line to write after. */
  fun quote(markdown: String, author: String? = null): String {
    // Runs of blank lines, and HTML comments like PR templates' hints, would quote as empty space.
    val text =
      markdown
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        .lines()
        .map { it.trimEnd() }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
    val quoted = text.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
    val attribution = author?.let { "@$it wrote:\n\n" }.orEmpty()
    return "$attribution$quoted\n\n"
  }
}
