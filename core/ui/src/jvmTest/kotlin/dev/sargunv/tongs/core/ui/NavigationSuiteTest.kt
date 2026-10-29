package dev.sargunv.tongs.core.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NavigationSuiteTest {
  @Test
  fun signedOutHasNoDestinationsAtPhoneTabletAndDesktopWidths() {
    for (width in listOf(360, 700, 1280)) {
      runDesktopComposeUiTest(width = width, height = 800) {
        setContent {
          TongsTheme {
            TongsNavigationSuite(
              items =
                listOf(NavigationItem("Inbox", TongsIcons.Inbox, TongsIcons.InboxFilled, false) {}),
              showNavigation = false,
            ) {
              Text("Sign in")
            }
          }
        }
        onNodeWithText("Sign in").assertExists()
        onNodeWithText("Inbox").assertDoesNotExist()
      }
    }
  }

  @Test
  fun signedInDestinationsStillWorkAtEveryWidth() {
    for (width in listOf(360, 700, 1280)) {
      runDesktopComposeUiTest(width = width, height = 800) {
        var opened = false
        setContent {
          TongsTheme {
            TongsNavigationSuite(
              items =
                listOf(
                  NavigationItem("Inbox", TongsIcons.Inbox, TongsIcons.InboxFilled, false) {
                    opened = true
                  },
                ),
              showNavigation = true,
            ) {
              Text("App content")
            }
          }
        }
        onNodeWithText("Inbox").performClick()
        assertTrue(opened)
      }
    }
  }
}
