package io.lightine.tessera.mrz.camera

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * The streaming heart of the owns-the-camera-session layer: turns a stream of platform [frames] into a
 * stream of [MrzScanResult]s by running each frame through this analyse-frame core, in order. This is
 * the platform-agnostic, frame-source-agnostic engine the [MrzCameraScanner]s build on — Android feeds
 * a CameraX `ImageProxy` stream, iOS an AVFoundation sample-buffer stream, and both reuse this unchanged
 * ([ADR-020](https://lightine.youtrack.cloud/articles/TES-A-49)).
 * Because it is a pure `Flow` transform over the injected analyzer, it is fully host-testable with a
 * fake frame `Flow` and a mock recognizer — no device.
 *
 * Each frame is analysed and then released through [releaseFrame] (even if [analyse][MrzFrameAnalyzer.analyse]
 * threw, which it does only for coroutine cancellation — OCR and parse problems become result values, not
 * exceptions). The core itself never closes or retains a frame; this engine is where the produced frame's
 * lifecycle ends, which on Android is `ImageProxy::close` — without it CameraX cannot deliver the next
 * frame (memory hygiene; the analyse-frame core's "caller owns the frame" contract).
 *
 * **Capture failures, surfaced — never thrown.** If the [frames] source itself fails (the camera could
 * not start: unavailable, permission denied, in use), [captureErrorFor] maps the failure to a typed
 * [CameraError] and it is emitted as a terminal [`MrzScanResult.CaptureError`][MrzScanResult.CaptureError],
 * keeping every outcome on the one [results][MrzCameraScanner.results] stream (ADR-020: capture errors are
 * a sealed result, not an exception). A `null` from [captureErrorFor] means "not a capture error" and the
 * cause is re-thrown unchanged — that is the default, so a bare `scan(frames)` keeps ordinary `Flow`
 * exception semantics and coroutine cancellation always propagates.
 *
 * @param frames the live frame stream; failure of this flow is treated as a capture-availability failure.
 * @param releaseFrame releases a frame once analysed (e.g. `ImageProxy::close`); default no-op for sources
 *   whose frames need no explicit release.
 * @param captureErrorFor maps a [frames] failure to the [CameraError] to surface, or `null` to re-throw it.
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
