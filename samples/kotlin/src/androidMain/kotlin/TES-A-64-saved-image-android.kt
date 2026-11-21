// Sample for the page TES-A-64, Read an MRZ from a saved image on Android: https://lightine.youtrack.cloud/articles/TES-A-64. Region text matches the page; code outside regions only makes it compile.
package samples.tesa64

// region TES-A-64 block 2
import android.content.Context
import android.net.Uri
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.SavedImageMrzReader
import io.lightine.tessera.mrz.camera.SavedImageReadingAcknowledgement
import io.lightine.tessera.mrz.camera.SavedImageScanResult
import io.lightine.tessera.mrz.camera.mlKitSavedImageRecognizer
import io.lightine.tessera.mrz.parsing.ParseResult
// endregion

// region TES-A-64 block 3
suspend fun readSavedImage(context: Context, uri: Uri): SavedImageScanResult {
    val acknowledgement = SavedImageReadingAcknowledgement()
// endregion
// region TES-A-64 block 4
    return SavedImageMrzReader(
        acknowledgement = acknowledgement,
        recognizer = mlKitSavedImageRecognizer(acknowledgement, context),
    ).use { reader -> reader.read(uri) }
}
// endregion

internal suspend fun handle(context: Context, uri: Uri) {
    // region TES-A-64 block 5
    val result = readSavedImage(context, uri)   // inside a coroutine
    when (val scan = result.scan) {
        is MrzScanResult.Decoded -> when (val parse = scan.parse) {
            is ParseResult.Success -> { /* parse.document */ }
            is ParseResult.PartialSuccess -> { /* parse.document and parse.metadata.validationFailures */ }
            is ParseResult.Failure -> { /* parse.error */ }
        }
        is MrzScanResult.NoMrzFound -> { /* no MRZ in this image */ }
        is MrzScanResult.CaptureError -> { /* the file could not be read: scan.error.code */ }
    }
    // endregion
}

internal suspend fun configure(context: Context, uri: Uri, acknowledgement: SavedImageReadingAcknowledgement) {
    // region TES-A-64 block 6
    val reader = SavedImageMrzReader(
        acknowledgement = acknowledgement,
        recognizer = mlKitSavedImageRecognizer(acknowledgement, context),
        tolerant = true,
    )
    reader.use { r ->
        for (candidate in r.read(uri).candidates) {
            val failures = candidate.parse.metadata.validationFailures
            val swaps = candidate.disambiguations   // line, column, recognized, resolved
        }
    }
    // endregion
}
