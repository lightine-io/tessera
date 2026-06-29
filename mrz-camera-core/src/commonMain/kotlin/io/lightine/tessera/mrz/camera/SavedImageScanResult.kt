package io.lightine.tessera.mrz.camera

/**
 * The outcome of reading an MRZ from a single saved image ([SavedImageMrzReader.read]).
 *
 * It **composes** the same [MrzScanResult] the live-camera path produces, exposed as [scan]: a saved-image
 * MRZ runs through the identical parse/validate pipeline and validates identically to a camera-sourced or
 * typed-in one — only the read-method provenance differs ([FrameProvenance.PRE_CAPTURED_IMAGE]). Reusing
 * the locked camera result keeps the contract stable; this wrapper is the saved-image-specific result type
 * that later carries the additional saved-image channels (tolerant-mode candidates and capture metadata).
 *
 * **Contains document PII — do not log verbatim.** [scan] holds the parsed document fields and the raw OCR
 * text, so this type's generated `toString()` surfaces personal data; branch on [scan] and log only non-PII
 * fields (see the warning on [MrzScanResult]). The SDK itself never logs these values.
 */
public data class SavedImageScanResult(
    /** The MRZ read outcome — `Decoded` / `NoMrzFound` / `CaptureError`, identical in shape to the camera path. */
    public val scan: MrzScanResult,
)
