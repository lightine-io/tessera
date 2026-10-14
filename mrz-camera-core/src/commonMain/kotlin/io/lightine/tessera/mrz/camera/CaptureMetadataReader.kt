package io.lightine.tessera.mrz.camera

/**
 * Reads capture metadata (EXIF) from a saved image.
 *
 * @param F the saved-image input type, the same file reference the [MrzTextRecognizer] reads.
 * @since 0.3.0
 */
public fun interface CaptureMetadataReader<in F> {
    /**
     * Reads the metadata [policy] permits from [image]; absent fields are `null`.
     *
     * Under [CaptureMetadataPolicy.EXCLUDING_LOCATION] an implementation must not read the GPS tags.
     * An unreadable or malformed image gives a [CaptureMetadata] with `null` fields and no exception.
     * Only coroutine cancellation propagates. [SavedImageMrzReader] does not catch exceptions from
     * this call.
     *
     * @param image the saved image to read.
     * @param policy how much metadata to read. [SavedImageMrzReader] never passes
     *   [CaptureMetadataPolicy.NONE].
     * @return the metadata read from [image].
     */
    public suspend fun read(
        image: F,
        policy: CaptureMetadataPolicy,
    ): CaptureMetadata
}
