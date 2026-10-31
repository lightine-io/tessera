package io.lightine.tessera.mrz.camera

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
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
        // The short-row rescue (MlKitRowRescue): a short MRZ row next to an exact-width row is read again
        // across that row's width. The image is decoded a second time only when a re-read is planned (ML Kit
        // keeps its own decoded bitmap private); its upright size must equal the first pass's, or the
        // rescue is skipped.
        return MlKitRowRescue.recognizedText(
            recognizer = recognizer,
            lines = recognized.textBlocks.flatMap { block -> block.lines },
            imageWidth = input.width,
            imageHeight = input.height,
            maxRereads = Int.MAX_VALUE,
            image = { withContext(Dispatchers.IO) { decodeUpright(appContext.contentResolver, frame) } },
        )
    }

    /** Releases the underlying ML Kit recognizer. */
    override fun close() {
        recognizer.close()
    }
}

// Decodes [uri] the way ML Kit's InputImage.fromFilePath does: the full image, turned upright by its EXIF
// orientation (read for content and file URIs only, as ML Kit does). Null when it cannot be decoded.
private fun decodeUpright(
    resolver: ContentResolver,
    uri: Uri,
): Bitmap? {
    val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
    val exif =
        if (uri.scheme == ContentResolver.SCHEME_CONTENT || uri.scheme == ContentResolver.SCHEME_FILE) {
            resolver.openInputStream(uri)?.use { ExifInterface(it) }
        } else {
            null
        }
    val matrix = uprightMatrix(exif?.rotationDegrees ?: 0, exif?.isFlipped ?: false) ?: return raw
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
}

// The matrix that turns a raw image upright: mirror first when the EXIF orientation is flipped, then rotate.
// Null when the image is already upright.
private fun uprightMatrix(
    rotationDegrees: Int,
    flipped: Boolean,
): Matrix? {
    if (rotationDegrees == 0 && !flipped) return null
    return Matrix().apply {
        if (flipped) postScale(-1f, 1f)
        postRotate(rotationDegrees.toFloat())
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
