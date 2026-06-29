package io.lightine.tessera.mrz.camera

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Android [CaptureMetadataReader] — reads a saved image's EXIF via `androidx.exifinterface`, honoring the
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
 * Creates the Android saved-image EXIF metadata reader. Reading saved images is opt-in: this REQUIRES a
 * [SavedImageReadingAcknowledgement] (no default). Pass the result to [SavedImageMrzReader] as its
 * `metadataReader`; it reads only what the [CaptureMetadataPolicy] permits (GPS only under
 * [CaptureMetadataPolicy.INCLUDING_LOCATION]).
 *
 * @param acknowledgement the required saved-image-reading acknowledgement (the opt-in gate).
 * @param context any [Context]; its application context is retained for opening the image `Uri`.
 */
public fun exifCaptureMetadataReader(
    acknowledgement: SavedImageReadingAcknowledgement,
    context: Context,
): CaptureMetadataReader<Uri> = ExifCaptureMetadataReader(context.applicationContext)
