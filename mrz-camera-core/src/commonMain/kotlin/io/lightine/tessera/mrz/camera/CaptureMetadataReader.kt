package io.lightine.tessera.mrz.camera

/**
 * The seam for reading capture metadata (EXIF) from a saved image — the metadata sibling of the OCR
 * [MrzTextRecognizer] ([ADR-023](https://lightine.youtrack.cloud/articles/TES-A-63)). A platform
 * implementation reads the image reference's embedded metadata (Android `androidx.exifinterface`, iOS
 * ImageIO) **honoring the [CaptureMetadataPolicy]**: under [CaptureMetadataPolicy.EXCLUDING_LOCATION] it must
 * never read the GPS tags (read-suppression — the GPS bytes never transit memory unless requested). A
 * [SavedImageMrzReader] never calls it with [CaptureMetadataPolicy.NONE] (no metadata read at all).
 *
 * @param F the saved-image input type — the same file reference the [MrzTextRecognizer] reads.
 */
public fun interface CaptureMetadataReader<in F> {
    /**
     * Reads the metadata permitted by [policy] from [image]; individual fields are `null` when absent in the
     * file.
     *
     * **Implementations must be best-effort and non-throwing**: an unreadable or malformed image yields a
     * [CaptureMetadata] with `null` fields, never a thrown error (a missing tag is not a read failure). Only
     * coroutine cancellation ([kotlin.coroutines.cancellation.CancellationException]) propagates. The bundled
     * platform readers honour this, and [SavedImageMrzReader] does **not** wrap this call — so a custom
     * implementation that throws would propagate to the `read()` caller.
     */
    public suspend fun read(
        image: F,
        policy: CaptureMetadataPolicy,
    ): CaptureMetadata
}
