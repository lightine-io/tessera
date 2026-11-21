package io.lightine.tessera.mrz.camera

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Turns a stream of platform [frames] into [MrzScanResult]s, one per frame, in order.
 *
 * It calls [releaseFrame] on each frame after analysis. If [frames] fails, [captureErrorFor] maps
 * the failure to a [CameraError], which is emitted as a final [MrzScanResult.CaptureError] and ends
 * the flow. A `null` mapping rethrows the failure. Coroutine cancellation propagates.
 *
 * @param frames the frame stream. A failure of this flow is treated as a capture failure.
 * @param releaseFrame releases a frame after it is analysed, for example `ImageProxy::close`. The
 *   default does nothing.
 * @param captureErrorFor maps a [frames] failure to the [CameraError] to report, or `null` to
 *   rethrow it. The default rethrows.
 * @return the results, one per frame the flow delivers.
 */
public fun <F> MrzFrameAnalyzer<F>.scan(
    frames: Flow<F>,
    releaseFrame: (F) -> Unit = {},
    captureErrorFor: (Throwable) -> CameraError? = { null },
): Flow<MrzScanResult> =
    frames
        .map { frame ->
            try {
                analyse(frame)
            } finally {
                releaseFrame(frame)
            }
        }.catch { cause ->
            val error = captureErrorFor(cause) ?: throw cause
            emit(
                MrzScanResult.CaptureError(
                    error = error,
                    quality = ScanQuality(mrzRegionFound = false, ocrConfidence = null, recognizedLineCount = 0),
                ),
            )
        }
