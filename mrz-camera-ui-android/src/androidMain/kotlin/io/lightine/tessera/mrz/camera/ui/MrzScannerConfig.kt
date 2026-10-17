// The MrzScannerConfig DSL factory is a PascalCase top-level function (the idiomatic Kotlin builder-DSL
// shape, as Compose does for AnnotatedString etc.), which ktlint's standard function-naming rule flags.
// Suppressed at file scope, the same per-file exemption used for @Composable files in this module.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import androidx.compose.runtime.Immutable
import io.lightine.tessera.mrz.camera.MrzDecodeConsensus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A reading method the scanner screen can offer.
 *
 * Restrict the screen to a subset with [MrzScannerConfig.enabledMethods].
 *
 * @since 0.5.0
 */
public enum class ScanMethod {
    /** Reads the MRZ from the live camera preview. */
    CAMERA,

    /** Reads the MRZ from a photo the user picks. Not enabled by default. */
    SAVED_IMAGE,

    /** The user types the MRZ by hand. */
    MANUAL_ENTRY,
}

/**
 * What the screen does when the reader decodes an MRZ.
 *
 * The same in both modes: a saved photo that fails to parse shows the read-failed screen, and a
 * photo with no MRZ shows "No MRZ found in this photo". Typed input that fails to parse stays on
 * the manual screen with a note. The live camera skips a frame that fails to parse and keeps
 * looking.
 *
 * @since 0.5.0
 */
public enum class ReviewMode {
    /** Shows a review screen with the parsed fields and observations. The user accepts or rescans. */
    REVIEW,

    /** Returns the decoded result at once, with no review step. */
    INSTANT_RETURN,
}

/**
 * The light or dark appearance of the scanner screen's own theme.
 *
 * @since 0.5.0
 */
public enum class DarkMode {
    /** Follows the system dark mode setting. */
    AUTO,

    /** Always dark. */
    ON,

    /** Always light. */
    OFF,
}

/**
 * Brand and appearance options for the scanner screen's own theme.
 *
 * @property brandColor Accent color as a packed `0xAARRGGBB` `Long`, such as `0xFF00695CL`.
 *   Replaces the theme's primary color; the text color on it is black when the accent's
 *   luminance is above 0.5, otherwise white. `null` (default) keeps the built-in accent.
 * @property darkMode Light or dark appearance. Default [DarkMode.AUTO].
 * @property useDynamicColor Uses Material You dynamic color on Android 12 (API 31) and later.
 *   Default `false`; has no effect below Android 12.
 * @since 0.5.0
 */
@Immutable
public class MrzScannerTheme internal constructor(
    public val brandColor: Long?,
    public val darkMode: DarkMode,
    public val useDynamicColor: Boolean,
) {
    /**
     * Collects [MrzScannerTheme] options; reached through [MrzScannerConfig.Builder.theme].
     *
     * @since 0.5.0
     */
    public class Builder {
        /** Sets [MrzScannerTheme.brandColor]. Default `null`. */
        public var brandColor: Long? = null

        /** Sets [MrzScannerTheme.darkMode]. Default [DarkMode.AUTO]. */
        public var darkMode: DarkMode = DarkMode.AUTO

        /** Sets [MrzScannerTheme.useDynamicColor]. Default `false`. */
        public var useDynamicColor: Boolean = false

        /** Builds the immutable [MrzScannerTheme]. */
        public fun build(): MrzScannerTheme = MrzScannerTheme(brandColor, darkMode, useDynamicColor)
    }
}

/**
 * Options for [MrzScannerScreen]. Build it with the [MrzScannerConfig] function.
 *
 * The configuration is read when the scanner opens; changes while it is open apply the next time it opens.
 *
 * @property enabledMethods Reading methods the screen offers. Default: [ScanMethod.CAMERA] and
 *   [ScanMethod.MANUAL_ENTRY]. Add [ScanMethod.SAVED_IMAGE] to opt in to photo reading. The screen
 *   starts on the first present of camera, manual entry, saved image.
 * @property reviewMode What happens when an MRZ decodes. Default [ReviewMode.REVIEW].
 * @property showTorchButton Shows the torch toggle over the live preview when the camera has a
 *   flash unit. Default `true`.
 * @property torchOnByDefault Starts with the torch on. Default `false`.
 * @property struggleTimeout Time on the live preview before the "still looking" hint can appear.
 *   Default 10 seconds, counted from when the camera becomes active. The hint shows when the time
 *   is up, whatever the camera sees; [Duration.INFINITE] never shows it.
 * @property scanTimeout Deadline for the whole session, across every screen. When it passes, the
 *   screen reports [MrzScannerResult.Cancelled] with [DismissReason.TIMED_OUT]. Default
 *   [Duration.INFINITE] (no deadline). It counts only foreground time and pauses while the app is
 *   not resumed, for example in the background or with the device locked. A camera held by
 *   another app does not pause it. A finite value shows a countdown.
 * @property consensusReads Number of live-camera frames that must decode the same document before
 *   it is accepted. Default 2 ([MrzDecodeConsensus.DEFAULT_THRESHOLD]); `1` accepts the first
 *   decode. Applies to the live camera only. Must be at least 1; [Builder.build] throws
 *   [IllegalArgumentException] otherwise.
 * @property hapticFeedback Plays a short haptic when a live-camera read is accepted. Default `true`.
 * @property theme Brand and appearance options. See [MrzScannerTheme].
 * @property onRequestPermission Called when the user taps the grant button on the permission
 *   screen. The screen never requests the permission itself. With `null` (default) the screen
 *   shows no grant button.
 * @since 0.5.0
 */
@Immutable
public class MrzScannerConfig internal constructor(
    public val enabledMethods: Set<ScanMethod>,
    public val reviewMode: ReviewMode,
    public val showTorchButton: Boolean,
    public val torchOnByDefault: Boolean,
    public val struggleTimeout: Duration,
    public val scanTimeout: Duration,
    public val consensusReads: Int,
    public val hapticFeedback: Boolean,
    public val theme: MrzScannerTheme,
    public val onRequestPermission: (() -> Unit)?,
) {
    /**
     * Collects [MrzScannerConfig] options. Use the [MrzScannerConfig] function instead of calling it directly.
     *
     * @since 0.5.0
     */
    public class Builder {
        /** Sets [MrzScannerConfig.enabledMethods]. Default: camera and manual entry. */
        public var enabledMethods: Set<ScanMethod> = setOf(ScanMethod.CAMERA, ScanMethod.MANUAL_ENTRY)

        /** Sets [MrzScannerConfig.reviewMode]. Default [ReviewMode.REVIEW]. */
        public var reviewMode: ReviewMode = ReviewMode.REVIEW

        /** Sets [MrzScannerConfig.showTorchButton]. Default `true`. */
        public var showTorchButton: Boolean = true

        /** Sets [MrzScannerConfig.torchOnByDefault]. Default `false`. */
        public var torchOnByDefault: Boolean = false

        /** Sets [MrzScannerConfig.struggleTimeout]. Default 10 seconds. */
        public var struggleTimeout: Duration = 10.seconds

        /** Sets [MrzScannerConfig.scanTimeout]. Default [Duration.INFINITE]. */
        public var scanTimeout: Duration = Duration.INFINITE

        /** Sets [MrzScannerConfig.consensusReads]. Default 2. Must be at least 1. */
        public var consensusReads: Int = MrzDecodeConsensus.DEFAULT_THRESHOLD

        /** Sets [MrzScannerConfig.hapticFeedback]. Default `true`. */
        public var hapticFeedback: Boolean = true

        /** Sets [MrzScannerConfig.onRequestPermission]. Default `null`. */
        public var onRequestPermission: (() -> Unit)? = null

        private val themeBuilder: MrzScannerTheme.Builder = MrzScannerTheme.Builder()

        /** Sets the [MrzScannerConfig.theme] options in [configure]. */
        public fun theme(configure: MrzScannerTheme.Builder.() -> Unit) {
            themeBuilder.apply(configure)
        }

        /**
         * Builds the immutable [MrzScannerConfig].
         *
         * @throws IllegalArgumentException if [consensusReads] is below 1.
         */
        public fun build(): MrzScannerConfig {
            require(consensusReads >= 1) { "consensusReads must be >= 1, was $consensusReads" }
            return MrzScannerConfig(
                enabledMethods = enabledMethods,
                reviewMode = reviewMode,
                showTorchButton = showTorchButton,
                torchOnByDefault = torchOnByDefault,
                struggleTimeout = struggleTimeout,
                scanTimeout = scanTimeout,
                consensusReads = consensusReads,
                hapticFeedback = hapticFeedback,
                theme = themeBuilder.build(),
                onRequestPermission = onRequestPermission,
            )
        }
    }
}

/**
 * Builds a [MrzScannerConfig]; call it with no arguments for all defaults.
 *
 * @param configure Sets options on the [MrzScannerConfig.Builder].
 * @return The immutable configuration.
 * @throws IllegalArgumentException if `consensusReads` is below 1.
 */
public fun MrzScannerConfig(configure: MrzScannerConfig.Builder.() -> Unit = {}): MrzScannerConfig =
    MrzScannerConfig.Builder().apply(configure).build()
