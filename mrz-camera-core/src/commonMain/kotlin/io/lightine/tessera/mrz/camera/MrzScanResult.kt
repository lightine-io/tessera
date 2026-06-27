package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.parsing.ParseResult

/**
 * The outcome of analysing one camera frame ([MrzFrameAnalyzer.analyse]). A sealed result, surfaced
 * rather than thrown, so a per-frame loop can branch exhaustively. Every variant carries [quality]
 * — observed signals exposed as metadata, never used to gate a result
 * ([ADR-020](https://lightine.youtrack.cloud/articles/TES-A-49),
 * Principle 5).
 *
 * **Contains document PII — do not log verbatim.** A [Decoded] result holds the parsed [`ParseResult`][ParseResult]
 * (document fields) and the raw [RecognizedText]; a [NoMrzFound] result holds whatever text the engine
 * read. Their auto-generated `toString()` therefore surfaces personal data, so logging a whole
 * `MrzScanResult` in production leaks it. Branch on the variant and log only non-PII fields (e.g.
 * [quality], the [CameraError.code][CameraError]), or redact first (see the `telemetry` module's
 * redaction helpers). The SDK itself never logs these values.
 */
public sealed interface MrzScanResult {
    /** Observed quality signals for this frame. Always present; informational only. */
    public val quality: ScanQuality

    /**
     * An MRZ-shaped candidate was located in the recognized text and run through `mrz-core`. [parse]
     * is the parser's own verdict — success, partial success, or a typed parse failure — and the
     * camera layer adds no judgement of its own (a camera-sourced MRZ validates identically to a
     * typed-in one). [recognizedText] is the raw OCR the candidate came from, exposed verbatim.
     */
    public data class Decoded(
        public val parse: ParseResult,
        public val recognizedText: RecognizedText,
        override val quality: ScanQuality,
    ) : MrzScanResult

    /**
     * OCR ran but no MRZ-shaped candidate was found in the recognized text — the normal outcome for a
     * frame that does not (yet) show the document's MRZ. In a live stream the consumer simply waits
     * for the next frame. [recognizedText] is whatever the engine did read, exposed verbatim.
     */
    public data class NoMrzFound(
        public val recognizedText: RecognizedText,
        override val quality: ScanQuality,
    ) : MrzScanResult

    /**
     * The capture step itself failed (see [CameraError]); no recognized text is available. Distinct
     * from a parse failure, which is a [Decoded] result carrying a `mrz-core` error.
     */
    public data class CaptureError(
        public val error: CameraError,
        override val quality: ScanQuality,
    ) : MrzScanResult
}

/**
 * Quality signals observed while analysing a frame, exposed as metadata. The SDK never refuses to
 * return data on quality grounds (ADR-020): a consumer reads these to decide whether to prompt for a
 * better capture, but the decision — and any threshold — is the consumer's.
 */
public data class ScanQuality(
    /** Whether an MRZ-shaped candidate was located in this frame's recognized text. */
    public val mrzRegionFound: Boolean,
    /**
     * A coarse aggregate (mean) of the engine's per-line confidence across all recognized lines, in
     * `[0, 1]`, or `null` when no line reported a confidence. Indicative only, never a gate.
     */
    public val ocrConfidence: Float?,
    /** How many text lines the engine recognized in this frame. */
    public val recognizedLineCount: Int,
)
