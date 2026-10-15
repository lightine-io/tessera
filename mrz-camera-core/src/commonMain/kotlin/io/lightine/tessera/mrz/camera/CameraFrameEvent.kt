package io.lightine.tessera.mrz.camera

import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.types.vocabulary.MrzFormat

/**
 * Telemetry emitted once per analysed frame, with diagnostics and no document data.
 *
 * It holds counts, booleans, a coarse confidence, the detected format, and an outcome. It never
 * holds recognized text or a parsed field.
 *
 * @since 0.2.0
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
    /** The MRZ format when the parse produced a document, otherwise `null` (including a failure). */
    public val detectedFormat: MrzFormat?,
) : TelemetryEvent {
    /** Event name: `mrz.camera.frame`. */
    override val name: String get() = EVENT_NAME

    /** Constants for [CameraFrameEvent]. */
    public companion object {
        /** The event name: `mrz.camera.frame`. */
        public const val EVENT_NAME: String = "mrz.camera.frame"
    }
}

/**
 * The outcome of analysing one frame, for telemetry.
 *
 * @since 0.2.0
 */
public enum class CameraFrameOutcome {
    /** A candidate was located and parsed. The parse can still be a failure. */
    DECODED,

    /** OCR ran and located no MRZ-shaped candidate. */
    NO_MRZ_FOUND,

    /** The OCR step failed; see [CameraError.OcrFailed]. */
    OCR_FAILED,
}
