package dev.sargunv.tongs.core.markdown

import dev.sargunv.tongs.forge.api.RepoRef
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/** Where Markdown is rendered, so references resolve: the instance and the thread's repo. */
data class MarkdownContext(val webUrl: String, val repo: RepoRef?)

/**
 * Rewrites `#123`, `owner/repo#123`, `@user`, `@org/team`, and commit SHAs (7 to 40 hex digits,
 * with at least one digit and one letter, alone or as `owner/repo@sha`) into Markdown links to
 * their web URLs, as GitHub renders them. Leaves code spans, code fences, links, autolinks, bare
 * URLs, and HTML alone. `#123` and bare SHAs need [MarkdownContext.repo].
 *
 * Issue and PR references link to `/issues/N`, which GitHub redirects for PRs; SHAs show their
 * first seven digits, like GitHub.
 */
fun linkifyReferences(markdown: String, context: MarkdownContext): String =
  rewriteMarkdown(markdown, context, linkify = true, html = false)

/**
 * What [ForgeMarkdown] renders: [markdown] with Unix line endings (bodies written on the web come
 * with CRLF), references linked, and the HTML GitHub comments commonly carry turned into Markdown
 * the renderer understands (see [convertHtmlBlock]).
 */
fun prepareForgeMarkdown(markdown: String, context: MarkdownContext): String =
  rewriteMarkdown(markdown.replace("\r\n", "\n"), context, linkify = true, html = true)

internal fun rewriteMarkdown(
  markdown: String,
  context: MarkdownContext,
  linkify: Boolean,
  html: Boolean,
): String {
  if (markdown.isEmpty()) return markdown
  val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
  val edits = mutableListOf<Edit>()
  val runs = mutableListOf<IntRange>()
  var runStart = -1
  var runEnd = -1

  fun closeRun() {
    if (runStart >= 0 && runEnd > runStart) runs += runStart until runEnd
    runStart = -1
  }

  fun visit(node: ASTNode, inCell: Boolean) {
    val type = node.type
    when {
      type == MarkdownElementTypes.HTML_BLOCK -> {
        closeRun()
        if (html) {
          val source = markdown.substring(node.startOffset, node.endOffset)
          edits += Edit(node.startOffset, node.endOffset, convertHtmlBlock(source))
        }
      }
      type == MarkdownTokenTypes.HTML_TAG -> {
        closeRun()
        if (html) {
          val source = markdown.substring(node.startOffset, node.endOffset)
          convertInlineHtml(source, inCell)?.let {
            edits += Edit(node.startOffset, node.endOffset, it)
          }
        }
      }
      type in OPAQUE -> closeRun()
      node.children.isEmpty() -> {
        if (type in UNLINKABLE_TOKENS) {
          closeRun()
        } else {
          if (runStart < 0 || node.startOffset != runEnd) {
            closeRun()
            runStart = node.startOffset
          }
          runEnd = node.endOffset
        }
      }
      else -> {
        val cell = inCell || type == GFMElementTypes.TABLE || type == GFMTokenTypes.CELL
        for (child in node.children) visit(child, cell)
        // Blocks don't run into each other.
        if (type !in INLINE_CONTAINERS) closeRun()
      }
    }
  }

  visit(tree, inCell = false)
  closeRun()
  if (linkify) for (run in runs) edits += referenceEdits(markdown, run, context)
  if (edits.isEmpty()) return markdown
  return buildString {
    var at = 0
    for (edit in edits.sortedBy { it.start }) {
      if (edit.start < at) continue
      append(markdown, at, edit.start)
      append(edit.replacement)
      at = edit.end
    }
    append(markdown, at, markdown.length)
  }
}

private class Edit(val start: Int, val end: Int, val replacement: String)

/** Subtrees whose text is never linked. */
private val OPAQUE: Set<IElementType> =
  setOf(
    MarkdownElementTypes.CODE_SPAN,
    MarkdownElementTypes.CODE_FENCE,
    MarkdownElementTypes.CODE_BLOCK,
    MarkdownElementTypes.INLINE_LINK,
    MarkdownElementTypes.FULL_REFERENCE_LINK,
    MarkdownElementTypes.SHORT_REFERENCE_LINK,
    MarkdownElementTypes.AUTOLINK,
    MarkdownElementTypes.IMAGE,
    MarkdownElementTypes.LINK_DEFINITION,
    GFMElementTypes.INLINE_MATH,
    GFMElementTypes.BLOCK_MATH,
  )

/** Leaves that end a run of linkable text. */
private val UNLINKABLE_TOKENS: Set<IElementType> =
  setOf(
    GFMTokenTypes.GFM_AUTOLINK,
    MarkdownTokenTypes.EMAIL_AUTOLINK,
    MarkdownTokenTypes.AUTOLINK,
    MarkdownTokenTypes.ATX_HEADER,
    MarkdownTokenTypes.BACKTICK,
    MarkdownTokenTypes.CODE_LINE,
    MarkdownTokenTypes.EOL,
    MarkdownTokenTypes.HARD_LINE_BREAK,
    GFMTokenTypes.TABLE_SEPARATOR,
    GFMTokenTypes.CHECK_BOX,
  )

/** Elements whose text continues around them, so a run can span their start and end. */
private val INLINE_CONTAINERS: Set<IElementType> =
  setOf(MarkdownElementTypes.EMPH, MarkdownElementTypes.STRONG, GFMElementTypes.STRIKETHROUGH)

private const val NAME = """[A-Za-z0-9][A-Za-z0-9-]{0,38}"""
private const val REPO = """[A-Za-z0-9_.-]+"""
private const val SHA = """[0-9a-f]{7,40}"""

private val CROSS_REF = Regex("""(?<![\w./#@\\-])($NAME)/($REPO)#(\d+)\b""")
private val CROSS_SHA = Regex("""(?<![\w./#@\\-])($NAME)/($REPO)@($SHA)(?![\w-])""")
private val LOCAL_REF = Regex("""(?<![\w/#&\\])#(\d+)\b""")
private val MENTION =
  Regex("""(?<![\w.+/@`\\-])@($NAME)(?:/([A-Za-z0-9_.-]*[A-Za-z0-9_]))?(?![\w-]|\.\w)""")
private val BARE_SHA = Regex("""(?<![\w./#@\\-])($SHA)(?![\w-]|\.\w)""")

private fun referenceEdits(markdown: String, run: IntRange, context: MarkdownContext): List<Edit> {
  val text = markdown.substring(run.first, run.last + 1)
  if (text.none { it == '#' || it == '@' || it.isDigit() }) return emptyList()
  val web = context.webUrl.trimEnd('/')
  val found = mutableListOf<Edit>()
  fun add(match: MatchResult, label: String, url: String) {
    found += Edit(run.first + match.range.first, run.first + match.range.last + 1, "[$label]($url)")
  }
  for (m in CROSS_REF.findAll(text)) {
    val (owner, repo, number) = m.destructured
    add(m, m.value, "$web/$owner/$repo/issues/$number")
  }
  for (m in CROSS_SHA.findAll(text)) {
    val (owner, repo, sha) = m.destructured
    if (isSha(sha)) add(m, "$owner/$repo@${sha.take(7)}", "$web/$owner/$repo/commit/$sha")
  }
  for (m in MENTION.findAll(text)) {
    val (login, team) = m.destructured
    val url = if (team.isNotEmpty()) "$web/orgs/$login/teams/$team" else "$web/$login"
    add(m, m.value, url)
  }
  context.repo?.let { repo ->
    for (m in LOCAL_REF.findAll(text)) add(m, m.value, "$web/$repo/issues/${m.groupValues[1]}")
    for (m in BARE_SHA.findAll(text)) {
      val sha = m.groupValues[1]
      if (isSha(sha)) add(m, sha.take(7), "$web/$repo/commit/$sha")
    }
  }
  // Earlier patterns win where matches overlap, as `owner/repo#1` over its `#1`.
  val kept = mutableListOf<Edit>()
  for (edit in found) {
    if (kept.none { edit.start < it.end && it.start < edit.end }) kept += edit
  }
  return kept
}

/** Hex alone isn't enough: `1234567` is a number and `deadbeef` a word. */
private fun isSha(text: String) = text.any { it.isDigit() } && text.any { it in 'a'..'f' }
