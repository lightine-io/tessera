package io.lightine.tessera.mrz.camera

import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.types.vocabulary.MrzFormat

/**
 * Telemetry emitted once per analysed frame. Camera reading is the first SDK component to emit
 * telemetry — the [`TelemetrySink`][io.lightine.tessera.telemetry.TelemetrySink] contract has
 * shipped since 0.1.0 with no emitters
 * ([ADR-015](https://lightine.youtrack.cloud/articles/TES-A-48)).
 *
 * Carries **diagnostics only and no document data**: counts, booleans, a coarse confidence, the
 * detected format, and an outcome label. It never includes recognized text or any parsed field, so a
 * sink can be wired to any log/metrics backend without leaking PII.
 */
public data class CameraFrameEvent(
    /** What analysing the frame produced. */
    public val outcome: CameraFrameOutcome,
    /** How many text lines OCR recognized in the frame. */
    public val recognizedLineCount: Int,
    /** Whether an MRZ-shaped candidate was located. */
    public val mrzRegionFound: Boolean,
    /** Coarse aggregate OCR confidence in `[0, 1]`, or `null` if unavailable. */
    public val ocrConfidence: Float?,
    /** The MRZ format detected when [outcome] is [CameraFrameOutcome.DECODED]; otherwise `null`. */
    public val detectedFormat: MrzFormat?,
) : TelemetryEvent {
    override val name: String get() = EVENT_NAME

    public companion object {
        /** The stable [`TelemetryEvent.name`][TelemetryEvent.name] for this event type. */
        public const val EVENT_NAME: String = "mrz.camera.frame"
    }
}

/** The outcome of analysing a single frame, for telemetry routing. */
public enum class CameraFrameOutcome {
    /** A candidate was located and run through the parser (the parse verdict may still be a failure). */
    DECODED,

    /** OCR ran but located no MRZ-shaped candidate. */
    NO_MRZ_FOUND,

    /** The OCR step failed; see [`CameraError.OcrFailed`][CameraError.OcrFailed]. */
    OCR_FAILED,
}
