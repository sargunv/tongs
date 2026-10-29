package dev.sargunv.tongs.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.material3.dynamicColorScheme
import dev.nucleusframework.systemcolor.systemAccentColor

@Composable
internal actual fun platformColorScheme(darkTheme: Boolean): ColorScheme {
  val accent = systemAccentColor() ?: TongsSeedColor
  return remember(accent, darkTheme) {
    dynamicColorScheme(
      seedColor = accent,
      isDark = darkTheme,
      style = PaletteStyle.TonalSpot,
      specVersion = ColorSpec.SpecVersion.SPEC_2025,
    )
  }
}

actual val isDesktop: Boolean = true
