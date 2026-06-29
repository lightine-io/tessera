package io.lightine.tessera.mrz.camera

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import platform.Vision.VNImageRequestHandler

/**
 * The iOS saved-image [MrzTextRecognizer], backed by Apple Vision — the saved-image sibling of the camera
 * [VisionMrzTextRecognizer]. It builds a Vision image-request handler from a saved-image file [NSURL] and
 * runs the **same** Vision text-recognition pipeline the camera path uses, reusing
 * [VisionMrzTextRecognizer]'s internal still-image seam (`.accurate` recognition, **no** language correction
 * so the MRZ characters are never altered, results ordered top-to-bottom). Obtained from the gated factory
 * [visionSavedImageRecognizer]; the class is `internal` so it is only reachable through that factory, which
 * requires the saved-image-reading acknowledgement (the public constructor cannot bypass it).
 *
 * iOS reads saved images from a file [NSURL] only in 0.3.0: the URL-based handler reads the file and applies
 * its EXIF orientation, whereas Vision's `NSData` initializer assumes an upright image — so an `NSData`
 * overload is deferred ([ADR-023](https://lightine.youtrack.cloud/articles/TES-A-63)). A Vision failure
 * throws, which [MrzFrameAnalyzer] catches and surfaces as [`CameraError.OcrFailed`][CameraError.OcrFailed]
 * (the OCR-seam contract).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class VisionSavedImageRecognizer(
    private val vision: VisionMrzTextRecognizer = VisionMrzTextRecognizer(),
) : MrzTextRecognizer<NSURL>,
    AutoCloseable {
    override suspend fun recognize(frame: NSURL): RecognizedText =
        // Vision's file read + recognition is blocking and CPU-bound; run it off the caller's (possibly main)
        // thread so a consumer calling read() from a UI coroutine does not freeze. The saved-image path has no
        // owns-the-session layer to do this, unlike the camera path (which drives Vision on Dispatchers.Default
        // — see AVCaptureMrzScanner); Dispatchers.IO is JVM-only, so Default is the Kotlin/Native equivalent.
        // Mirrors the Android recognizer's off-caller-thread recognize().
        withContext(Dispatchers.Default) {
            // Drain Vision's per-frame autoreleased objects (handler, results, observations) on this
            // run-loop-less analysis thread, exactly as the camera recognizer does.
            autoreleasepool {
                vision.recognize(VNImageRequestHandler(uRL = frame, options = emptyMap<Any?, Any?>()))
            }
        }

    /** Releases the underlying Vision recognizer's resources. */
    override fun close() {
        vision.close()
    }
}

/**
 * Creates the iOS saved-image OCR recognizer. Reading saved images is opt-in: this REQUIRES a
 * [SavedImageReadingAcknowledgement] (no default) — the consumer's explicit acknowledgement of the higher
 * saved-image risk (see [SavedImageMrzReader] and
 * [reading-risks](https://lightine.youtrack.cloud/articles/TES-A-11)). The returned recognizer reads a file
 * [NSURL] via Apple Vision; pass it to [SavedImageMrzReader], which releases it when the reader is closed.
 *
 * @param acknowledgement the required saved-image-reading acknowledgement (the opt-in gate).
 */
public fun visionSavedImageRecognizer(acknowledgement: SavedImageReadingAcknowledgement): MrzTextRecognizer<NSURL> =
    VisionSavedImageRecognizer()
