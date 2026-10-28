package dev.sargunv.tongs.feature.review

import dev.sargunv.tongs.engine.ChangeKind
import dev.sargunv.tongs.engine.FileClass
import dev.sargunv.tongs.engine.ReviewFile

/**
 * The PR's files as a tree: one section per [FileClass], ordinary files first and unsectioned, then
 * generated, lockfiles, vendored, and renamed files, each under a header. Directories with a single
 * subdirectory and nothing else fold into one row, like `bindings/kotlin/…/ffi`.
 *
 * Built once per file list, off the main thread; [rows] flattens what's expanded, and it's cheap
 * enough to run on every toggle even with thousands of files.
 */
class FileTree(val files: List<ReviewFile>) {
  /** A directory, or a chain of them folded into one. [path] is the last one's. */
  class Dir(val name: String, val path: String) {
    val dirs = mutableListOf<Dir>()
    /** Indices into [files]. */
    val files = mutableListOf<Int>()
    /** Every file under it, for rollups. */
    var descendants = IntArray(0)
      internal set
  }

  class Section(val fileClass: FileClass, val root: Dir)

  val sections: List<Section>

  init {
    val roots = linkedMapOf<FileClass, Dir>()
    files.forEachIndexed { index, file ->
      val root = roots.getOrPut(file.fileClass) { Dir("", "") }
      var dir = root
      val parts = file.path.split('/')
      for (i in 0 until parts.size - 1) {
        val name = parts[i]
        dir =
          dir.dirs.firstOrNull { it.name == name }
            ?: Dir(name, parts.subList(0, i + 1).joinToString("/")).also { dir.dirs += it }
      }
      dir.files += index
    }
    sections =
      FileClass.entries.mapNotNull { fileClass ->
        roots[fileClass]?.let { Section(fileClass, compact(it, isRoot = true)) }
      }
    for (section in sections) collectDescendants(section.root)
  }

  private fun compact(dir: Dir, isRoot: Boolean): Dir {
    var current = dir
    var name = dir.name
    // Fold single-child chains; a section's root stays itself.
    while (!isRoot && current.files.isEmpty() && current.dirs.size == 1) {
      current = current.dirs.single()
      name = "$name/${current.name}"
    }
    val folded = if (current === dir) dir else Dir(name, current.path)
    if (folded !== current) {
      folded.files += current.files
      folded.dirs += current.dirs
    }
    val children = folded.dirs.map { compact(it, isRoot = false) }
    folded.dirs.clear()
    folded.dirs += children
    return folded
  }

  private fun collectDescendants(dir: Dir): IntArray {
    val parts = dir.dirs.map(::collectDescendants)
    val out = IntArray(dir.files.size + parts.sumOf { it.size })
    var at = 0
    for (part in parts) {
      part.copyInto(out, at)
      at += part.size
    }
    dir.files.toIntArray().copyInto(out, at)
    dir.descendants = out
    return out
  }

  /**
   * The rows to show: every expanded directory's contents, in tree order. Sections other than
   * ordinary files start collapsed, and directories expanded. With a [filter], only matching files
   * and their directories show, all expanded.
   */
  fun rows(
    expandedSections: Set<FileClass>,
    collapsedDirs: Set<String>,
    viewed: (Int) -> Boolean,
    filter: String = "",
  ): List<TreeRow> {
    val query = filter.trim().lowercase()
    val matches: BooleanArray? =
      if (query.isEmpty()) null else BooleanArray(files.size) { fuzzyMatch(query, files[it].path) }
    val out = ArrayList<TreeRow>()
    for (section in sections) {
      val descendants = section.root.descendants
      val shown = matches?.let { m -> descendants.count { m[it] } } ?: descendants.size
      if (shown == 0) continue
      val sectioned = section.fileClass != FileClass.Normal
      val expanded = !sectioned || matches != null || section.fileClass in expandedSections
      if (sectioned) {
        out +=
          TreeRow.Section(
            section.fileClass,
            files = descendants.size,
            viewed = descendants.count(viewed),
            expanded = expanded,
          )
      }
      if (expanded)
        emit(section, section.root, if (sectioned) 1 else 0, collapsedDirs, viewed, matches, out)
    }
    return out
  }

  private fun emit(
    section: Section,
    dir: Dir,
    depth: Int,
    collapsedDirs: Set<String>,
    viewed: (Int) -> Boolean,
    matches: BooleanArray?,
    out: MutableList<TreeRow>,
  ) {
    for (child in dir.dirs) {
      if (matches != null && child.descendants.none { matches[it] }) continue
      val key = dirKey(section.fileClass, child.path)
      val expanded = matches != null || key !in collapsedDirs
      var additions = 0
      var deletions = 0
      var viewedCount = 0
      for (i in child.descendants) {
        additions += files[i].additions
        deletions += files[i].deletions
        if (viewed(i)) viewedCount++
      }
      out +=
        TreeRow.Directory(
          key = key,
          label = folded(child.name),
          path = child.path,
          depth = depth,
          expanded = expanded,
          files = child.descendants.size,
          viewed = viewedCount,
          additions = additions,
          deletions = deletions,
        )
      if (expanded) emit(section, child, depth + 1, collapsedDirs, viewed, matches, out)
    }
    for (index in dir.files) {
      if (matches != null && !matches[index]) continue
      val file = files[index]
      out += TreeRow.File(index, file.path.substringAfterLast('/'), file, depth, viewed(index))
    }
  }

  companion object {
    fun dirKey(fileClass: FileClass, path: String) = "${fileClass.name}:$path"

    /** A long folded chain keeps its ends: `bindings/kotlin/…/ffi`. */
    internal fun folded(name: String): String {
      val parts = name.split('/')
      return if (parts.size <= 3) name else "${parts[0]}/${parts[1]}/…/${parts.last()}"
    }
  }
}

/** One row of the flattened tree. */
sealed class TreeRow {
  abstract val key: String
  abstract val depth: Int

  /** A group of files usually skipped, like generated code, with its header. */
  data class Section(
    val fileClass: FileClass,
    val files: Int,
    val viewed: Int,
    val expanded: Boolean,
  ) : TreeRow() {
    override val key = "section:${fileClass.name}"
    override val depth = 0
  }

  data class Directory(
    override val key: String,
    val label: String,
    val path: String,
    override val depth: Int,
    val expanded: Boolean,
    val files: Int,
    val viewed: Int,
    val additions: Int,
    val deletions: Int,
  ) : TreeRow()

  data class File(
    val index: Int,
    val name: String,
    val file: ReviewFile,
    override val depth: Int,
    val viewed: Boolean,
  ) : TreeRow() {
    override val key = "file:${file.path}"
    val renamed: Boolean
      get() = file.kind == ChangeKind.Renamed
  }
}

/**
 * Whether each space-separated word of [query] matches [path], ignoring case: its characters in
 * order within one path component, so `revkt` finds `Review.kt` and `doc` finds `docs/` but not
 * `bindings/kotlin/src`. A word with a `/` matches across components instead.
 */
fun fuzzyMatch(query: String, path: String): Boolean {
  val components = path.split('/')
  return query
    .split(' ')
    .filter { it.isNotEmpty() }
    .all { word ->
      if ('/' in word) isSubsequence(word, path) else components.any { isSubsequence(word, it) }
    }
}

private fun isSubsequence(word: String, text: String): Boolean {
  var at = 0
  for (c in word) {
    at = text.indexOf(c, at, ignoreCase = true)
    if (at < 0) return false
    at++
  }
  return true
}
