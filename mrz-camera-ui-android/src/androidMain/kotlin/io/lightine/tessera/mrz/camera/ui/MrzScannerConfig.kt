// The MrzScannerConfig DSL factory is a PascalCase top-level function (the idiomatic Kotlin builder-DSL
// shape, as Compose does for AnnotatedString etc.), which ktlint's standard function-naming rule flags.
// Suppressed at file scope, the same per-file exemption used for @Composable files in this module.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import io.lightine.tessera.mrz.camera.MrzDecodeConsensus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A reading method the default scanner UI can offer. A consumer restricts the UI to a subset via
 * [MrzScannerConfig.enabledMethods]; the method switcher shows only the enabled ones.
 */
public enum class ScanMethod {
    /** Live camera capture (the default primary method). */
    CAMERA,

    /**
     * Pick an existing photo and decode its MRZ. **Opt-in — not enabled by default** (scope: saved-image
     * reading is opt-in because a saved image carries more risk than a live capture, ADR-023 / KB TES-A-62).
     * Adding it to [enabledMethods][MrzScannerConfig.enabledMethods] is the consumer's acknowledgement of
     * that risk; there is no separate acknowledgement screen.
     */
    SAVED_IMAGE,

    /** Type the MRZ (or its fields) by hand — the accessible fallback. */
    MANUAL_ENTRY,
}

/**
 * What the screen does the moment the reader decodes an MRZ.
 */
public enum class ReviewMode {
    /** Show the review screen (parsed fields + observations); the user accepts or rescans. The default. */
    REVIEW,

    /** Return the decoded result immediately, with no review step. */
    INSTANT_RETURN,
}

/**
 * The dark/light disposition of the module's own theme (it never reads the host app's theme).
 */
public enum class DarkMode {
    /** Follow the system setting. The default. */
    AUTO,

    /** Always dark. */
    ON,

    /** Always light. */
    OFF,
}

/**
 * The theming seam — the *bounded* surface a consumer can brand without reskinning. The module keeps its
 * own base theme (ADR-026); these options tint it and set the light/dark disposition, they do not hand the
 * consumer the whole palette.
 *
 * @property brandColor an optional accent, as a packed ARGB `Long` (e.g. `0xFF00695CL`). When set it tints
 *   the theme's primary/accent color; `null` (the default) keeps the module's baseline accent. A `Long`
 *   rather than a Compose `Color` keeps the frozen 0.5.0 surface free of a UI-type commitment. Only the
 *   accent is tinted — the rest of the palette (surfaces, containers) stays the module's own, so the tint
 *   itself must read clearly as a small accent against those fixed surfaces; the UI derives a contrasting
 *   text/label color for use ON the tint itself (light or dark, by the tint's own luminance), but a brand
 *   color close to the theme's own surface/background tone can still be hard to see as an accent — pick one
 *   with enough separation from both a light and a dark surface if the UI may run in either mode.
 * @property darkMode auto (default) / on / off.
 * @property useDynamicColor opt into Material You dynamic color (Android 12+, wallpaper-sourced). Off by
 *   default so the look does not depend on the device. Has no effect below Android 12.
 */
public class MrzScannerTheme internal constructor(
    public val brandColor: Long?,
    public val darkMode: DarkMode,
    public val useDynamicColor: Boolean,
) {
    /** Builder for [MrzScannerTheme]; reached via the [MrzScannerConfig.Builder.theme] DSL. */
    public class Builder {
        /** @see MrzScannerTheme.brandColor */
        public var brandColor: Long? = null

        /** @see MrzScannerTheme.darkMode */
        public var darkMode: DarkMode = DarkMode.AUTO

        /** @see MrzScannerTheme.useDynamicColor */
        public var useDynamicColor: Boolean = false

        /** Build the immutable [MrzScannerTheme]. */
        public fun build(): MrzScannerTheme = MrzScannerTheme(brandColor, darkMode, useDynamicColor)
    }
}

/**
 * Consumer configuration for [MrzScannerScreen] — the one place a host app tunes the default UI without
 * forking it. Built with a **DSL builder** (`MrzScannerConfig { … }`) rather than constructor parameters:
 * under [ADR-007](https://lightine.youtrack.cloud/articles/TES-A-37) the public surface freezes at the
 * 0.5.0 tag, and a builder lets new options be **added** later without breaking the binary signature (a
 * new `var` on the builder is additive; a new constructor parameter would not be). Every option is a
 * promise the SDK keeps once 0.5.0 tags.
 *
 * **Reader, not oracle (Principle 1).** Nothing here lets the UI make a trust decision about a document;
 * options govern which methods appear, the review step, appearance, timing, and the permission hand-off.
 *
 * ```
 * val config = MrzScannerConfig {
 *     enabledMethods = setOf(ScanMethod.CAMERA, ScanMethod.MANUAL_ENTRY)
 *     reviewMode = ReviewMode.REVIEW
 *     theme {
 *         brandColor = 0xFF00695CL
 *         darkMode = DarkMode.AUTO
 *     }
 *     onRequestPermission = { /* host requests CAMERA */ }
 * }
 * ```
 *
 * @property enabledMethods which reading methods the UI offers. **Default: camera + manual entry.**
 *   Saved-image is opt-in (off by default) per scope (KB TES-A-62) — adding [`SAVED_IMAGE`][ScanMethod.SAVED_IMAGE]
 *   is the consumer's acknowledgement of the higher saved-image risk (ADR-023). The switcher shows only the
 *   enabled methods.
 * @property reviewMode review (default) vs instant-return once an MRZ decodes.
 * @property showTorchButton show the torch toggle over the live preview (default true).
 * @property torchOnByDefault start with the torch on (default false).
 * @property struggleTimeout how long the live camera scans with no decode before the "still looking / type it
 *   instead" hint appears (default 10s). Camera-scoped: measured from when the camera becomes active (the live
 *   preview), not from when the screen opens — camera-boot time is not counted.
 * @property scanTimeout the **session** deadline: total time the user may spend in the scanner before it gives
 *   up and reports [`Cancelled(TIMED_OUT)`][DismissReason.TIMED_OUT]; [Duration.INFINITE] (the default) never
 *   times out. This is a bound on the whole session for the *host's* sake (e.g. a kiosk or a timed flow), not a
 *   camera limit: it starts when the user enters the scanner and runs across every screen (camera, manual,
 *   photo, review) regardless of method. It counts only foreground time — it pauses while the app is
 *   backgrounded, the device is locked, or the camera is held by another app, and resumes where it left off. Set
 *   any duration you need; when finite, the scanner shows the user a live countdown of the time left. There is
 *   no enforced floor, but pick a value generous enough for a real reading (tens of seconds at least).
 * @property consensusReads how many live-camera frames must decode the **same** document before it is
 *   accepted, shedding transient single-frame OCR misreads the MRZ's checksums cannot catch (e.g. a filler
 *   `<` misread as a letter in the name field). Default 2 ([MrzDecodeConsensus.DEFAULT_THRESHOLD]); `1`
 *   accepts the first decode (no consensus). Higher values add latency to every successful read for
 *   diminishing benefit — see [MrzDecodeConsensus]. Live camera only; saved-image and manual entry are
 *   one-shot and unaffected.
 * @property hapticFeedback fire a short confirming haptic the moment a live-camera read is accepted (the
 *   hands-free "it scanned" cue). Default true. Always honours the device's system haptic setting; set false
 *   to opt the default UI out entirely. Only the camera path buzzes — manual/saved-image already give the
 *   tap/selection its own feedback.
 * @property theme the bounded theming seam; see [MrzScannerTheme].
 * @property onRequestPermission invoked when the UI needs the camera permission it does not hold — the host
 *   requests it (the SDK never requests a permission itself). `null` (default) means the host owns it entirely.
 */
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
    /** Mutable builder for [MrzScannerConfig]. Prefer the [MrzScannerConfig] DSL function over this directly. */
    public class Builder {
        /** @see MrzScannerConfig.enabledMethods */
        public var enabledMethods: Set<ScanMethod> = setOf(ScanMethod.CAMERA, ScanMethod.MANUAL_ENTRY)

        /** @see MrzScannerConfig.reviewMode */
        public var reviewMode: ReviewMode = ReviewMode.REVIEW

        /** @see MrzScannerConfig.showTorchButton */
        public var showTorchButton: Boolean = true

        /** @see MrzScannerConfig.torchOnByDefault */
        public var torchOnByDefault: Boolean = false

        /** @see MrzScannerConfig.struggleTimeout */
        public var struggleTimeout: Duration = 10.seconds

        /** @see MrzScannerConfig.scanTimeout */
        public var scanTimeout: Duration = Duration.INFINITE

        /** @see MrzScannerConfig.consensusReads */
        public var consensusReads: Int = MrzDecodeConsensus.DEFAULT_THRESHOLD

        /** @see MrzScannerConfig.hapticFeedback */
        public var hapticFeedback: Boolean = true

        /** @see MrzScannerConfig.onRequestPermission */
        public var onRequestPermission: (() -> Unit)? = null

        private val themeBuilder: MrzScannerTheme.Builder = MrzScannerTheme.Builder()

        /** Configure the [theme][MrzScannerConfig.theme] seam. */
        public fun theme(configure: MrzScannerTheme.Builder.() -> Unit) {
            themeBuilder.apply(configure)
        }

        /** Build the immutable [MrzScannerConfig]. */
        public fun build(): MrzScannerConfig =
            MrzScannerConfig(
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

/**
 * Build a [MrzScannerConfig] with the DSL builder — `MrzScannerConfig { … }`, or `MrzScannerConfig()` for
 * all defaults.
 */
public fun MrzScannerConfig(configure: MrzScannerConfig.Builder.() -> Unit = {}): MrzScannerConfig =
    MrzScannerConfig.Builder().apply(configure).build()
