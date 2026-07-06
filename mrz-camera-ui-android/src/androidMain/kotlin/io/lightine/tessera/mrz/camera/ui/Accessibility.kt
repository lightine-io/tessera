// @Composable functions are PascalCase by Compose convention — see the note in MrzScannerScreen.kt.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// Shared accessibility helpers for the default scanner UI (TES-47). Everything here is internal wiring — no
// public API. The screens carry meaning in text (never colour or motion alone), and these helpers back the
// two cross-cutting a11y concerns that need a small shared decision: whether decorative motion should run,
// and (in tests) how the live-region status text is asserted.

/**
 * Whether the platform's animations are enabled — i.e. the user has NOT turned motion off (the
 * "Remove animations" accessibility setting / a reduce-motion preference). Android does not expose a direct
 * "reduce motion" flag, so the established signal is the global [`ANIMATOR_DURATION_SCALE`][Settings.Global.ANIMATOR_DURATION_SCALE]:
 * the platform sets it to `0f` when animations are disabled. Read once per composition (the setting does not
 * change mid-screen in practice).
 *
 * Used to gate *decorative* motion only — the reconnect pulse (mockup 05). Meaning never depends on the
 * animation: the "Reconnecting…" text carries the recoverable status on its own, so when animations are off
 * the dot simply renders static rather than pulsing. Modern Compose animation primitives already honour this
 * scale for finite/duration-based animations, but an [`infiniteRepeatable`][androidx.compose.animation.core.infiniteRepeatable]
 * pulse keeps running regardless, so it is gated explicitly here.
 */
@Composable
internal fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
}
