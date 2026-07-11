// @Composable functions are PascalCase by Compose convention — see the note in MrzScannerScreen.kt.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

/**
 * The module's own Material 3 theme, resolved from the consumer's [MrzScannerTheme] seam. Nothing is read
 * from the host app's `MaterialTheme`: the default UI looks the same regardless of where it is embedded, so
 * its appearance is a stable contract rather than a function of the consumer's theme (ADR-026).
 *
 * Resolution order:
 * - **dark/light** from [MrzScannerTheme.darkMode] (auto follows the system);
 * - **base scheme** = Material You dynamic color when [opted in][MrzScannerTheme.useDynamicColor] on
 *   Android 12+, else the Material 3 baseline scheme;
 * - **brand accent** ([MrzScannerTheme.brandColor], packed ARGB) tints the scheme's primary when set — a
 *   tint, not a reskin (the rest of the palette stays the module's own). Because an arbitrary brand colour has
 *   no guaranteed contrast against a fixed foreground (a light tint under `onPrimary: Color.White` is
 *   illegible, and vice versa), [onPrimary] is derived from the tint's own relative luminance alongside it —
 *   see the `brandColor` block below.
 */
@Composable
internal fun TesseraScannerTheme(
    theme: MrzScannerTheme,
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (theme.darkMode) {
            DarkMode.AUTO -> isSystemInDarkTheme()
            DarkMode.ON -> true
            DarkMode.OFF -> false
        }
    val context = LocalContext.current
    val baseScheme =
        when {
            theme.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                darkColorScheme()
            }

            else -> {
                lightColorScheme()
            }
        }
    val colorScheme =
        theme.brandColor?.let { argb ->
            val brand = Color(argb)
            // A brand tint replaces primary, so it also becomes the Button container colour and the colour
            // observation text is drawn on (ReviewScreen's MATCHES tone now avoids that path, but Material3's
            // default Button still uses colorScheme.onPrimary for its label) — an arbitrary brand colour has
            // no guaranteed contrast against a FIXED foreground. Derive onPrimary from the tint's own relative
            // luminance instead: dark text on a light brand colour, light text on a dark one. 0.5 is a simple,
            // documented threshold (not a full WCAG contrast-ratio computation) — good enough to avoid the
            // clearly-illegible case (e.g. the documented example brandColor, a dark teal, would otherwise pair
            // with the baseline scheme's light onPrimary and read as light-on-dark-on-dark in dark mode).
            baseScheme.copy(
                primary = brand,
                onPrimary = if (brand.luminance() > 0.5f) Color.Black else Color.White,
            )
        } ?: baseScheme
    MaterialTheme(colorScheme = colorScheme, content = content)
}
