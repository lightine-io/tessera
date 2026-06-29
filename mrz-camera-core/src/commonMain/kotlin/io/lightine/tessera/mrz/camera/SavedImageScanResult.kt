package io.lightine.tessera.mrz.camera

/**
 * The outcome of reading an MRZ from a single saved image ([SavedImageMrzReader.read]).
 *
 * It **composes** the same [MrzScanResult] the live-camera path produces, exposed as [scan]: a saved-image
 * MRZ runs through the identical parse/validate pipeline and validates identically to a camera-sourced or
 * typed-in one — only the read-method provenance differs ([FrameProvenance.PRE_CAPTURED_IMAGE]). Reusing the
 * locked camera result keeps the contract stable; this wrapper is the saved-image-specific result type that
 * also carries the additional saved-image channels — the tolerant-mode [candidates] and the optional
 * [captureMetadata].
 *
 * **Contains document PII — do not log verbatim.** [scan] holds the parsed document fields and raw OCR text,
 * [candidates] hold reconstructed MRZ lines, and [captureMetadata] can hold precise GPS — so this type's
 * generated `toString()` surfaces personal data. Branch and log only non-PII fields (see the warnings on
 * [MrzScanResult] / [MrzCandidate] / [CaptureMetadata]). The SDK itself never logs these values.
 */
public data class SavedImageScanResult(
    /** The MRZ read outcome — `Decoded` / `NoMrzFound` / `CaptureError`, identical in shape to the camera path. */
    public val scan: MrzScanResult,
    /**
     * Candidate reconstructions from tolerant disambiguation, surfaced **alongside** [scan] and never
     * replacing it. **Empty** unless the read was tolerant (`SavedImageMrzReader(tolerant = true)`) and the
     * recognized MRZ had genuinely ambiguous glyphs to resolve. All distinct, parseable candidates are
     * surfaced, unranked — the consumer chooses, using each [MrzCandidate.parse] verdict. See [MrzCandidate].
     */
    public val candidates: List<MrzCandidate>,
    /**
     * Capture metadata (EXIF) read from the image, or `null` when not requested (the default
     * [CaptureMetadataPolicy.NONE], or no [CaptureMetadataReader] supplied) or unavailable. Self-reported and
     * unverifiable — see [CaptureMetadata].
     */
    public val captureMetadata: CaptureMetadata?,
)
