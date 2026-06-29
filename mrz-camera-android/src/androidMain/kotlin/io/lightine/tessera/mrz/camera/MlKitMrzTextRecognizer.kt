package io.lightine.tessera.mrz.camera

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
 * The Android [MrzTextRecognizer], backed by ML Kit Text Recognition (the **bundled** Latin model —
 * no Play Services, no network). It reads each CameraX [ImageProxy], runs ML Kit, and returns the
 * recognized lines for [MrzFrameAnalyzer] to turn into an MRZ candidate. It makes no trust decision
 * about what it reads — OCR only.
 *
 * **Frame ownership.** This reads the [ImageProxy] but never closes it; the caller that produced the
 * frame (CameraX's `ImageAnalysis.Analyzer`, wired up by the owns-the-camera-session layer) closes it
 * after analysis, exactly as CameraX requires. It does, however, own the ML Kit [TextRecognizer] it
 * holds: call [close] to release it (this type is [AutoCloseable]).
 *
 * @param recognizer the underlying ML Kit recognizer. Defaults to the bundled Latin recognizer; a
 *   custom one may be injected (for example a script-specific model).
 */
public class MlKitMrzTextRecognizer(
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
) : MrzTextRecognizer<ImageProxy>,
    AutoCloseable {
    override suspend fun recognize(frame: ImageProxy): RecognizedText {
        // No backing media image (the producer may have released it): nothing to recognize.
        val mediaImage = frame.image ?: return RecognizedText(emptyList())
        val input = InputImage.fromMediaImage(mediaImage, frame.imageInfo.rotationDegrees)
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
