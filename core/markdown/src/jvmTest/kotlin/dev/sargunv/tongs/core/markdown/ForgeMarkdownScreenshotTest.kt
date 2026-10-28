package dev.sargunv.tongs.core.markdown

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sargunv.tongs.core.ui.TongsTheme
import dev.sargunv.tongs.forge.api.RepoRef
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test

/** Desktop screenshots of a PR description as a comment card would show it. */
@OptIn(ExperimentalTestApi::class)
class ForgeMarkdownScreenshotTest {
  private val context = MarkdownContext("https://github.com", RepoRef("octo", "hello"))

  private val image =
    "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAUAAAAB4CAIAAAAMrLyJAAABeklEQVR4nO3TQQ0AIBDAsPODHwSjDA98yJImFbDP5uwFRM33AuCZgSHMwBBmYAgzMIQZGMIMDGEGhjADQ5iBIczAEGZgCDMwhBkYwgwMYQaGMANDmIEhzMAQZmAIMzCEGRjCDAxhBoYwA0OYgSHMwBBmYAgzMIQZGMIMDGEGhjADQ5iBIczAEGZgCDMwhBkYwgwMYQaGMANDmIEhzMAQZmAIMzCEGRjCDAxhBoYwA0OYgSHMwBBmYAgzMIQZGMIMDGEGhjADQ5iBIczAEGZgCDMwhBkYwgwMYQaGMANDmIEhzMAQZmAIMzCEGRjCDAxhBoYwA0OYgSHMwBBmYAgzMIQZGMIMDGEGhjADQ5iBIczAEGZgCDMwhBkYwgwMYQaGMANDmIEhzMAQZmAIMzCEGRjCDAxhBoYwA0OYgSHMwBBmYAgzMIQZGMIMDGEGhjADQ5iBIczAEGZgCDMwhBkYwgwMYQaGMANDmIEhzMAQZmAIMzCEGRjCDAxhF/6PTRSei3XKAAAAAElFTkSuQmCC"

  private val body =
    """
    <!-- Thanks for contributing! Describe your change below. -->
    ## Summary

    Streams packs into the object store as they arrive, so reviews of big PRs show their first
    files sooner. Fixes #412 and follows up on octo/engine#88; cc @sargunv and @octo/reviewers.

    - Writes each pack to a temp file, then **renames** it into place
    - Indexes with `gix-pack` in a background thread
    - [x] Tested on a 576k-line PR
    - [ ] Benchmarks on Android

    > [!NOTE]
    > The object store layout doesn't change, so existing caches keep working.

    ```kotlin
    // Opens the store, creating it if needed.
    fun openStore(dir: String): Store = Store(dir, maxBytes = 512L shl 20)
    ```

    | Stage | Before | After |
    |---|---|---|
    | First file | 4.1 s | 1.3 s |
    | All files | 9.8 s | 9.6 s |

    <details><summary>Screenshot</summary>

    <img width="320" alt="timeline" src="$image">
    </details>

    > Quoting the review: looks good, but see abc1234def for the regression.

    Line one<br>line two
    """
      .trimIndent()

  private fun capture(name: String, dark: Boolean) =
    runDesktopComposeUiTest(width = 520, height = 1200) {
      setContent {
        TongsTheme(darkTheme = dark) {
          Surface { ForgeMarkdown(body, context, Modifier.width(520.dp).padding(16.dp)) }
        }
      }
      // Code fences highlight on a background thread.
      Thread.sleep(500)
      waitForIdle()
      onRoot().captureRoboImage("src/jvmTest/snapshots/$name.png")
    }

  @Test fun description() = capture("markdown", dark = false)

  @Test fun descriptionDark() = capture("markdown-dark", dark = true)
}
