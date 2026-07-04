package io.lightine.tessera.mrz.camera.ui

import io.lightine.tessera.mrz.camera.MrzScanResult

/**
 * The terminal outcome [MrzScannerScreen] hands back through its `onResult` callback — the single point
 * where the default UI returns control to the host app. A sealed interface so the consumer branches
 * exhaustively.
 *
 * The screen is a one-shot flow: it drives the camera / saved-image / manual-entry reading internally and
 * calls back exactly once, with either a [Confirmed] reading the user accepted or [Cancelled]. Per-frame
 * scan results, quality signals, and capture errors stay inside the UI; only this final decision crosses
 * the boundary.
 */
public sealed interface MrzScannerResult {
    /**
     * The user reviewed a decoded MRZ and accepted it. [result] is the SDK's own
     * [`MrzScanResult.Decoded`][MrzScanResult.Decoded] — the verbatim parse verdict plus the raw
     * recognized text and quality signals, exactly as the headless reader produced it. The UI adds no
     * judgement of its own (Principle 1); a UI-confirmed reading is identical to a headless one.
     *
     * **Contains document PII** — the parsed fields and raw OCR — so do not log it verbatim (same
     * caution as [MrzScanResult] itself).
     */
    public data class Confirmed(
        public val result: MrzScanResult.Decoded,
    ) : MrzScannerResult

    /**
     * The user dismissed the scanner without accepting a reading (back/cancel), or the flow ended without
     * a confirmed result. Carries nothing.
     */
    public data object Cancelled : MrzScannerResult
}
