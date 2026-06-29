package io.lightine.tessera.mrz.camera

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreImage.CIImage
import platform.Foundation.NSNumber
import platform.Foundation.NSURL

/**
 * The iOS [CaptureMetadataReader] — reads a saved image's EXIF from its `CIImage.properties` dictionary
 * (Apple's image-metadata dictionary, the same structure ImageIO's `CGImageSource` exposes), honoring the
 * [CaptureMetadataPolicy]. Obtained from the gated factory [imageIoCaptureMetadataReader]; the class is
 * `internal` so it is only reachable through that factory, which requires the acknowledgement.
 *
 * **Read-suppression, not field-suppression.** Under [CaptureMetadataPolicy.EXCLUDING_LOCATION] the GPS
 * dictionary is never read, and the raw-tag map never contains GPS; only
 * [CaptureMetadataPolicy.INCLUDING_LOCATION] reads location. Metadata is **best-effort**: an unreadable file
 * or absent tags yield `null` fields, never an error.
 *
 * The metadata-assembly logic (dictionary navigation, GPS sign-from-reference, suppression) is the pure
 * [assembleCaptureMetadata] — unit-tested with synthetic property maps. This class is only the thin platform
 * read: load the image's properties and hand them to that function.
 */
@OptIn(ExperimentalForeignApi::class)
internal class ImageIoCaptureMetadataReader : CaptureMetadataReader<NSURL> {
    override suspend fun read(
        image: NSURL,
        policy: CaptureMetadataPolicy,
    ): CaptureMetadata =
        // The CIImage load reads and decodes the file; run it off the caller's (possibly main) thread so a
        // consumer calling read() from a UI coroutine does not block. Dispatchers.IO is JVM-only, so Default is
        // the Kotlin/Native equivalent (matching the camera path and the saved-image OCR recognizer; the
        // Android reader makes the same off-caller-thread guarantee via Dispatchers.IO).
        withContext(Dispatchers.Default) {
            assembleCaptureMetadata(CIImage.imageWithContentsOfURL(image)?.properties, policy)
        }
}

/**
 * Builds a [CaptureMetadata] from an Apple image-properties dictionary ([CIImage.properties] /
 * `CGImageSourceCopyPropertiesAtIndex`), honoring [policy]. Pure and platform-data-shaped so it is
 * unit-testable without an image: the keys below are the documented, stable `kCGImageProperty…` *values*
 * (e.g. `kCGImagePropertyExifDictionary` == `"{Exif}"`). GPS is read only under
 * [CaptureMetadataPolicy.INCLUDING_LOCATION] (read-suppression) and the raw-tag map excludes GPS.
 */
internal fun assembleCaptureMetadata(
    properties: Map<Any?, *>?,
    policy: CaptureMetadataPolicy,
): CaptureMetadata {
    val exif = properties?.dictionary(EXIF_DICTIONARY)
    val tiff = properties?.dictionary(TIFF_DICTIONARY)
    val location =
        if (policy == CaptureMetadataPolicy.INCLUDING_LOCATION) {
            properties?.dictionary(GPS_DICTIONARY)?.toGeoLocation()
        } else {
            null // read-suppression: the GPS dictionary is not read unless explicitly requested
        }
    // Raw tags for transparency — a curated, non-GPS allowlist (the same descriptive set the Android reader
    // collects), not an open-ended dump of every Exif/TIFF field.
    val rawTags = rawStringTags(exif, tiff)
    return CaptureMetadata(
        reportedCaptureTime = exif?.string(EXIF_DATETIME_ORIGINAL),
        deviceMake = tiff?.string(TIFF_MAKE),
        deviceModel = tiff?.string(TIFF_MODEL),
        software = tiff?.string(TIFF_SOFTWARE),
        location = location,
        rawTags = rawTags.ifEmpty { null },
    )
}

// Documented kCGImageProperty… constant VALUES (stable EXIF/TIFF/GPS standard names). Used as literals so
// the lookups stay pure Kotlin String comparisons against the bridged properties Map — no CFString-constant
// bridging. (That the platform actually presents these keys/this structure is confirmed by device test.)
private const val EXIF_DICTIONARY = "{Exif}"
private const val GPS_DICTIONARY = "{GPS}"
private const val TIFF_DICTIONARY = "{TIFF}"
private const val EXIF_DATETIME_ORIGINAL = "DateTimeOriginal"
private const val TIFF_MAKE = "Make"
private const val TIFF_MODEL = "Model"
private const val TIFF_SOFTWARE = "Software"
private const val GPS_LATITUDE = "Latitude"
private const val GPS_LATITUDE_REF = "LatitudeRef"
private const val GPS_LONGITUDE = "Longitude"
private const val GPS_LONGITUDE_REF = "LongitudeRef"

// Curated, non-GPS raw-tag allowlist — the descriptive EXIF/TIFF tags the Android reader collects (its
// RAW_TAGS), so rawTags stays a small, predictable, cross-platform set rather than an open-ended dump of every
// Exif/TIFF field (which would surface arbitrary identifying tags: lens, owner, body serial, exposure). GPS is
// excluded here and read-suppressed above. Both platforms' native dimension keys are listed (iOS Exif
// PixelX/YDimension, Android/TIFF ImageWidth/ImageLength) so each surfaces image dimensions.
private val RAW_TAG_ALLOWLIST =
    setOf(
        "DateTimeOriginal",
        "DateTime",
        "Make",
        "Model",
        "Software",
        "Orientation",
        "PixelXDimension",
        "PixelYDimension",
        "ImageWidth",
        "ImageLength",
    )

@Suppress("UNCHECKED_CAST")
private fun Map<Any?, *>.dictionary(key: String): Map<Any?, *>? = this[key] as? Map<Any?, *>

private fun Map<Any?, *>.string(key: String): String? = this[key] as? String

// EXIF/CIImage numeric values arrive as NSNumber; tolerate plain Kotlin numbers too (synthetic test data).
private fun Any?.asDouble(): Double? =
    (this as? NSNumber)?.doubleValue ?: (this as? Double) ?: (this as? Float)?.toDouble() ?: (this as? Int)?.toDouble()

// GPS stores positive magnitudes plus a hemisphere reference (S → negative latitude, W → negative longitude).
private fun Map<Any?, *>.toGeoLocation(): GeoLocation? {
    val latitude = this[GPS_LATITUDE].asDouble() ?: return null
    val longitude = this[GPS_LONGITUDE].asDouble() ?: return null
    return GeoLocation(
        latitude = if ((this[GPS_LATITUDE_REF] as? String) == "S") -latitude else latitude,
        longitude = if ((this[GPS_LONGITUDE_REF] as? String) == "W") -longitude else longitude,
    )
}

private fun rawStringTags(vararg dictionaries: Map<Any?, *>?): Map<String, String> =
    buildMap {
        dictionaries.filterNotNull().forEach { dictionary ->
            dictionary.forEach { (key, value) ->
                if (key is String && key in RAW_TAG_ALLOWLIST && (value is String || value is NSNumber)) {
                    put(key, value.toString())
                }
            }
        }
    }

/**
 * Creates the iOS saved-image EXIF metadata reader. Reading saved images is opt-in: this REQUIRES a
 * [SavedImageReadingAcknowledgement] (no default). Pass the result to [SavedImageMrzReader] as its
 * `metadataReader`; it reads only what the [CaptureMetadataPolicy] permits (GPS only under
 * [CaptureMetadataPolicy.INCLUDING_LOCATION]).
 *
 * @param acknowledgement the required saved-image-reading acknowledgement (the opt-in gate).
 */
public fun imageIoCaptureMetadataReader(acknowledgement: SavedImageReadingAcknowledgement): CaptureMetadataReader<NSURL> =
    ImageIoCaptureMetadataReader()
