package io.lightine.tessera.mrz.camera.ui

import io.lightine.tessera.mrz.camera.MrzScanResult

/**
 * Why the scanner screen ended without a confirmed reading.
 *
 * Carried by [MrzScannerResult.Cancelled].
 *
 * @since 0.5.0
 */
public enum class DismissReason {
    /** The user closed the screen, including from the recoverable camera-in-use notice. */
    USER_DISMISSED,

    /** The [MrzScannerConfig.scanTimeout] deadline passed before a reading was confirmed. */
    TIMED_OUT,

    /** The user closed the screen from the terminal camera-unavailable screen. */
    CAMERA_UNAVAILABLE,

    /** The user closed the screen from a camera permission screen (grant or permanently denied). */
    PERMISSION_DENIED,
}

/**
 * The outcome [MrzScannerScreen] reports when its flow ends.
 *
 * Per-frame results, quality signals, and capture errors stay inside the screen.
 *
 * @since 0.5.0
 */
public sealed interface MrzScannerResult {
    /**
     * The user accepted a decoded MRZ, or [ReviewMode.INSTANT_RETURN] returned it without review.
     *
     * The reading is never a parse failure. A check-digit mismatch is reported as a partial success.
     * It holds document data, so do not log it verbatim.
     *
     * @property result The decoded reading, as the headless reader produced it.
     * @since 0.5.0
     */
    public data class Confirmed(
        public val result: MrzScanResult.Decoded,
    ) : MrzScannerResult

    /**
     * The flow ended without a confirmed reading.
     *
     * @property reason Why the flow ended.
     * @since 0.5.0
     */
    public data class Cancelled(
        public val reason: DismissReason,
    ) : MrzScannerResult
}
