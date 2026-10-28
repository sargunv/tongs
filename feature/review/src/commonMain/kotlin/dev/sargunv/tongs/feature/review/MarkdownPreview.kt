package dev.sargunv.tongs.feature.review

private val HtmlTag = Regex("""</?[a-zA-Z][^>]*>""")
private val Image = Regex("""!\[([^\]]*)]\([^)]*\)""")
private val Link = Regex("""\[([^\]]*)]\([^)]*\)""")
private val Emphasis = Regex("""(\*\*|__|~~)(.+?)\1""")
private val Fence = Regex("""^\s*```.*$""", RegexOption.MULTILINE)
private val Heading = Regex("""^#{1,6}\s+""", RegexOption.MULTILINE)
private val Blank = Regex("""\n{3,}""")

/**
 * Markdown as readable plain text, for previews until comments render Markdown: images become their
 * alt text, links their text, and tags, emphasis markers, fences, and heading marks go.
 */
fun markdownPreview(markdown: String): String =
  markdown
    .replace(HtmlTag, "")
    .replace(Image) { it.groupValues[1] }
    .replace(Link) { it.groupValues[1] }
    .replace(Emphasis) { it.groupValues[2] }
    .replace(Fence, "")
    .replace(Heading, "")
    .replace(Blank, "\n\n")
    .trim()
