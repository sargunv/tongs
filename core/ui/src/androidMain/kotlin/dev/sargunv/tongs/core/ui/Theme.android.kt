package dev.sargunv.tongs.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Dynamic color, from the wallpaper. Every supported version (API 31+) has it. */
@Composable
internal actual fun platformColorScheme(darkTheme: Boolean): ColorScheme? {
  val context = LocalContext.current
  return if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

actual val isDesktop: Boolean = false
