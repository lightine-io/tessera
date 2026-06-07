package io.lightine.tessera.mrz.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.common.util.concurrent.ListenableFuture
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.telemetry.TelemetrySinkRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Android owns-the-camera-session scanner: runs CameraX internally and streams a [MrzScanResult] per
 * analysed frame, so the consumer never touches `ImageAnalysis` / `bindToLifecycle`
 * ([ADR-020](https://github.com/lightine-io/tessera/blob/main/docs/decisions/0020-camera-reading-architecture.md)).
 * It binds an [ImageAnalysis] use case (back-pressure [STRATEGY_KEEP_ONLY_LATEST][ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST]
 * — only the newest frame is analysed; intermediate frames are dropped, which is exactly what a live MRZ
 * scan wants) to the supplied [lifecycleOwner], pipes each `ImageProxy` through [MrzFrameAnalyzer.scan],
 * and closes the proxy once analysed (CameraX cannot deliver the next frame until the current one is
 * closed — memory hygiene).
 *
 * **Headless.** No preview surface is created; a consumer that wants one attaches its own. **The consumer
 * holds the `CAMERA` permission** before [start] — this scanner reports a [CameraError.PermissionDenied]
 * on [results], it never requests permission (`scope.md` "permission boundary").
 *
 * **Verification status.** The contract this implements (the [MrzCameraScanner] interface and the
 * [scan][MrzFrameAnalyzer.scan] engine) is host-tested in `mrz-camera-core`, and the
 * recoverable-vs-critical state classification ([classifyCameraState]) is host-tested here in
 * `androidHostTest`. The CameraX wiring is compiled on CI and was verified end-to-end on a physical
 * device (the live-device slice): the back camera opens, frames stream, each [ImageProxy] is closed, and
 * results flow. That slice established that CameraX surfaces a failed open **asynchronously through camera
 * state**, not as a bind-time exception. CameraX classifies those state errors as *critical* (no
 * auto-recovery — permission / fatal hardware / camera removed) or *recoverable* (the camera is in use, or
 * the open can be retried — CameraX keeps retrying, parking the camera in `PENDING_OPEN`). This scanner
 * ends the session only on a **critical** error (close the flow → terminal [CaptureError][MrzScanResult.CaptureError]);
 * a **recoverable** one it surfaces as a *non-terminal* [CameraError.CameraInUse] /
 * [CameraError.CameraUnavailable] while staying bound, so CameraX recovers and the stream resumes when the
 * blocker clears. The permission/fatal (critical) path was device-verified to surface
 * [CameraError.PermissionDenied]; the live in-use (recoverable) scenario — a second client taking then
 * releasing the camera — is device-verified separately (the Simulator/emulator cannot stage camera
 * contention; see `docs/open-questions.md`).
 *
 * @param appContext an application [Context] (held for the camera-provider lookup; pass
 *   `context.applicationContext` to avoid leaking an Activity).
 * @param lifecycleOwner the lifecycle the camera session is bound to; CameraX starts/stops the camera as
 *   this lifecycle moves, in addition to [start]/[stop].
 * @param recognizer the OCR seam. Defaults to the bundled ML Kit recognizer, which this scanner then owns
 *   and releases on [close]; a consumer-supplied recognizer that is [AutoCloseable] is likewise released
 *   on [close], since the scanner owns the OCR resource for its session.
 * @param mode strict (default) or lenient candidate extraction, forwarded to the analyse-frame core.
 * @param telemetry where per-frame [CameraFrameEvent]s go. Defaults to the application's registered sink
 *   ([TelemetrySinkRegistry.current][TelemetrySinkRegistry]; [NoOpTelemetrySink] when none is registered).
 *   Pass an explicit sink to override per instance.
 * @param cameraSelector which lens to use; defaults to the back camera (documents face the rear lens).
 */
public class CameraXMrzScanner(
    private val appContext: Context,
    private val lifecycleOwner: LifecycleOwner,
    recognizer: MrzTextRecognizer<ImageProxy> = MlKitMrzTextRecognizer(),
    mode: ParsingMode = ParsingMode.STRICT,
    telemetry: TelemetrySink = TelemetrySinkRegistry.current,
    private val cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
) : MrzCameraScanner,
    AutoCloseable {
    private val analyzer = MrzFrameAnalyzer(recognizer, mode, telemetry)
    private val ownedRecognizer = recognizer as? AutoCloseable

    // CameraX binds on the main thread; the analysis callbacks run on a dedicated single-thread executor.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    // Hot result stream, alive across start/stop. extraBufferCapacity + DROP_OLDEST means a slow collector
    // never suspends the analysis pipeline — it just misses the oldest pending result (the next frame is
    // milliseconds away), matching the "drop stale frames" intent of KEEP_ONLY_LATEST.
    private val mutableResults =
        MutableSharedFlow<MrzScanResult>(
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val results: Flow<MrzScanResult> = mutableResults.asSharedFlow()

    // Tracks the in-flight session and auto-clears it on completion, so start() works again after the
    // stream ends on its own (a terminal CaptureError), not only after stop(). See CameraSessionGate.
    private val session = CameraSessionGate()

    override fun start() {
        session.start {
            scope.launch {
                analyzer
                    .scan(
                        frames = cameraFrames(),
                        releaseFrame = ImageProxy::close,
                        captureErrorFor = ::cameraErrorFor,
                    ).collect(mutableResults::emit)
            }
        }
    }

    override fun stop() {
        session.stop()
    }

    /** Stops the session, releases the analysis executor, and closes the OCR recognizer it owns. */
    override fun close() {
        stop()
        scope.cancel()
        analysisExecutor.shutdown()
        ownedRecognizer?.close()
    }

    // CameraX's ImageAnalysis.Analyzer callback (an executor callback, not a coroutine) bridged to a Flow.
    // bindToLifecycle/unbindAll run on the collecting coroutine, which is on the main dispatcher. Binding
    // failures close the flow with the cause, which scan() maps to a CameraError via cameraErrorFor.
    private fun cameraFrames(): Flow<ImageProxy> =
        callbackFlow {
            val provider =
                try {
                    ProcessCameraProvider.getInstance(appContext).await()
                } catch (failure: Exception) {
                    close(failure)
                    return@callbackFlow
                }

            val analysis =
                ImageAnalysis
                    .Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
            analysis.setAnalyzer(analysisExecutor) { proxy ->
                // Hand the frame to the collector; if it is not ready, drop and close this frame here (a
                // dropped frame never reaches scan()'s releaseFrame). The RENDEZVOUS buffer (below) keeps
                // "not ready" to "the collector is mid-analysis" — the frame we want to drop anyway.
                if (trySendBlocking(proxy).isFailure) proxy.close()
            }

            val camera =
                try {
                    provider.bindToLifecycle(lifecycleOwner, cameraSelector, analysis)
                } catch (failure: Exception) {
                    analysis.clearAnalyzer()
                    close(failure)
                    return@callbackFlow
                }

            // CameraX surfaces a failed camera open (permission denied, camera in use, hardware fault)
            // asynchronously through camera STATE, not as a bind-time exception. How we surface it follows
            // CameraX's own recoverable/critical split (see classifyCameraState):
            //   • CRITICAL (permission, fatal hardware, camera removed): no auto-recovery, so close the
            //     flow with the code — scan()'s catch -> cameraErrorFor maps it to a TERMINAL CaptureError
            //     and the session tears down (awaitClose unbinds). The device-verified permission/fatal path.
            //   • RECOVERABLE (in-use / max-in-use / other-recoverable): CameraX retries the open itself,
            //     parking the camera in PENDING_OPEN until the blocker clears. Closing here would defeat that
            //     recovery, so instead emit a NON-TERMINAL CaptureError as an observation, keep the session
            //     bound, and let CameraX resume streaming when the camera reopens (reader-not-oracle: report
            //     the in-use observation; do not decide a transient condition ends the session).
            // A recoverable code can be re-reported on successive state ticks while parked, so emit it once
            // per occurrence (lastRecoverableCode) and clear that latch when the camera recovers (a state
            // with no error) so a later contention re-emits.
            var lastRecoverableCode: Int? = null
            val stateObserver =
                Observer<CameraState> { state ->
                    val stateError = state.error
                    if (stateError == null) {
                        lastRecoverableCode = null
                        return@Observer
                    }
                    when (val decision = classifyCameraState(stateError.code, hasCameraPermission())) {
                        is CameraStateDecision.Terminal -> {
                            close(CameraStateException(stateError.code))
                        }

                        is CameraStateDecision.Recoverable -> {
                            if (stateError.code != lastRecoverableCode) {
                                lastRecoverableCode = stateError.code
                                mutableResults.tryEmit(
                                    MrzScanResult.CaptureError(
                                        error = decision.error,
                                        quality = ScanQuality(mrzRegionFound = false, ocrConfidence = null, recognizedLineCount = 0),
                                    ),
                                )
                            }
                        }
                    }
                }
            camera.cameraInfo.cameraState.observe(lifecycleOwner, stateObserver)

            awaitClose {
                camera.cameraInfo.cameraState.removeObserver(stateObserver)
                analysis.clearAnalyzer()
                // Unbind only THIS session's use case, not the whole provider — so a stop()-then-start()
                // restart (whose old teardown may run after the new bind, since cancellation is
                // cooperative) cannot unbind the new session, and any other use case the consumer bound
                // to the same lifecycle is left alone. The full rapid-restart lifecycle is verified on a
                // device in the live-device slice.
                provider.unbind(analysis)
            }
            // RENDEZVOUS (zero-capacity): a send completes only when the collector actually receives the
            // frame, so a successfully-sent ImageProxy is, by that same instant, in scan()'s map{} where the
            // finally{ releaseFrame } owns its close(). There is no buffered-but-unreceived state, so no
            // onUndeliveredElement safety net is needed here — the analogue the iOS scanner's capacity-1
            // DROP_OLDEST channel does need, since that channel can hold/evict a frame the collector never took.
        }.buffer(Channel.RENDEZVOUS)

    // Maps a camera-open failure that CLOSED the flow to a typed CameraError. Only terminal failures reach
    // here: a CameraStateException carries the androidx CameraState code of a *critical* state error (the
    // recoverable codes are surfaced non-terminally in cameraFrames and never close the flow), so its
    // classifyCameraState decision is a Terminal whose .error is the right CameraError; a SecurityException
    // would be a synchronous bind-time throw (kept for completeness, though on the devices tested CameraX
    // does not throw it — it collapses to a CameraState error).
    private fun cameraErrorFor(cause: Throwable): CameraError? =
        when (cause) {
            is CameraStateException -> classifyCameraState(cause.code, hasCameraPermission()).error
            is SecurityException -> CameraError.PermissionDenied(cause.message ?: "CAMERA permission not granted")
            else -> CameraError.CameraUnavailable(cause.message ?: cause.toString())
        }

    private fun hasCameraPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}

// The recoverable/critical decision for an androidx [CameraState] error code, per CameraX's own
// classification (https://developer.android.com/reference/androidx/camera/core/CameraState):
//   • RECOVERABLE — ERROR_CAMERA_IN_USE (2), ERROR_MAX_CAMERAS_IN_USE (1), ERROR_OTHER_RECOVERABLE_ERROR
//     (3): CameraX retries the open automatically and parks the camera in PENDING_OPEN until the blocker
//     clears. The scanner surfaces these as a non-terminal observation and stays bound so CameraX recovers.
//   • CRITICAL — everything else (ERROR_STREAM_CONFIG, ERROR_CAMERA_DISABLED, ERROR_CAMERA_FATAL_ERROR,
//     ERROR_DO_NOT_DISTURB_MODE_ENABLED, ERROR_CAMERA_REMOVED): no auto-recovery; terminal.
// CameraX collapses a PERMISSION denial into the critical ERROR_CAMERA_FATAL_ERROR with a null cause —
// its public state API does not distinguish "no permission" from a hardware fault — so for a critical
// code we read the observable CAMERA permission: not held ⇒ PermissionDenied is the consumer's actionable
// cause; otherwise the cause is genuinely unknown to us ⇒ CameraUnavailable. This only *reads* permission,
// never requests or gates on it (scope.md permission boundary; reader-not-oracle: report the observable
// fact, never infer a cause we cannot see).
//
// `internal` + top-level, taking the permission state as a plain Boolean rather than reading it, so it is
// a pure function the androidHostTest exercises across every code without a live camera — the Android
// counterpart of the iOS AVCaptureMrzScannerErrorMappingTest.
internal fun classifyCameraState(
    code: Int,
    hasCameraPermission: Boolean,
): CameraStateDecision =
    when (code) {
        CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> {
            CameraStateDecision.Recoverable(
                CameraError.CameraInUse("camera is in use by another client (camera state error $code)"),
            )
        }

        CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> {
            CameraStateDecision.Recoverable(
                CameraError.CameraUnavailable("camera temporarily unavailable; the platform is retrying (camera state error $code)"),
            )
        }

        else -> {
            if (!hasCameraPermission) {
                CameraStateDecision.Terminal(
                    CameraError.PermissionDenied("CAMERA permission not granted (camera state error $code)"),
                )
            } else {
                CameraStateDecision.Terminal(
                    CameraError.CameraUnavailable("camera could not be opened (camera state error $code)"),
                )
            }
        }
    }

// How [CameraXMrzScanner] surfaces a [CameraState] error, per CameraX's recoverable/critical split.
internal sealed interface CameraStateDecision {
    val error: CameraError

    /**
     * CameraX is auto-retrying (a recoverable code): surface [error] as a non-terminal observation and
     * keep the session bound so it can recover.
     */
    data class Recoverable(
        override val error: CameraError,
    ) : CameraStateDecision

    /** No auto-recovery (a critical code): surface [error] and end the session. */
    data class Terminal(
        override val error: CameraError,
    ) : CameraStateDecision
}

// Carries the androidx [CameraState] error code from a *critical* (terminal) camera-open failure so
// [CameraXMrzScanner.cameraErrorFor] can map it to a [CameraError] through scan()'s catch path.
private class CameraStateException(
    val code: Int,
) : Exception("camera state error: $code")

// Bridges a Guava ListenableFuture (CameraX's provider lookup) to a suspend call without the
// kotlinx-coroutines-guava artifact — the same suspendCancellableCoroutine pattern the ML Kit Task
// bridge uses. The listener runs on the thread that completes the future; resuming the continuation
// from there is safe and the coroutine resumes on its own dispatcher.
private suspend fun <T> ListenableFuture<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addListener({
            try {
                continuation.resume(get())
            } catch (failure: ExecutionException) {
                continuation.resumeWithException(failure.cause ?: failure)
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            }
        }, Runnable::run)
        continuation.invokeOnCancellation { cancel(false) }
    }
