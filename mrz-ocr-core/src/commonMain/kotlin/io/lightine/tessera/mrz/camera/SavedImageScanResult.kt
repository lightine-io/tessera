package io.lightine.tessera.mrz.camera

/**
 * The result of reading one saved image: the scan outcome, any candidates, and capture metadata.
 *
 * [scan] has the same shape as a live-camera result and differs only in its reading method,
 * `PRE_CAPTURED_IMAGE`. The type holds document data and can hold a precise location: do not log it
 * verbatim.
 *
 * @since 0.3.0
 */
public data class SavedImageScanResult(
    /** The MRZ read outcome: `Decoded`, `NoMrzFound`, or `CaptureError`. */
    public val scan: MrzScanResult,
    /**
     * The reconstructions listed by tolerant reading, unranked and alongside [scan].
     *
     * Empty unless the read was tolerant and the recognized MRZ had ambiguous characters. See
     * [MrzCandidate] for which reconstructions are listed.
     */
    public val candidates: List<MrzCandidate>,
    /**
     * The capture metadata read from the image.
     *
     * `null` when none was requested: policy [CaptureMetadataPolicy.NONE] or no [CaptureMetadataReader].
     */
    public val captureMetadata: CaptureMetadata?,
)
