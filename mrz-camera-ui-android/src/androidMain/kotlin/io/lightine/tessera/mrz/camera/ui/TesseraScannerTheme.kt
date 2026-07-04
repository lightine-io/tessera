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
import androidx.compose.ui.platform.LocalContext

/**
 * The module's own Material 3 theme. Nothing is read from the host app's `MaterialTheme`: the default UI
 * looks the same regardless of where it is embedded, so its appearance is a stable contract rather than a
 * function of the consumer's theme (ADR-026 / the 0.5.0 tech-stack recap).
 *
 * Dynamic color (Material You, wallpaper-sourced) is applied only when the consumer opts in via
 * [MrzScannerConfig.useDynamicColor] *and* the device is Android 12+; otherwise the fixed built-in scheme
 * is used. The fixed scheme is currently the Material 3 baseline palette — a bespoke Tessera palette is a
 * later refinement, not a scaffold concern.
 */
@Composable
internal fun TesseraScannerTheme(
    useDynamicColor: Boolean,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme =
        when {
            useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                darkColorScheme()
            }

            else -> {
                lightColorScheme()
            }
        }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
