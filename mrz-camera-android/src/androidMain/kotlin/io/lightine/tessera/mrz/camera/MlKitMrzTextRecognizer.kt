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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
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
    // unchanged. @Volatile because it may be set from any thread while [recognize] reads it on another (in
    // CameraXMrzScanner, a Dispatchers.Default thread, TES-205).
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
     * Returns no lines when the frame has no backing image. It does not close the frame. It returns, also
     * when cancelled, only after ML Kit has stopped reading the frame, so the caller can close it right away.
     *
     * @param frame the CameraX frame to read.
     * @return the recognized lines.
     */
    override suspend fun recognize(frame: ImageProxy): RecognizedText {
        if (bandRestricted) {
            // Crop to the MRZ band before OCR; null (no backing image) → nothing to recognize. ML Kit reads
            // a bitmap copy here, never the frame, so the caller may release the frame as soon as we return.
            val band = mrzBandBitmap(frame) ?: return RecognizedText(emptyList())
            val recognized = recognizer.process(InputImage.fromBitmap(band, 0)).await()
            // The short-row rescue (MlKitRowRescue): when ML Kit collapsed the filler of one MRZ row while
            // its neighbour read at exact width, the short row is read once more from the same band bitmap,
            // across the neighbour's width. At most one extra ML Kit call, and only on such frames.
            return MlKitRowRescue.recognizedText(
                recognizer = recognizer,
                lines = recognized.textBlocks.flatMap { block -> block.lines },
                imageWidth = band.width,
                imageHeight = band.height,
                maxRereads = MAX_LIVE_REREADS,
                image = { band },
            )
        }
        // Whole-frame reading (the released headless path): ML Kit rotates via rotationDegrees. ML Kit
        // reads the frame's own media.Image, so we return (also when cancelled) only after ML Kit is
        // done with it: the caller closes the frame when we return (TES-220). No rescue here: it would need
        // a bitmap copy of every frame, and the headless reading stays as it was.
        val mediaImage = frame.image ?: return RecognizedText(emptyList())
        val input = InputImage.fromMediaImage(mediaImage, frame.imageInfo.rotationDegrees)
        val recognized = recognizer.process(input).awaitWhileReadingInput()
        return RecognizedText(
            recognized.textBlocks
                .flatMap { block -> block.lines }
                .map { line -> RecognizedLine(text = line.text, confidence = line.confidence) },
        )
    }

    // Builds a bitmap of just the MRZ band. ML Kit has no region-of-interest, so the frame is cropped
    // here: toBitmap() yields the frame in *sensor* orientation, which is rotated upright (honouring
    // rotationDegrees) so "the bottom of the document" is the bottom of the bitmap, then the bottom band is
    // cropped; it is read with rotation 0 (already upright). Returns null when the producer already released
    // the backing image (nothing to recognize). The intermediate bitmaps are per-frame and short-lived (ML
    // Kit copies the band's pixels), so they are left to GC rather than eagerly recycled — the frame is
    // throttled + KEEP_ONLY_LATEST, matching the module's no-frame-held-in-state hygiene.
    private fun mrzBandBitmap(frame: ImageProxy): Bitmap? {
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
        return Bitmap.createBitmap(upright, 0, top, upright.width, height)
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

        // The live path's cost cap for the short-row rescue: at most this many extra ML Kit calls per frame.
        internal const val MAX_LIVE_REREADS: Int = 1

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
// reuses the same bridge. ML Kit runs the work on its own threads. Listeners added without an executor
// "will be called on the main application thread"
// (https://developers.google.com/android/reference/com/google/android/gms/tasks/Task), so they are added
// with a direct executor instead: they run on the thread that completes the task, and resuming the
// continuation only hands control back to the caller's dispatcher (Dispatchers.Default in
// CameraXMrzScanner, TES-205). ML Kit's completion never waits in the main thread's queue.
internal suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener(DirectExecutor) { result -> continuation.resume(result) }
        addOnFailureListener(DirectExecutor) { error -> continuation.resumeWithException(error) }
        addOnCanceledListener(DirectExecutor) { continuation.cancel() }
    }

// Like [await], but when the caller is cancelled it still waits for the task to finish before rethrowing,
// bounded by [maxWaitAfterCancelMillis]. For a task that reads a frame the caller releases once we return:
// ML Kit's InputImage.fromMediaImage says "Please wait for the detector to finish processing this InputImage
// before you close the underlying Image"
// (https://developers.google.com/android/reference/com/google/mlkit/vision/common/InputImage), and ML Kit's
// CameraX guide closes the ImageProxy in the Task's OnCompleteListener
// (https://developers.google.com/ml-kit/vision/text-recognition/v2/android). Cancelling our wait does not
// cancel the task, so without this a cancelled scan closed the frame while ML Kit could still be reading it
// (TES-220). The wait suspends (it never blocks a thread), and it is bounded so a task that never completes
// cannot hold the frame forever: past the bound the frame is released anyway, as before.
internal suspend fun <T> Task<T>.awaitWhileReadingInput(maxWaitAfterCancelMillis: Long = MAX_WAIT_AFTER_CANCEL_MILLIS): T =
    try {
        await()
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) { withTimeoutOrNull(maxWaitAfterCancelMillis) { awaitCompletion() } }
        throw cancelled
    }

// Suspends until the task completes in any way (success, failure, or cancelled). Ignores the result.
private suspend fun Task<*>.awaitCompletion() {
    if (isComplete) return
    suspendCancellableCoroutine { continuation -> addOnCompleteListener(DirectExecutor) { continuation.resume(Unit) } }
}

// How long a cancelled recognition waits for ML Kit to let go of the frame. One OCR pass takes well under a
// second on a phone, so this only cuts in when the task is stuck.
internal const val MAX_WAIT_AFTER_CANCEL_MILLIS: Long = 2_000

// Runs a task listener on the thread that completes the task. Each listener only resumes a continuation.
private val DirectExecutor = Executor(Runnable::run)
