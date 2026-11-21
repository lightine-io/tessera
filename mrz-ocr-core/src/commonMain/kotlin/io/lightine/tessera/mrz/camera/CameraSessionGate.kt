package io.lightine.tessera.mrz.camera

import kotlinx.coroutines.Job
import kotlin.concurrent.Volatile

/**
 * Tracks the one running camera-session [Job] of a [MrzCameraScanner].
 *
 * The gate clears the job when the session ends for any reason, cancelled by [stop] or finished on
 * its own, so [start] works again. [start] and [stop] are not thread-safe: call them from one
 * lifecycle thread.
 *
 * @since 0.2.0
 */
public class CameraSessionGate {
    @Volatile
    private var job: Job? = null

    /** `true` while a session is running. */
    public val isRunning: Boolean
        get() = job != null

    /**
     * Starts a session with [launch] when idle; does nothing while one is running.
     *
     * @param launch starts the session and returns its [Job].
     */
    public fun start(launch: () -> Job) {
        if (job != null) return
        val started = launch()
        job = started
        started.invokeOnCompletion { if (job === started) job = null }
    }

    /** Cancels the running session, if any. Does nothing when idle. */
    public fun stop() {
        job?.cancel()
        job = null
    }
}
