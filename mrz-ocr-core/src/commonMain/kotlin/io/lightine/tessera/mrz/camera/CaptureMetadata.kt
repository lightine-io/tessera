package io.lightine.tessera.mrz.camera

/**
 * Capture metadata (EXIF) read from a saved image, as the file reports it.
 *
 * Every field is self-reported by the file and the SDK does not verify it. The type holds no
 * screenshot or edited verdict. It is filled only when a [SavedImageMrzReader] has a
 * [CaptureMetadataReader] and a policy other than [CaptureMetadataPolicy.NONE]. It holds personal
 * data (location, device fields, raw tags): do not log it verbatim.
 *
 * @since 0.3.0
 */
public data class CaptureMetadata(
    /** The raw EXIF `DateTimeOriginal` string, or `null` if absent. */
    public val reportedCaptureTime: String?,
    /** The device make the file reports (EXIF `Make`), or `null`. */
    public val deviceMake: String?,
    /** The device model the file reports (EXIF `Model`), or `null`. */
    public val deviceModel: String?,
    /** The software the file reports (EXIF `Software`), or `null`. */
    public val software: String?,
    /**
     * The GPS location the file reports, or `null`.
     *
     * Set only when the read used [CaptureMetadataPolicy.INCLUDING_LOCATION] and the file has the tag.
     */
    public val location: GeoLocation?,
    /**
     * The non-GPS EXIF tags collected, by tag name; `null` if none were found.
     *
     * The tags come from a fixed list that differs by platform.
     */
    public val rawTags: Map<String, String>?,
)

/**
 * A GPS coordinate from a saved image's EXIF. Precise location: do not log verbatim.
 *
 * @since 0.3.0
 */
public data class GeoLocation(
    /** Latitude in decimal degrees (positive north). */
    public val latitude: Double,
    /** Longitude in decimal degrees (positive east). */
    public val longitude: Double,
)

/**
 * How much capture metadata a [SavedImageMrzReader] reads from a saved image.
 *
 * The reader does not read the GPS tags unless [INCLUDING_LOCATION] is chosen.
 *
 * @since 0.3.0
 */
public enum class CaptureMetadataPolicy {
    /** Reads no capture metadata. The default. */
    NONE,

    /** Reads the timestamp, device, software, and raw tags. Never reads GPS. */
    EXCLUDING_LOCATION,

    /** Reads the above and the GPS location into [CaptureMetadata.location]. */
    INCLUDING_LOCATION,
}
