package io.lightine.tessera.mrz.camera

/**
 * The OCR engine seam: turns a frame of type [F] into recognized text lines.
 *
 * An implementation runs OCR only. To report a failure it throws, and [MrzFrameAnalyzer] reports
 * that as [CameraError.OcrFailed]. Keep recognized text out of the exception message, because the
 * message becomes [CameraError.OcrFailed.message].
 *
 * The SDK bundles no OCR engine on the JVM, so a desktop app brings its own by implementing this
 * interface for its image type and passing it to [SavedImageMrzReader] or [MrzFrameAnalyzer]. The
 * app chooses [F] and supplies the reading:
 *
 * - `F` is the app's own image type, for example `java.nio.file.Path` or
 *   `java.awt.image.BufferedImage` on the JVM, or `androidx.camera.core.ImageProxy` on Android.
 * - [recognize] returns one [RecognizedLine] per physical line of text, in reading order (top to
 *   bottom). No coordinates or bounding boxes are required: the analyzer groups MRZ lines from
 *   that order, so keep each MRZ line whole in its own [RecognizedLine], and never merge two
 *   printed lines into one or split one printed line across two.
 *
 * @param F the frame type, for example `androidx.camera.core.ImageProxy` on Android or
 *   `java.nio.file.Path` on the JVM.
 * @since 0.2.0
 */
public fun interface MrzTextRecognizer<in F> {
    /**
     * Runs OCR on [frame] and returns the recognized lines in reading order (top to bottom).
     *
     * [CameraXMrzScanner] and [AVCaptureMrzScanner] call it on `Dispatchers.Default`, never on the
     * main thread; [MrzFrameAnalyzer.analyse] calls it on the caller's coroutine context.
     *
     * @param frame the frame to read.
     * @return the recognized lines.
     */
    public suspend fun recognize(frame: F): RecognizedText
}
