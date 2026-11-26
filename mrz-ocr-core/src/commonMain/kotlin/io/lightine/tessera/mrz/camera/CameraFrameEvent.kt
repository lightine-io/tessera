package io.lightine.tessera.mrz.camera

import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.types.vocabulary.MrzFormat

/**
 * Telemetry emitted once per analysed frame, with diagnostics and no document data.
 *
 * It holds counts, booleans, a coarse confidence, the detected format, an outcome, how many fillers
 * the reader added to a short name line, and how many rows came from a second read. It never holds
 * recognized text or a parsed field.
 *
 * @since 0.2.0
 */
public class CameraFrameEvent private constructor(
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
    /**
     * How many `<` fillers the reader added at the end of a name line that was read short; `0` when
     * no line was padded. It is a count only, never the line or its text. See
     * [MrzFillerPadding.addedCount].
     *
     * @since 1.0.0
     */
    public val fillerPaddingAdded: Int = 0,
    /**
     * How many rows of this frame came from a second read; `0` when none did.
     *
     * @since 1.1.0
     */
    public val secondReadRows: Int = 0,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : TelemetryEvent {
    /** Creates an event without second-read rows. */
    public constructor(
        outcome: CameraFrameOutcome,
        recognizedLineCount: Int,
        mrzRegionFound: Boolean,
        ocrConfidence: Float?,
        detectedFormat: MrzFormat?,
        fillerPaddingAdded: Int = 0,
    ) : this(outcome, recognizedLineCount, mrzRegionFound, ocrConfidence, detectedFormat, fillerPaddingAdded, 0, Unit)

    /** Creates an event with an explicit second-read row count. */
    public constructor(
        outcome: CameraFrameOutcome,
        recognizedLineCount: Int,
        mrzRegionFound: Boolean,
        ocrConfidence: Float?,
        detectedFormat: MrzFormat?,
        fillerPaddingAdded: Int = 0,
        secondReadRows: Int,
    ) : this(
        outcome,
        recognizedLineCount,
        mrzRegionFound,
        ocrConfidence,
        detectedFormat,
        fillerPaddingAdded,
        secondReadRows,
        Unit,
    )

    public operator fun component1(): CameraFrameOutcome = outcome

    public operator fun component2(): Int = recognizedLineCount

    public operator fun component3(): Boolean = mrzRegionFound

    public operator fun component4(): Float? = ocrConfidence

    public operator fun component5(): MrzFormat? = detectedFormat

    public operator fun component6(): Int = fillerPaddingAdded

    public operator fun component7(): Int = secondReadRows

    /** Copies this event while preserving its second-read row count. */
    public fun copy(
        outcome: CameraFrameOutcome = this.outcome,
        recognizedLineCount: Int = this.recognizedLineCount,
        mrzRegionFound: Boolean = this.mrzRegionFound,
        ocrConfidence: Float? = this.ocrConfidence,
        detectedFormat: MrzFormat? = this.detectedFormat,
        fillerPaddingAdded: Int = this.fillerPaddingAdded,
    ): CameraFrameEvent =
        CameraFrameEvent(
            outcome,
            recognizedLineCount,
            mrzRegionFound,
            ocrConfidence,
            detectedFormat,
            fillerPaddingAdded,
            secondReadRows,
        )

    /** Copies this event with an explicit second-read row count. */
    public fun copy(
        outcome: CameraFrameOutcome = this.outcome,
        recognizedLineCount: Int = this.recognizedLineCount,
        mrzRegionFound: Boolean = this.mrzRegionFound,
        ocrConfidence: Float? = this.ocrConfidence,
        detectedFormat: MrzFormat? = this.detectedFormat,
        fillerPaddingAdded: Int = this.fillerPaddingAdded,
        secondReadRows: Int,
    ): CameraFrameEvent =
        CameraFrameEvent(
            outcome,
            recognizedLineCount,
            mrzRegionFound,
            ocrConfidence,
            detectedFormat,
            fillerPaddingAdded,
            secondReadRows,
        )

    override fun equals(other: Any?): Boolean =
        other is CameraFrameEvent &&
            outcome == other.outcome &&
            recognizedLineCount == other.recognizedLineCount &&
            mrzRegionFound == other.mrzRegionFound &&
            ocrConfidence == other.ocrConfidence &&
            detectedFormat == other.detectedFormat &&
            fillerPaddingAdded == other.fillerPaddingAdded &&
            secondReadRows == other.secondReadRows

    override fun hashCode(): Int {
        var result = outcome.hashCode()
        result = 31 * result + recognizedLineCount
        result = 31 * result + mrzRegionFound.hashCode()
        result = 31 * result + (ocrConfidence?.hashCode() ?: 0)
        result = 31 * result + (detectedFormat?.hashCode() ?: 0)
        result = 31 * result + fillerPaddingAdded
        result = 31 * result + secondReadRows
        return result
    }

    override fun toString(): String =
        "CameraFrameEvent(outcome=$outcome, recognizedLineCount=$recognizedLineCount, mrzRegionFound=$mrzRegionFound, ocrConfidence=$ocrConfidence, detectedFormat=$detectedFormat, fillerPaddingAdded=$fillerPaddingAdded, secondReadRows=$secondReadRows)"

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
