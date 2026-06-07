package io.lightine.tessera.mrz.camera

import kotlinx.coroutines.Job
import kotlin.concurrent.Volatile

/**
 * Tracks the single in-flight camera-session [Job] behind a [MrzCameraScanner], so the scanner's
 * start/stop contract holds even when the session ends on its own. It is a small building block for
 * the analyse-frame layer: the bundled [CameraXMrzScanner]/[AVCaptureMrzScanner] use it, and a consumer
 * driving [MrzFrameAnalyzer.scan] as their own owns-session scanner can reuse the same bookkeeping.
 *
 * **The bug this prevents.** A scanner that nulls its job field only in `stop()` stays "running"
 * forever after the results flow completes by itself — e.g. a *terminal* `CaptureError` ends the
 * stream without anyone calling `stop()`. A later `start()` then sees a non-null job and no-ops,
 * silently breaking the documented prompt-and-retry path (`PermissionDenied` → prompt → `start()`).
 * This gate clears the job when the session completes for ANY reason — cancellation via [stop] or
 * natural completion — so [start] works again afterward.
 *
 * **Threading.** [start] and [stop] are not thread-safe by themselves; call them from a single
 * lifecycle thread (the contract on [MrzCameraScanner]). The completion handler, however, runs on an
 * arbitrary thread — [Job.invokeOnCompletion] gives no dispatcher guarantee — so the tracked job is
 * `@Volatile` for cross-thread visibility (notably on Kotlin/Native, where the iOS scanner completes
 * on `Dispatchers.Default` while lifecycle calls are main-thread), and the handler clears the field
 * only when it still refers to the job it was registered for. That identity guard means a completing
 * *old* session can never clear a freshly [start]ed *new* one.
 */
public class CameraSessionGate {
    @Volatile
    private var job: Job? = null

    /** True while a session is running. */
    public val isRunning: Boolean
        get() = job != null

    /**
     * Starts a session via [launch] when idle; a no-op while one is already running. The launched
     * [Job] is tracked and auto-cleared on completion, so a later [start] after the session ends —
     * cancelled via [stop] or completed on its own — begins a fresh session.
     */
    public fun start(launch: () -> Job) {
        if (job != null) return
        val started = launch()
        job = started
        started.invokeOnCompletion { if (job === started) job = null }
    }

    /** Cancels the running session, if any, and clears the gate. Idempotent. */
    public fun stop() {
        job?.cancel()
        job = null
    }
}
