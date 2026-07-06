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
 *   tint, not a reskin (the rest of the palette stays the module's own).
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
        theme.brandColor?.let { argb -> baseScheme.copy(primary = Color(argb)) } ?: baseScheme
    MaterialTheme(colorScheme = colorScheme, content = content)
}
