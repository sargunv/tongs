package dev.sargunv.tongs.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.materialkolor.material3.ktx.harmonizeWithPrimary

/**
 * Colors for an issue's or PR's state and its checks, the hues people know from forges (green open,
 * purple merged, red closed), nudged toward the theme's primary so they sit in its palette. Use
 * them for small marks (an icon, a glyph), not for large areas.
 */
@Immutable
data class StateColors(
  val open: Color,
  val merged: Color,
  val closed: Color,
  /** Drafts, issues closed as not planned, and anything neutral. */
  val neutral: Color,
  val success: Color,
  val failure: Color,
  val pending: Color,
)

@Composable
fun stateColors(): StateColors {
  val scheme = MaterialTheme.colorScheme
  val dark = scheme.surface.luminance() < 0.5f
  return remember(scheme) {
    fun hue(light: Long, darkTone: Long) =
      scheme.harmonizeWithPrimary(Color(if (dark) darkTone else light))
    StateColors(
      open = hue(0xFF1A7F37, 0xFF3FB950),
      merged = hue(0xFF8250DF, 0xFFA371F7),
      closed = hue(0xFFCF222E, 0xFFF85149),
      neutral = scheme.onSurfaceVariant,
      success = hue(0xFF1A7F37, 0xFF3FB950),
      failure = hue(0xFFCF222E, 0xFFF85149),
      pending = hue(0xFF9A6700, 0xFFD29922),
    )
  }
}
