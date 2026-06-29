package io.lightine.tessera.mrz.camera

/**
 * The outcome of reading an MRZ from a single saved image ([SavedImageMrzReader.read]).
 *
 * It **composes** the same [MrzScanResult] the live-camera path produces, exposed as [scan]: a saved-image
 * MRZ runs through the identical parse/validate pipeline and validates identically to a camera-sourced or
 * typed-in one — only the read-method provenance differs ([FrameProvenance.PRE_CAPTURED_IMAGE]). Reusing
 * the locked camera result keeps the contract stable; this wrapper is the saved-image-specific result type
 * that also carries the additional saved-image channel(s) — currently the tolerant-mode [candidates].
 *
 * **Contains document PII — do not log verbatim.** [scan] holds the parsed document fields and the raw OCR
 * text, and [candidates] hold reconstructed MRZ lines, so this type's generated `toString()` surfaces
 * personal data; branch and log only non-PII fields (see the warnings on [MrzScanResult] / [MrzCandidate]).
 * The SDK itself never logs these values.
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
)
