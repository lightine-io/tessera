package io.lightine.tessera.mrz.camera

/**
 * The OCR seam: turns a platform camera [frame] into recognized text lines.
 *
 * This is the only platform-specific collaborator of [MrzFrameAnalyzer] and the deliberate
 * extension point named in [ADR-020](https://lightine.youtrack.cloud/articles/TES-A-49):
 * any frame source — a phone camera (Android `ImageProxy`, iOS `CMSampleBuffer`), a USB document
 * reader, a webcam, a desktop or web capture — implements this interface for its own frame type [F],
 * and the analyse-frame core works unchanged. The Android implementation wraps ML Kit Text
 * Recognition; tests inject a mock that returns canned text, which is how the core is unit-tested
 * with no device.
 *
 * Implementations perform OCR only: they neither locate the MRZ region nor parse it (the analyzer's
 * job), and they make no trust decision about what they read (Principle 1). Signal a failure to run
 * OCR by throwing — [MrzFrameAnalyzer] catches it and surfaces a typed
 * [`CameraError.OcrFailed`][CameraError.OcrFailed] rather than letting the exception propagate. Keep
 * recognized text **out of** the exception message: it becomes `CameraError.OcrFailed.message`, which
 * a consumer may log, and recognized MRZ text is PII.
 *
 * @param F the platform frame type (e.g. `androidx.camera.core.ImageProxy` on Android).
 */
public fun interface MrzTextRecognizer<in F> {
    /** Runs OCR on [frame] and returns every text line the engine recognized, in reading order. */
    public suspend fun recognize(frame: F): RecognizedText
}
