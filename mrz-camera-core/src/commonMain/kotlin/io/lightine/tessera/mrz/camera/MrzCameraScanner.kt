package io.lightine.tessera.mrz.camera

import kotlinx.coroutines.flow.Flow

/**
 * Runs the platform camera and streams a [MrzScanResult] for analysed frames on [results].
 *
 * The scanner draws nothing and never requests the camera permission. It reports a capture failure
 * as a [CameraError] on [results] and does not throw from the stream.
 *
 * @since 0.2.0
 */
public interface MrzCameraScanner {
    /**
     * The stream of results for analysed frames, including capture errors.
     *
     * Frames that arrive while one is being analysed are dropped, so results are not one per frame.
     * The flow is hot: it emits only between [start] and [stop], replays nothing, and never completes.
     * A terminal [MrzScanResult.CaptureError] ends the session and the flow then emits nothing until
     * the next [start]; a non-terminal one leaves the session running. See [CameraError] for which
     * members are terminal. Each platform scanner states the thread its recognizer runs on.
     */
    public val results: Flow<MrzScanResult>

    /**
     * Starts the camera session and begins emitting on [results].
     *
     * Does nothing while a session is running. After [stop], or after a terminal capture error, it
     * starts a new session. The caller must hold the camera permission first. Call it from one thread,
     * typically the UI thread; concurrent lifecycle calls are not supported.
     */
    public fun start()

    /**
     * Stops the camera session, so [results] emits nothing until the next [start].
     *
     * Does nothing when the session is stopped. Releasing the camera resources is the platform type's
     * `close`.
     */
    public fun stop()
}
