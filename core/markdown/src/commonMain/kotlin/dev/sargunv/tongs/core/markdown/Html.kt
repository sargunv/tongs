package dev.sargunv.tongs.core.markdown

// The renderer drops HTML: blocks vanish, text and all, and inline tags disappear while the text
// between them stays. GitHub bodies lean on a little HTML, mostly from templates and pasted
// screenshots, so the common tags become Markdown first. Anything else loses its tags but keeps its
// text, which is what a reader needs. Mermaid, math, and layout don't survive; `bodyHTML` is the
// fallback for those.

private val COMMENT = Regex("""<!--[\s\S]*?(-->|$)""")
private val TAG =
  Regex(
    """<(/?)([A-Za-z][A-Za-z0-9-]*)((?:\s+[^\s>/=]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+))?)*)\s*/?>""",
  )
private val ATTRIBUTE = Regex("""([^\s>/=]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")

/** Tags that start a new line of text when they open or close. */
private val BREAKING =
  setOf(
    "p",
    "div",
    "br",
    "details",
    "summary",
    "li",
    "tr",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "table",
    "ul",
    "ol",
    "hr",
    "picture",
    "blockquote",
    "pre",
  )

/**
 * Turns an HTML block into Markdown: comments go, `<img>` becomes an image, `<a href>` a link,
 * `<summary>` and headings bold text, `<br>` and block tags line breaks, and other tags vanish.
 * Lines stay apart, as they would in a browser, and the result is its own block.
 */
internal fun convertHtmlBlock(html: String): String {
  val source = html.replace(COMMENT, "")
  if (source.isBlank()) return ""
  val out = StringBuilder()
  var at = 0
  var linkStart = -1
  var linkHref: String? = null
  for (match in TAG.findAll(source)) {
    out.append(source, at, match.range.first)
    at = match.range.last + 1
    val closing = match.groupValues[1] == "/"
    val name = match.groupValues[2].lowercase()
    val attributes = attributes(match.groupValues[3])
    when {
      name == "img" -> image(attributes)?.let { out.append(it) }
      name == "a" && !closing -> {
        linkStart = out.length
        linkHref = attributes["href"]
      }
      name == "a" && closing -> {
        val href = linkHref
        if (href != null && linkStart >= 0) {
          val label = out.substring(linkStart).trim()
          out.setLength(linkStart)
          out.append(if (label.isEmpty()) href else "[$label]($href)")
        }
        linkStart = -1
        linkHref = null
      }
      name == "summary" || name.matches(Regex("h[1-6]")) -> {
        out.append(if (closing) "**\n" else "\n**")
      }
      name == "hr" -> out.append("\n\n---\n\n")
      name in BREAKING -> out.append('\n')
    }
  }
  out.append(source, at, source.length)
  // One line per line of text, each a hard break; empty `**` pairs from `<summary></summary>` go.
  val lines =
    out
      .toString()
      .replace("****", "")
      .lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() && it != "**" }
  if (lines.isEmpty()) return ""
  return lines.joinToString("\\\n")
}

/**
 * Converts one inline tag: `<img>` to an image, `<br>` to a hard break (a space in a table cell,
 * where a newline would end the row). Returns null to drop the tag and keep the text around it.
 */
internal fun convertInlineHtml(tag: String, inTableCell: Boolean): String? {
  if (tag.startsWith("<!--")) return ""
  val match = TAG.matchEntire(tag.trim()) ?: return null
  return when (match.groupValues[2].lowercase()) {
    "img" -> image(attributes(match.groupValues[3])) ?: ""
    "br" -> if (inTableCell) " " else "\\\n"
    else -> ""
  }
}

private fun attributes(text: String): Map<String, String> =
  ATTRIBUTE.findAll(text).associate { m ->
    m.groupValues[1].lowercase() to
      (m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: "")
  }

private fun image(attributes: Map<String, String>): String? {
  val src = attributes["src"]?.takeIf { it.isNotBlank() } ?: return null
  val alt = attributes["alt"].orEmpty().replace("[", "").replace("]", "")
  return "![$alt](${src.replace(" ", "%20")})"
}
