package dev.sargunv.tongs.feature.review

import dev.sargunv.tongs.engine.ChangeKind
import dev.sargunv.tongs.engine.FileClass
import dev.sargunv.tongs.engine.ReviewFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

private fun file(path: String, fileClass: FileClass = FileClass.Normal, added: Int = 1) =
  ReviewFile(
    path = path,
    oldPath = null,
    kind = ChangeKind.Modified,
    fileClass = fileClass,
    additions = added,
    deletions = 0,
    oldBlob = "a",
    newBlob = "b",
    loaded = true,
    binary = false,
  )

class FileTreeTest {
  /** In display order, as the engine gives them. */
  private val files =
    listOf(
      file("bindings/kotlin/src/main/ffi/Map.kt", added = 3),
      file("bindings/kotlin/src/main/ffi/Style.kt", added = 2),
      file("docs/a.md"),
      file("docs/b.md"),
      file("README.md"),
      file("bindings/go/api.go", FileClass.Generated, added = 900),
      file("Cargo.lock", FileClass.Lockfile, added = 40),
    )
  private val tree = FileTree(files)

  private fun List<TreeRow>.render() = map { row ->
    val indent = "  ".repeat(row.depth)
    when (row) {
      is TreeRow.Section -> "$indent[${row.fileClass}] ${row.viewed}/${row.files}"
      is TreeRow.Directory -> "$indent${row.label}/ ${row.viewed}/${row.files} +${row.additions}"
      is TreeRow.File -> "$indent${row.name}${if (row.viewed) " ✓" else ""}"
    }
  }

  @Test
  fun foldsSingleChildChainsAndRollsUpCounts() {
    val viewed = setOf(0, 2)
    assertEquals(
      listOf(
        "bindings/kotlin/…/ffi/ 1/2 +5",
        "  Map.kt ✓",
        "  Style.kt",
        "docs/ 1/2 +2",
        "  a.md ✓",
        "  b.md",
        "README.md",
        "[Generated] 0/1",
        "[Lockfile] 0/1",
      ),
      tree.rows(emptySet(), emptySet(), viewed::contains).render(),
    )
  }

  @Test
  fun expandsSectionsAndCollapsesDirectories() {
    val rows =
      tree.rows(
        expandedSections = setOf(FileClass.Generated),
        collapsedDirs = setOf(FileTree.dirKey(FileClass.Normal, "docs")),
        viewed = { false },
      )
    assertEquals(
      listOf(
        "bindings/kotlin/…/ffi/ 0/2 +5",
        "  Map.kt",
        "  Style.kt",
        "docs/ 0/2 +2",
        "README.md",
        "[Generated] 0/1",
        "  bindings/go/ 0/1 +900",
        "    api.go",
        "[Lockfile] 0/1",
      ),
      rows.render(),
    )
  }

  @Test
  fun filtersFuzzilyAndExpandsWhatMatches() {
    val rows =
      tree.rows(emptySet(), setOf(FileTree.dirKey(FileClass.Normal, "docs")), { false }, "doc b")
    assertEquals(listOf("docs/ 0/2 +2", "  b.md"), rows.render())
    assertEquals(
      listOf("[Lockfile] 0/1", "  Cargo.lock"),
      tree.rows(emptySet(), emptySet(), { false }, "cargo").render(),
    )
  }

  @Test
  fun fuzzyMatchesSubsequences() {
    assertTrue(fuzzyMatch("revkt", "feature/review/src/Review.kt"))
    assertTrue(fuzzyMatch(".kt map", "bindings/kotlin/Map.kt"))
    assertTrue(fuzzyMatch("kotlin/map", "bindings/kotlin/Map.rs"))
    assertTrue(!fuzzyMatch("mapkt", "bindings/kotlin/Map.rs"))
    assertTrue(!fuzzyMatch("doc", "bindings/kotlin/src/Map.kt"))
  }

  @Test
  fun staysFastWithThousandsOfFiles() {
    // About #760's shape: 2,190 files across nested directories.
    val many =
      (0 until 2190).map { i -> file("bindings/lang${i % 12}/src/module${i % 97}/File$i.kt") }
    lateinit var built: FileTree
    val build = measureTime { built = FileTree(many) }
    val flatten = measureTime { built.rows(emptySet(), emptySet(), { it % 3 == 0 }) }
    val filter = measureTime { built.rows(emptySet(), emptySet(), { false }, "mod5 file9") }
    // `bindings/`, then each `langN/src/` folded, its 97 modules, and the files.
    assertEquals(1 + 12 + 12 * 97 + 2190, built.rows(emptySet(), emptySet(), { false }).size)
    // Generous bounds for a loaded CI box; these take a few milliseconds.
    for ((what, time) in listOf("build" to build, "flatten" to flatten, "filter" to filter)) {
      assertTrue(time.inWholeMilliseconds < 500, "$what took $time")
    }
  }
}
