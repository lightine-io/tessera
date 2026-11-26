package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.parsing.ParseResult

/**
 * The outcome of analysing one frame.
 *
 * [Decoded] and [NoMrzFound] hold document data and raw OCR text, so logging a whole result leaks
 * it: log only non-PII fields such as [quality], or redact first. `toString()` on each variant
 * shows no document values; they are in the properties.
 *
 * @since 0.2.0
 */
public sealed interface MrzScanResult {
    /** The quality signals observed for this frame. Informational only. */
    public val quality: ScanQuality

    /**
     * An MRZ-shaped candidate was located and parsed.
     *
     * [parse] is the parser's own result, which can be a failure.
     *
     * @property parse the parser's result for the candidate.
     * @property recognizedText the raw OCR text the candidate came from; a short MRZ row may be the
     *   engine's second read of the same region, which the result does not mark.
     * @property quality the quality signals for this frame.
     * @property fillerPadding the `<` the reader added at the end of a name line that was read short,
     *   or `null` when every line was read at full width. When it is set, [parse] was made from the
     *   padded lines, and [recognizedText] still holds the line as read. See [MrzFillerPadding]. Added
     *   in 1.0.0.
     * @since 0.2.0
     */
    public data class Decoded(
        public val parse: ParseResult,
        public val recognizedText: RecognizedText,
        override val quality: ScanQuality,
        public val fillerPadding: MrzFillerPadding?,
    ) : MrzScanResult {
        /**
         * Creates a result with no filler padding.
         *
         * @param parse the parser's result for the candidate.
         * @param recognizedText the raw OCR text the candidate came from; a short MRZ row may be the
         *   engine's second read of the same region, which the result does not mark.
         * @param quality the quality signals for this frame.
         * @since 0.2.0
         */
        public constructor(
            parse: ParseResult,
            recognizedText: RecognizedText,
            quality: ScanQuality,
        ) : this(parse, recognizedText, quality, fillerPadding = null)

        /** The parse variant and the quality signals; never a document value. */
        override fun toString(): String =
            "Decoded(parse=${parse::class.simpleName}, quality=$quality, fillerPadding=${fillerPadding != null})"
    }

    /**
     * OCR ran and found no MRZ-shaped candidate.
     *
     * @property recognizedText whatever text the engine did read.
     * @property quality the quality signals for this frame.
     * @since 0.2.0
     */
    public data class NoMrzFound(
        public val recognizedText: RecognizedText,
        override val quality: ScanQuality,
    ) : MrzScanResult {
        /** The line count and the quality signals; never a document value. */
        override fun toString(): String = "NoMrzFound(lines=${recognizedText.lines.size}, quality=$quality)"
    }

    /**
     * The capture step failed (see [CameraError]); there is no recognized text.
     *
     * @property error what failed. [CameraError] says whether the failure ends the session.
     * @property quality the quality signals, with no region found.
     * @since 0.2.0
     */
    public data class CaptureError(
        public val error: CameraError,
        override val quality: ScanQuality,
    ) : MrzScanResult {
        /** What failed and the quality signals; never a document value. */
        override fun toString(): String = "CaptureError($error, quality=$quality)"
    }
}

/**
 * Quality signals observed while analysing a frame. The SDK never refuses a result on quality grounds.
 *
 * @since 0.2.0
 */
public data class ScanQuality(
    /** Whether an MRZ-shaped candidate was located in this frame's recognized text. */
    public val mrzRegionFound: Boolean,
    /**
     * Mean OCR confidence across recognized lines, in `[0, 1]`; `null` when no line reported one.
     */
    public val ocrConfidence: Float?,
    /** How many text lines the engine recognized in this frame. */
    public val recognizedLineCount: Int,
)
