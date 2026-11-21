// Sample for the page TES-A-95, Read capture metadata from a saved image on Android: https://lightine.youtrack.cloud/articles/TES-A-95. Region text matches the page; code outside regions only makes it compile.
package samples.tesa95

import android.content.Context
import android.net.Uri

// region TES-A-95 block 2
import io.lightine.tessera.mrz.camera.CaptureMetadataPolicy
import io.lightine.tessera.mrz.camera.SavedImageMrzReader
import io.lightine.tessera.mrz.camera.SavedImageReadingAcknowledgement
import io.lightine.tessera.mrz.camera.exifCaptureMetadataReader
import io.lightine.tessera.mrz.camera.mlKitSavedImageRecognizer
// endregion

internal suspend fun readMetadata(context: Context, uri: Uri) {
    // region TES-A-95 block 3
    val acknowledgement = SavedImageReadingAcknowledgement()
    val reader = SavedImageMrzReader(
        acknowledgement = acknowledgement,
        recognizer = mlKitSavedImageRecognizer(acknowledgement, context),
        metadataReader = exifCaptureMetadataReader(acknowledgement, context),
        metadataPolicy = CaptureMetadataPolicy.EXCLUDING_LOCATION,
    )
    // endregion

    // region TES-A-95 block 4
    val metadata = reader.use { it.read(uri) }.captureMetadata
    val device = metadata?.let { "${it.deviceMake} ${it.deviceModel}" }
    // endregion
}

internal fun configure(context: Context, acknowledgement: SavedImageReadingAcknowledgement) {
    SavedImageMrzReader(
        acknowledgement = acknowledgement,
        recognizer = mlKitSavedImageRecognizer(acknowledgement, context),
        metadataReader = exifCaptureMetadataReader(acknowledgement, context),
        // region TES-A-95 block 5
        metadataPolicy = CaptureMetadataPolicy.INCLUDING_LOCATION   // precise location: ask only if you need it
        // endregion
    )
}
