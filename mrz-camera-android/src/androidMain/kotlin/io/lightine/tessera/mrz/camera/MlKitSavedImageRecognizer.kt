package io.lightine.tessera.mrz.camera

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Android saved-image [MrzTextRecognizer], backed by the same bundled-model ML Kit Text Recognition as
 * the camera path — it just reads a saved-image [Uri] instead of a camera frame. Obtained from the gated
 * factory [mlKitSavedImageRecognizer]; the class is `internal` so the only way to get one is through that
 * factory, which requires the saved-image-reading acknowledgement (the public constructor cannot bypass it).
 *
 * It decodes the [Uri] with `InputImage.fromFilePath` (which reads the file and applies its EXIF orientation)
 * on [Dispatchers.IO], then runs ML Kit. A decode/read failure propagates as an exception, which
 * [MrzFrameAnalyzer] catches and surfaces as a typed [`CameraError.OcrFailed`][CameraError.OcrFailed] (the
 * OCR-seam contract — recognizers signal failure by throwing). Owns its ML Kit [TextRecognizer]; release via [close].
 */
internal class MlKitSavedImageRecognizer(
    private val appContext: Context,
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
) : MrzTextRecognizer<Uri>,
    AutoCloseable {
    override suspend fun recognize(frame: Uri): RecognizedText {
        // fromFilePath does blocking file I/O + decode, so keep it off the caller's dispatcher.
        val input = withContext(Dispatchers.IO) { InputImage.fromFilePath(appContext, frame) }
        val recognized = recognizer.process(input).await()
        return RecognizedText(
            recognized.textBlocks
                .flatMap { block -> block.lines }
                .map { line -> RecognizedLine(text = line.text, confidence = line.confidence) },
        )
    }

    /** Releases the underlying ML Kit recognizer. */
    override fun close() {
        recognizer.close()
    }
}

/**
 * Creates the Android saved-image OCR recognizer. Reading saved images is opt-in: this REQUIRES a
 * [SavedImageReadingAcknowledgement] (no default) — the consumer's explicit acknowledgement of the higher
 * saved-image risk (see [SavedImageMrzReader] and
 * [reading-risks](https://lightine.youtrack.cloud/articles/TES-A-11)). The returned recognizer reads a
 * content/file [Uri]; pass it to [SavedImageMrzReader], which releases it when the reader is closed.
 *
 * @param acknowledgement the required saved-image-reading acknowledgement (the opt-in gate).
 * @param context any [Context]; its application context is retained for `InputImage.fromFilePath`.
 */
public fun mlKitSavedImageRecognizer(
    acknowledgement: SavedImageReadingAcknowledgement,
    context: Context,
): MrzTextRecognizer<Uri> = MlKitSavedImageRecognizer(context.applicationContext)
