package io.lightine.tessera.mrz.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Recognizes text in CameraX frames with ML Kit Text Recognition (the bundled Latin model).
 *
 * It reads each [ImageProxy] and never closes it: the caller closes the frame. Close the
 * recognizer to release the ML Kit client.
 *
 * @param recognizer the ML Kit client. The default is the bundled Latin recognizer.
 * @since 0.2.0
 */
public class MlKitMrzTextRecognizer(
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
) : MrzTextRecognizer<ImageProxy>,
    AutoCloseable {
    // When set, OCR runs on only the MRZ band (the bottom of the upright frame) instead of the whole frame,
    // so it reads just the machine-readable zone and not the rest of the document's printed text — which on a
    // real ID buried the MRZ among the page's prose and failed the decode (TES-86). Opt-in via
    // [restrictToMrzBand]; off by default so a headless consumer's full-frame reading is byte-for-byte
    // unchanged. @Volatile because it may be set from any thread while [recognize] reads it on the analysis
    // thread.
    @Volatile private var bandRestricted: Boolean = false

    /**
     * Limits OCR to a horizontal band in the vertical centre of the upright frame.
     *
     * The band spans the full width and is 18 percent of the frame height. When CameraX reports a
     * crop rect, the band is centred in that cropped region. Off by default: the recognizer reads
     * the whole frame. Call it before reading.
     *
     * @since 0.5.0
     */
    public fun restrictToMrzBand() {
        bandRestricted = true
    }

    /**
     * Runs ML Kit on [frame] and returns the recognized lines.
     *
     * Returns no lines when the frame has no backing image. It does not close the frame.
     *
     * @param frame the CameraX frame to read.
     * @return the recognized lines.
     */
    override suspend fun recognize(frame: ImageProxy): RecognizedText {
        val input =
            if (bandRestricted) {
                // Crop to the MRZ band before OCR; null (no backing image) → nothing to recognize.
                mrzBandInputImage(frame) ?: return RecognizedText(emptyList())
            } else {
                // Whole-frame reading (the released headless path, unchanged): ML Kit rotates via rotationDegrees.
                val mediaImage = frame.image ?: return RecognizedText(emptyList())
                InputImage.fromMediaImage(mediaImage, frame.imageInfo.rotationDegrees)
            }
        val recognized = recognizer.process(input).await()
        return RecognizedText(
            recognized.textBlocks
                .flatMap { block -> block.lines }
                .map { line -> RecognizedLine(text = line.text, confidence = line.confidence) },
        )
    }

    // Builds an InputImage of just the MRZ band. ML Kit has no region-of-interest, so the frame is cropped
    // here: toBitmap() yields the frame in *sensor* orientation, which is rotated upright (honouring
    // rotationDegrees) so "the bottom of the document" is the bottom of the bitmap, then the bottom band is
    // cropped and passed with rotation 0 (already upright). Returns null when the producer already released
    // the backing image (nothing to recognize). The intermediate bitmaps are per-frame and short-lived (ML
    // Kit copies the band's pixels), so they are left to GC rather than eagerly recycled — the frame is
    // throttled + KEEP_ONLY_LATEST, matching the module's no-frame-held-in-state hygiene.
    private fun mrzBandInputImage(frame: ImageProxy): InputImage? {
        if (frame.image == null) return null
        val full = frame.toBitmap()
        // Honour the crop rect CameraX put on the frame. With a ViewPort (UseCaseGroup), that rect is the
        // WYSIWYG region — the exact area the viewfinder shows — expressed in the FULL analysis buffer's
        // coordinates; `toBitmap()` returns the full buffer, so we apply the crop ourselves (CameraX
        // "Transform output": buffer + separate transform metadata). Without a ViewPort it defaults to the
        // whole buffer, so this is a no-op. Done in sensor orientation, before rotating upright.
        val cropped = cropToRect(full, frame.cropRect)
        val rotation = frame.imageInfo.rotationDegrees
        val upright =
            if (rotation == 0) {
                cropped
            } else {
                Bitmap.createBitmap(
                    cropped,
                    0,
                    0,
                    cropped.width,
                    cropped.height,
                    Matrix().apply { postRotate(rotation.toFloat()) },
                    true,
                )
            }
        val (top, height) = mrzBandRect(upright.height)
        val band = Bitmap.createBitmap(upright, 0, top, upright.width, height)
        return InputImage.fromBitmap(band, 0)
    }

    // Crops [bitmap] to [rect], clamped to the bitmap's bounds so an out-of-range rect can never crash (a
    // CameraX crop rect is within the buffer, but clamp defensively). Returns the bitmap unchanged when the
    // rect already covers it (the no-ViewPort default), avoiding a needless copy.
    private fun cropToRect(
        bitmap: Bitmap,
        rect: Rect,
    ): Bitmap {
        val left = rect.left.coerceIn(0, bitmap.width)
        val top = rect.top.coerceIn(0, bitmap.height)
        val width = (rect.right.coerceIn(left, bitmap.width) - left).coerceAtLeast(1)
        val height = (rect.bottom.coerceIn(top, bitmap.height) - top).coerceAtLeast(1)
        if (left == 0 && top == 0 && width == bitmap.width && height == bitmap.height) return bitmap
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    /** Releases the underlying ML Kit recognizer. */
    override fun close() {
        recognizer.close()
    }

    internal companion object {
        // The fraction of the (upright) frame height the MRZ band occupies. The band is CENTRED (the framing
        // guide sits in the middle of the preview): centre puts the MRZ in the lens's sharpest region and the
        // camera's default autofocus/exposure sweet spot, which reads faster and more reliably than a low band
        // (device-observed). Restricting OCR to it drops the rest of the page's printed text. Tuned on-device
        // (TES-86): 0.30 was far taller than the on-screen guide box and pulled in the lines just above the MRZ
        // (place of birth, blood group, the legal paragraph) — ML Kit then read 8–12 lines and the MRZ-shape
        // match failed on ~half the frames. Tightened toward the guide box so OCR sees essentially just the
        // three MRZ lines, which is what actually stabilises detection.
        internal const val MRZ_BAND_HEIGHT_FRACTION: Float = 0.18f

        // The centred band as (top, height) in pixels for a frame [frameHeight] tall. Pure, so the band math
        // is host-testable without a device (the crop itself needs a real frame and is device-verified).
        internal fun mrzBandRect(frameHeight: Int): Pair<Int, Int> {
            val height = (frameHeight * MRZ_BAND_HEIGHT_FRACTION).toInt().coerceIn(1, frameHeight)
            return ((frameHeight - height) / 2) to height
        }
    }
}

// Bridges an ML Kit Task<T> to a suspend call without depending on the Play-Services coroutines
// integration (which would pull a Play-Services artifact the bundled model otherwise avoids).
// Cancelling the coroutine cancels the awaiting continuation. `internal` so the saved-image recognizer
// reuses the same bridge.
internal suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> continuation.resume(result) }
        addOnFailureListener { error -> continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }
