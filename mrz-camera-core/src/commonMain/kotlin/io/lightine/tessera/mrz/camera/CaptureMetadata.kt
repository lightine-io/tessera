package io.lightine.tessera.mrz.camera

/**
 * Capture metadata read from a saved image's embedded EXIF, exposed to help a consumer assess saved-image
 * risk ([reading-risks](https://lightine.youtrack.cloud/articles/TES-A-11)).
 *
 * **Every field is self-reported by the file and unverifiable by the SDK** — EXIF can be absent, stripped,
 * or fabricated, so a present value is what the file *claims*, not a fact the SDK establishes (Principle 4).
 * There is deliberately **no `isScreenshot` / `isEdited` verdict**: those are not reliable EXIF facts, and
 * synthesizing one would be the SDK deciding (reader-not-oracle). The consumer infers from the raw fields
 * ([software], the absence of camera fields, [rawTags]). See [ADR-023](https://lightine.youtrack.cloud/articles/TES-A-63).
 *
 * Only populated when a [SavedImageMrzReader] is given a [CaptureMetadataReader] and a non-`NONE`
 * [CaptureMetadataPolicy]; [location] only when that policy is [CaptureMetadataPolicy.INCLUDING_LOCATION].
 *
 * **Contains PII — do not log verbatim.** [location] is precise geolocation; the device fields and [rawTags]
 * are identifying. Redact before logging.
 */
public data class CaptureMetadata(
    /** The capture timestamp the file reports — the raw EXIF `DateTimeOriginal` string — or `null` if absent. Self-reported. */
    public val reportedCaptureTime: String?,
    /** The capturing device make the file reports (EXIF `Make`), or `null`. Self-reported. */
    public val deviceMake: String?,
    /** The capturing device model the file reports (EXIF `Model`), or `null`. Self-reported. */
    public val deviceModel: String?,
    /** The software the file reports having processed it (EXIF `Software`), or `null`. A hint of editing, never proof. */
    public val software: String?,
    /**
     * The GPS location the file reports, or `null`. **Precise-location PII.** Non-`null` only when the read
     * used [CaptureMetadataPolicy.INCLUDING_LOCATION] and the tag was present; under the other policies the
     * GPS bytes are not read at all.
     */
    public val location: GeoLocation?,
    /**
     * The raw EXIF tags the reader collected (tag name → raw string value), for full transparency
     * (Principle 5), or `null` if not collected. May itself hold identifying data — treat as PII.
     */
    public val rawTags: Map<String, String>?,
)

/** A GPS coordinate from a saved image's EXIF. **Precise-location PII — do not log verbatim.** */
public data class GeoLocation(
    /** Latitude in decimal degrees (positive north). */
    public val latitude: Double,
    /** Longitude in decimal degrees (positive east). */
    public val longitude: Double,
)

/**
 * How much capture metadata a [SavedImageMrzReader] reads from a saved image — the opt-in-within-opt-in gate
 * for the PII in EXIF ([ADR-023](https://lightine.youtrack.cloud/articles/TES-A-63)). The contract is
 * **read-suppression, not field-suppression**: the GPS bytes are never read unless [INCLUDING_LOCATION] is
 * chosen, rather than read-and-then-nulled.
 */
public enum class CaptureMetadataPolicy {
    /** Read no capture metadata at all (the default). No EXIF is read. */
    NONE,

    /** Read non-location metadata (timestamp, device, software); GPS is never read. */
    EXCLUDING_LOCATION,

    /** Read the above plus GPS ([CaptureMetadata.location]) — precise-location PII; opt-in-within-opt-in. */
    INCLUDING_LOCATION,
}
