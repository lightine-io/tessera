package io.lightine.tessera.mrz.camera.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

// TES-123: the scanner configuration is fixed per presentation. MrzScannerScreen reads the config once, when
// it opens, and uses only that captured copy for everything (methods, switcher, rescan, review mode, torch,
// timeouts, theme). A different config passed while the screen is open is ignored; it applies the next time
// the screen enters composition. Before this, enabledMethods was half live: the switcher followed a new set,
// but the current screen and "Rescan" did not, so the user could stay on (or be routed back into) a method
// the app had just disabled.
//
// A configuration change (rotation, font scale, locale) recreates the Activity but is still the SAME
// presentation, so the captured config survives it: rememberSaveable, with the value options flattened to
// Bundle-safe primitives by [openedConfigSaver]. The one exception is onRequestPermission: it is a callback
// that usually closes over the Activity, so the old instance cannot outlive the recreation (keeping it would
// leak the destroyed Activity and ask a dead screen for the permission). After a recreation it is taken from
// the config the app passes at that moment. Process death restores the same way.

/**
 * Captures [config] the first time the scanner opens and returns that captured copy on every later
 * recomposition, ignoring any different config passed while the screen stays open (TES-123). Survives a
 * configuration change through [openedConfigSaver].
 */
@Composable
internal fun rememberOpenedConfig(config: MrzScannerConfig): MrzScannerConfig =
    rememberSaveable(saver = openedConfigSaver(onRequestPermission = config.onRequestPermission)) { config }

/**
 * Saves every value option of a [MrzScannerConfig] as Bundle-safe primitives and restores it with
 * [onRequestPermission], the callback from the config the app passes after the recreation (a callback cannot
 * be saved, see the file header). A malformed saved list restores as `null`, so `rememberSaveable` falls back
 * to the config passed at that moment.
 */
internal fun openedConfigSaver(onRequestPermission: (() -> Unit)?): Saver<MrzScannerConfig, Any> =
    listSaver(
        save = { config -> encodeOpenedConfig(config) },
        restore = { saved -> decodeOpenedConfig(saved, onRequestPermission) },
    )

/** Flattens the value options of [config] to a `List<Any?>` of Bundle-safe values. Pairs with [decodeOpenedConfig]. */
internal fun encodeOpenedConfig(config: MrzScannerConfig): List<Any?> =
    listOf(
        ArrayList(config.enabledMethods.map { it.name }),
        config.reviewMode.name,
        config.showTorchButton,
        config.torchOnByDefault,
        encodeDuration(config.struggleTimeout),
        encodeDuration(config.scanTimeout),
        config.consensusReads,
        config.hapticFeedback,
        config.theme.brandColor,
        config.theme.darkMode.name,
        config.theme.useDynamicColor,
    )

/** Rebuilds a config from [encodeOpenedConfig]'s list, with [onRequestPermission] supplied fresh; `null` if malformed. */
internal fun decodeOpenedConfig(
    saved: List<Any?>,
    onRequestPermission: (() -> Unit)?,
): MrzScannerConfig? {
    if (saved.size != 11) return null
    val methodNames = saved[0] as? List<*> ?: return null
    val enabledMethods =
        methodNames
            .map { name -> ScanMethod.entries.find { it.name == name } ?: return null }
            .toSet()
    val reviewMode = ReviewMode.entries.find { it.name == saved[1] } ?: return null
    val showTorchButton = saved[2] as? Boolean ?: return null
    val torchOnByDefault = saved[3] as? Boolean ?: return null
    val struggleTimeout = decodeDuration(saved[4] as? Long ?: return null)
    val scanTimeout = decodeDuration(saved[5] as? Long ?: return null)
    val consensusReads = saved[6] as? Int ?: return null
    val hapticFeedback = saved[7] as? Boolean ?: return null
    val brandColor = saved[8]?.let { it as? Long ?: return null }
    val darkMode = DarkMode.entries.find { it.name == saved[9] } ?: return null
    val useDynamicColor = saved[10] as? Boolean ?: return null
    return MrzScannerConfig(
        enabledMethods = enabledMethods,
        reviewMode = reviewMode,
        showTorchButton = showTorchButton,
        torchOnByDefault = torchOnByDefault,
        struggleTimeout = struggleTimeout,
        scanTimeout = scanTimeout,
        consensusReads = consensusReads,
        hapticFeedback = hapticFeedback,
        theme = MrzScannerTheme(brandColor = brandColor, darkMode = darkMode, useDynamicColor = useDynamicColor),
        onRequestPermission = onRequestPermission,
    )
}

// A Duration as whole nanoseconds, with the two Long extremes reserved for the infinite values (both the
// struggle and scan timeouts use Duration.INFINITE as "never"). A finite value too large for a Long of
// nanoseconds (about 292 years) would read back as infinite, which is the same thing for a timeout.
private fun encodeDuration(value: Duration): Long =
    when {
        value == Duration.INFINITE -> Long.MAX_VALUE
        value == -Duration.INFINITE -> Long.MIN_VALUE
        else -> value.inWholeNanoseconds
    }

private fun decodeDuration(value: Long): Duration =
    when (value) {
        Long.MAX_VALUE -> Duration.INFINITE
        Long.MIN_VALUE -> -Duration.INFINITE
        else -> value.nanoseconds
    }
