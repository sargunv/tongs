package dev.sargunv.tongs.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme

/**
 * Ember orange: tongs live by a forge. Seeds the scheme where the platform has no dynamic color.
 */
val TongsSeedColor = Color(0xFFC2562B)

/**
 * Material 3 Expressive, the same on Android and desktop: one UI tree, with the platform setting
 * only color and density. Android uses the wallpaper's dynamic colors; desktop derives a scheme
 * from [TongsSeedColor]. Desktop is denser, since it has a pointer and a keyboard.
 */
@Composable
fun TongsTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  val colorScheme =
    platformColorScheme(darkTheme)
      ?: rememberDynamicColorScheme(
        seedColor = TongsSeedColor,
        isDark = darkTheme,
        style = PaletteStyle.TonalSpot,
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
      )
  val density = if (isDesktop) TongsDensity.Dense else TongsDensity.Comfortable
  CompositionLocalProvider(
    LocalTongsDensity provides density,
    LocalMinimumInteractiveComponentSize provides density.minTouchTarget,
  ) {
    MaterialExpressiveTheme(
      colorScheme = colorScheme,
      motionScheme = MotionScheme.expressive(),
      typography = if (isDesktop) DesktopTypography else Typography(),
      content = content,
    )
  }
}

/** Material's type scale with smaller body text, for desktop's denser layouts. */
private val DesktopTypography =
  Typography().run {
    copy(
      titleLarge = titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp),
      bodyLarge = bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
      bodyMedium = bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
    )
  }

/** The platform's own color scheme, such as Android's dynamic color, if it has one. */
@Composable internal expect fun platformColorScheme(darkTheme: Boolean): ColorScheme?

/** True on desktop: a pointer, a keyboard, and room for denser layouts. */
expect val isDesktop: Boolean

/** Spacing that differs between touch and pointer platforms. Read it from [LocalTongsDensity]. */
@Immutable
data class TongsDensity(
  val minTouchTarget: Dp,
  /** Vertical padding inside a list row. */
  val rowVerticalPadding: Dp,
  val rowHorizontalPadding: Dp,
  /** A top app bar's height. */
  val appBarHeight: Dp,
) {
  companion object {
    val Comfortable =
      TongsDensity(
        minTouchTarget = 48.dp,
        rowVerticalPadding = 10.dp,
        rowHorizontalPadding = 16.dp,
        appBarHeight = 64.dp,
      )
    val Dense =
      TongsDensity(
        minTouchTarget = 32.dp,
        rowVerticalPadding = 8.dp,
        rowHorizontalPadding = 16.dp,
        appBarHeight = 52.dp,
      )
  }
}

val LocalTongsDensity = staticCompositionLocalOf { TongsDensity.Comfortable }
