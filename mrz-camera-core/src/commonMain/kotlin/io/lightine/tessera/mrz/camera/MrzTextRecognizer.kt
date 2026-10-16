package io.lightine.tessera.mrz.camera

/**
 * The OCR engine seam: turns a platform frame of type [F] into recognized text lines.
 *
 * An implementation runs OCR only. To report a failure it throws, and [MrzFrameAnalyzer] reports
 * that as [CameraError.OcrFailed]. Keep recognized text out of the exception message, because the
 * message becomes [CameraError.OcrFailed.message].
 *
 * @param F the platform frame type, for example `androidx.camera.core.ImageProxy`.
 * @since 0.2.0
 */
public fun interface MrzTextRecognizer<in F> {
    /**
     * Runs OCR on [frame] and returns the recognized lines in reading order.
     *
     * [CameraXMrzScanner] and [AVCaptureMrzScanner] call it on `Dispatchers.Default`, never on the
     * main thread; [MrzFrameAnalyzer.analyse] calls it on the caller's coroutine context.
     *
     * @param frame the frame to read.
     * @return the recognized lines.
     */
    public suspend fun recognize(frame: F): RecognizedText
}
