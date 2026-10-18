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
 * the camera path - it reads a saved-image [Uri] instead of a camera frame. Obtained from the gated
 * factory [mlKitSavedImageRecognizer]; the class is `internal` so the only way to get one is through that
 * factory, which requires the saved-image-reading acknowledgement (the public constructor cannot bypass it).
 *
 * It decodes the [Uri] with `InputImage.fromFilePath` (which reads the file and applies its EXIF orientation)
 * on [Dispatchers.IO], then runs ML Kit. A decode/read failure propagates as an exception, which
 * [MrzFrameAnalyzer] catches and surfaces as a typed [`CameraError.OcrFailed`][CameraError.OcrFailed] (the
 * OCR-seam contract - recognizers signal failure by throwing). Owns its ML Kit [TextRecognizer]; release via [close].
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
 * Creates the Android saved-image OCR recognizer, which reads an image [Uri] with ML Kit.
 *
 * Pass it to [SavedImageMrzReader], which closes it when the reader is closed. A decode or read
 * failure is thrown, and [MrzFrameAnalyzer] reports it as [CameraError.OcrFailed].
 *
 * @param acknowledgement the required saved-image acknowledgement. It has no default.
 * @param context any [Context]; the recognizer keeps its application context.
 * @return the recognizer.
 * @since 0.3.0
 */
public fun mlKitSavedImageRecognizer(
    acknowledgement: SavedImageReadingAcknowledgement,
    context: Context,
): MrzTextRecognizer<Uri> = MlKitSavedImageRecognizer(context.applicationContext)
