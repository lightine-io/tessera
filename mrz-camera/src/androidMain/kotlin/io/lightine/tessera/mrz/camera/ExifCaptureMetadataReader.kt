package io.lightine.tessera.mrz.camera

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Android [CaptureMetadataReader] - reads a saved image's EXIF via `androidx.exifinterface`, honoring the
 * [CaptureMetadataPolicy]. Obtained from the gated factory [exifCaptureMetadataReader]; the class is
 * `internal` so it is only reachable through that factory, which requires the saved-image-reading acknowledgement.
 *
 * **Read-suppression, not field-suppression.** Under [CaptureMetadataPolicy.EXCLUDING_LOCATION] the GPS tags
 * are never read (`getLatLong` is not called) and the raw-tag map never contains GPS; only
 * [CaptureMetadataPolicy.INCLUDING_LOCATION] reads location. Metadata is **best-effort**: an unreadable or
 * malformed file yields all-`null` fields rather than an error (a missing tag is not a read failure).
 */
internal class ExifCaptureMetadataReader(
    private val appContext: Context,
) : CaptureMetadataReader<Uri> {
    override suspend fun read(
        image: Uri,
        policy: CaptureMetadataPolicy,
    ): CaptureMetadata =
        withContext(Dispatchers.IO) {
            try {
                appContext.contentResolver.openInputStream(image)?.use { stream ->
                    readExif(ExifInterface(stream), policy)
                } ?: EMPTY
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // Best-effort: a malformed/unreadable EXIF means no metadata, never a crash.
                EMPTY
            }
        }

    private fun readExif(
        exif: ExifInterface,
        policy: CaptureMetadataPolicy,
    ): CaptureMetadata {
        val location =
            if (policy == CaptureMetadataPolicy.INCLUDING_LOCATION) {
                exif.latLong?.let { GeoLocation(latitude = it[0], longitude = it[1]) }
            } else {
                null // read-suppression: the GPS tags are never read unless explicitly requested
            }
        val rawTags = RAW_TAGS.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }.toMap()
        return CaptureMetadata(
            reportedCaptureTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            deviceMake = exif.getAttribute(ExifInterface.TAG_MAKE),
            deviceModel = exif.getAttribute(ExifInterface.TAG_MODEL),
            software = exif.getAttribute(ExifInterface.TAG_SOFTWARE),
            location = location,
            rawTags = rawTags.ifEmpty { null },
        )
    }

    private companion object {
        private val EMPTY = CaptureMetadata(null, null, null, null, null, null)

        // Non-GPS EXIF tags collected into the raw-tag map for transparency. GPS tags are deliberately
        // excluded — GPS is surfaced only via the gated [CaptureMetadata.location] under INCLUDING_LOCATION.
        private val RAW_TAGS =
            listOf(
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_SOFTWARE,
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.TAG_IMAGE_WIDTH,
                ExifInterface.TAG_IMAGE_LENGTH,
            )
    }
}

/**
 * Creates the Android reader for the EXIF of a saved image [Uri].
 *
 * Pass it to [SavedImageMrzReader] as `metadataReader`. It reads GPS only under
 * [CaptureMetadataPolicy.INCLUDING_LOCATION]. [CaptureMetadata.rawTags] holds up to eight non-GPS
 * tags: `DateTimeOriginal`, `DateTime`, `Make`, `Model`, `Software`, `Orientation`, `ImageWidth`,
 * and `ImageLength`. An unreadable or malformed image gives all-`null` fields.
 *
 * @param acknowledgement the required saved-image acknowledgement. It has no default.
 * @param context any [Context]; the reader keeps its application context.
 * @return the reader.
 * @since 0.3.0
 */
public fun exifCaptureMetadataReader(
    acknowledgement: SavedImageReadingAcknowledgement,
    context: Context,
): CaptureMetadataReader<Uri> = ExifCaptureMetadataReader(context.applicationContext)
