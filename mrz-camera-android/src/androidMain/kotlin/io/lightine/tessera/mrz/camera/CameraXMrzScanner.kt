package io.lightine.tessera.mrz.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs CameraX on Android and streams [MrzScanResult]s from analysed frames.
 *
 * It binds an [ImageAnalysis] use case to [lifecycleOwner] with the keep-only-latest strategy, and
 * closes every frame after analysis. Frames that arrive while one is being analysed are dropped, so
 * results are not one per frame. The scanner creates no preview unless you call [enablePreview]. The
 * caller must hold the `CAMERA` permission before [start]; the scanner reports
 * [CameraError.PermissionDenied] and never requests it.
 *
 * A [CameraError.PermissionDenied] or a critical [CameraError.CameraUnavailable] ends the session.
 * A [CameraError.CameraInUse], and a [CameraError.CameraUnavailable] reported while CameraX is
 * retrying, leave the session bound; the stream resumes when the camera recovers. A
 * [CameraError.OcrFailed] covers one frame. Close the scanner to release the analysis executor and
 * the recognizer.
 *
 * @param appContext an application [Context], used to look up the camera provider. Pass
 *   `context.applicationContext`.
 * @param lifecycleOwner the lifecycle the camera session is bound to. CameraX also starts and
 *   stops the camera as this lifecycle moves.
 * @param recognizer the OCR engine. The default is [MlKitMrzTextRecognizer]. The scanner closes a
 *   recognizer that is [AutoCloseable] when it is closed.
 * @param mode how tolerant candidate extraction is of OCR formatting noise. The default is
 *   [ParsingMode.STRICT].
 * @param telemetry where per-frame [CameraFrameEvent]s go. The default is the registered sink
 *   ([TelemetrySinkRegistry.current][TelemetrySinkRegistry]), or [NoOpTelemetrySink] when none is
 *   registered.
 * @param cameraSelector which lens to use. The default is the back camera.
 * @since 0.2.0
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

    /**
     * Results for analysed frames and capture errors. A slow collector misses the oldest pending
     * results. The scanner calls the recognizer on the main thread, so a custom
     * [MrzTextRecognizer] runs there.
     */
    override val results: Flow<MrzScanResult> = mutableResults.asSharedFlow()

    // Opt-in live-preview seam (additive; the scanner is headless by default — ADR-020). Read at bind
    // time in cameraFrames(): when armed, a CameraX Preview use case is bound in the SAME session as the
    // analysis use case and its SurfaceRequest is published on [surfaceRequest] for a viewfinder to draw.
    // @Volatile because enablePreview() may be called from any thread; the bind reads it on Dispatchers.Main.
    @Volatile private var previewEnabled = false

    // Optional viewport for preview↔analysis WYSIWYG: when set (with preview armed), the two use cases bind
    // in a UseCaseGroup with this ViewPort, so CameraX crops both to the SAME sensor region — what the
    // viewfinder shows is what the analyzer sees. Without it, CameraX may give the two different crops, so a
    // region-of-interest on the analysis frame would not match the on-screen guide. Set via [enablePreview].
    @Volatile private var previewViewport: ViewPort? = null
    private val mutableSurfaceRequest = MutableStateFlow<SurfaceRequest?>(null)

    /**
     * The CameraX [SurfaceRequest] of the live preview, or `null` when no preview is active.
     *
     * It is set once the camera binds after [enablePreview] and [start], and returns to `null` when
     * the session ends.
     *
     * @since 0.5.0
     */
    public val surfaceRequest: StateFlow<SurfaceRequest?> = mutableSurfaceRequest.asStateFlow()

    /**
     * Arms the live preview for later [start] calls.
     *
     * The next session binds a CameraX [Preview] next to analysis and publishes its [SurfaceRequest]
     * on [surfaceRequest]. Call it before [start]. Preview stays armed for the life of the scanner.
     *
     * @param viewport when set, preview and analysis bind in one [UseCaseGroup] with this [ViewPort]
     *   and crop the same sensor region. When `null` (the default), their crops can differ.
     * @since 0.5.0
     */
    public fun enablePreview(viewport: ViewPort? = null) {
        previewEnabled = true
        previewViewport = viewport
    }

    // Torch control for the default UI's torch button. The desired state is held here and applied to the bound
    // CameraX [Camera] when the session binds and on every toggle. @Volatile because [setTorchEnabled] may be
    // called from the UI thread while [boundCamera] is assigned on the bind thread. `enableTorch` is a CameraX
    // async control that does not rebind the session, so toggling never interrupts capture.
    @Volatile private var torchEnabled = false

    @Volatile private var boundCamera: Camera? = null

    /**
     * Turns the torch of the active camera on or off.
     *
     * Call it before [start] or while running. It does nothing on a camera without a flash unit. The
     * scanner keeps the last requested state and applies it each time the camera binds, so after
     * [stop] and [start] the torch is requested on again if you last requested on.
     *
     * @param enabled `true` to turn the torch on.
     * @since 0.5.0
     */
    public fun setTorchEnabled(enabled: Boolean) {
        torchEnabled = enabled
        boundCamera?.let { if (it.cameraInfo.hasFlashUnit()) it.cameraControl.enableTorch(enabled) }
    }

    /**
     * Whether the bound camera has a flash unit; `false` when no camera is bound.
     *
     * @since 0.5.0
     */
    public fun hasTorch(): Boolean = boundCamera?.cameraInfo?.hasFlashUnit() == true

    // Autofocus is intentionally left to CameraX's default continuous mode (CONTROL_AF_MODE_CONTINUOUS_PICTURE):
    // it keeps re-focusing on the scene as a handheld document moves, which is what an MRZ scan needs. An earlier
    // pass aimed a one-shot FocusMeteringAction at the MRZ band and held it with disableAutoCancel() — but a
    // one-shot lock is the wrong shape here: it fixes focus on the FIRST frame and never tracks, so a document
    // brought in too close (inside the lens minimum focus distance, ~10 cm on a typical main camera) stays soft
    // even after the user pulls back into the focusable range. Device profiling (TES-86, Galaxy S24) confirmed the
    // one-shot AF actually converged (isFocusSuccessful=true) yet the image was still blurred — the classic
    // signature of shooting nearer than the lens can focus, not an AF failure. Continuous AF tracks the pull-back;
    // the WYSIWYG viewport already centres the analysis frame on the guide box, so the default full-frame metering
    // is effectively band-weighted without a per-frame metering call. Framing guidance (the struggling hint) steers
    // the user out of the too-close range instead.

    // Tracks the in-flight session and auto-clears it on completion, so start() works again after the
    // session ends on its own (a critical CaptureError), not only after stop(). See CameraSessionGate.
    private val session = CameraSessionGate()

    /** Binds CameraX to [lifecycleOwner] and begins emitting on [results]. */
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

    /** Stops the session and unbinds this scanner's use cases. */
    override fun stop() {
        session.stop()
    }

    /** Stops the session, releases the analysis executor, and closes the recognizer if closeable. */
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
                    // Analyse above CameraX's ~640x480 default: the MRZ's small fixed-pitch glyphs need enough
                    // pixels for the OCR engine to read them reliably — at the default a 30-char line is
                    // ~16 px/char and ML Kit confused the filler `<` with `«`, failing the parse (TES-86).
                    // 1280x960 keeps the 4:3 aspect (so the analysis FOV — and the MRZ band crop — still line
                    // up with the preview) while quadrupling the pixels (~32 px/char); fall back to the closest
                    // the device supports. `setResolutionSelector` is the current API (`setTargetResolution` is
                    // deprecated in camera-core 1.6.x).
                    .setResolutionSelector(
                        ResolutionSelector
                            .Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 960),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            ).build(),
                    ).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
            analysis.setAnalyzer(analysisExecutor) { proxy ->
                // Hand the frame to the collector; if it is not ready, drop and close this frame here (a
                // dropped frame never reaches scan()'s releaseFrame). The RENDEZVOUS buffer (below) keeps
                // "not ready" to "the collector is mid-analysis" — the frame we want to drop anyway.
                if (trySendBlocking(proxy).isFailure) proxy.close()
            }

            // When preview is armed, bind a Preview use case in the SAME session (never a second camera):
            // CameraX hands it a SurfaceRequest through the surface provider, which we publish for a
            // viewfinder to fulfil. setSurfaceProvider with no executor delivers on the main thread; the
            // MutableStateFlow is thread-safe regardless. Un-armed → null, and only ImageAnalysis binds.
            val preview: Preview? =
                if (previewEnabled) {
                    Preview.Builder().build().apply {
                        setSurfaceProvider { request -> mutableSurfaceRequest.value = request }
                    }
                } else {
                    null
                }
            val useCases: List<UseCase> = listOfNotNull(preview, analysis)

            val camera =
                try {
                    // With a viewport (and a preview to align to), bind preview + analysis in a UseCaseGroup so
                    // CameraX crops BOTH to the same sensor region (WYSIWYG) — this is what lets a
                    // region-of-interest on the analysis frame line up with the on-screen framing guide.
                    // Otherwise bind the use cases directly (headless, or no viewport), preserving prior behaviour.
                    val viewport = previewViewport
                    if (preview != null && viewport != null) {
                        val group =
                            UseCaseGroup
                                .Builder()
                                .setViewPort(viewport)
                                .addUseCase(preview)
                                .addUseCase(analysis)
                                .build()
                        provider.bindToLifecycle(lifecycleOwner, cameraSelector, group)
                    } else {
                        provider.bindToLifecycle(lifecycleOwner, cameraSelector, *useCases.toTypedArray())
                    }
                } catch (failure: Exception) {
                    analysis.clearAnalyzer()
                    mutableSurfaceRequest.value = null
                    close(failure)
                    return@callbackFlow
                }
            // Hold the camera for torch control and apply the desired torch state (honours torchOnByDefault).
            boundCamera = camera
            if (camera.cameraInfo.hasFlashUnit()) camera.cameraControl.enableTorch(torchEnabled)

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
                boundCamera = null
                camera.cameraInfo.cameraState.removeObserver(stateObserver)
                analysis.clearAnalyzer()
                // Clear the published request first: the viewfinder stops drawing before we unbind, and a
                // re-arm on the next start() begins from null rather than a stale, now-invalid request.
                mutableSurfaceRequest.value = null
                // Unbind only THIS session's use cases (analysis + the optional preview), not the whole
                // provider — so a stop()-then-start() restart (whose old teardown may run after the new
                // bind, since cancellation is cooperative) cannot unbind the new session, and any other
                // use case the consumer bound to the same lifecycle is left alone. The full rapid-restart
                // lifecycle is verified on a device in the live-device slice.
                provider.unbind(*useCases.toTypedArray())
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
