package io.lightine.tessera.mrz.camera.ui

import io.lightine.tessera.mrz.camera.MrzScanResult

/**
 * Why the default scanner UI ended without a confirmed reading — carried by
 * [`Cancelled`][MrzScannerResult.Cancelled] so the host can branch on *what happened* rather than seeing
 * one undifferentiated cancel. The set is public and freezes at the 0.5.0 tag; new reasons may be added
 * later (branch with an `else`).
 */
public enum class DismissReason {
    /** The user backed out / pressed cancel. */
    USER_DISMISSED,

    /** The configured [scanTimeout][MrzScannerConfig.scanTimeout] elapsed with no confirmed reading. */
    TIMED_OUT,

    /** The camera could not be started and there was no path forward (a terminal capture error). */
    CAMERA_UNAVAILABLE,

    /** The camera permission was not granted and the user declined to continue another way. */
    PERMISSION_DENIED,
}

/**
 * The terminal outcome [MrzScannerScreen] hands back through its `onResult` callback — the single point
 * where the default UI returns control to the host app. A sealed interface so the consumer branches
 * exhaustively.
 *
 * The screen is a one-shot flow: it drives the camera / saved-image / manual-entry reading internally and
 * calls back exactly once, with either a [Confirmed] reading the user accepted or a [Cancelled] carrying
 * the [reason][Cancelled.reason]. Per-frame scan results, quality signals, and capture errors stay inside
 * the UI; only this final decision crosses the boundary.
 */
public sealed interface MrzScannerResult {
    /**
     * The user reviewed a decoded MRZ and accepted it (or [instant-return][ReviewMode.INSTANT_RETURN]
     * returned it without a review step). [result] is the SDK's own
     * [`MrzScanResult.Decoded`][MrzScanResult.Decoded] — the verbatim parse verdict (including a
     * check-digit mismatch: `PartialSuccess`) plus the raw recognized text and quality signals, exactly as
     * the headless reader produced it. The UI adds no judgement of its own (Principle 1); a UI-confirmed
     * reading is identical to a headless one.
     *
     * **Contains document PII** — the parsed fields and raw OCR — so do not log it verbatim (same caution
     * as [MrzScanResult] itself).
     */
    public data class Confirmed(
        public val result: MrzScanResult.Decoded,
    ) : MrzScannerResult

    /**
     * The flow ended without a confirmed reading. [reason] says why (user backed out, timed out, terminal
     * camera error, or declined permission) so the host can respond appropriately.
     */
    public data class Cancelled(
        public val reason: DismissReason,
    ) : MrzScannerResult
}
