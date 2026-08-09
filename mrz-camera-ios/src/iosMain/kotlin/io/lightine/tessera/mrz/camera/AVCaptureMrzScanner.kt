package io.lightine.tessera.mrz.camera

import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.telemetry.TelemetrySinkRegistry
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceDiscoverySession
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePosition
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInDualWideCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInTripleCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureFocusModeContinuousAutoFocus
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionInterruptionEndedNotification
import platform.AVFoundation.AVCaptureSessionInterruptionReasonKey
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient
import platform.AVFoundation.AVCaptureSessionRuntimeErrorNotification
import platform.AVFoundation.AVCaptureSessionWasInterruptedNotification
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.focusMode
import platform.AVFoundation.focusPointOfInterest
import platform.AVFoundation.focusPointOfInterestSupported
import platform.AVFoundation.isFocusModeSupported
import platform.AVFoundation.videoZoomFactor
import platform.AVFoundation.virtualDeviceSwitchOverVideoZoomFactors
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreGraphics.CGPointMake
import platform.CoreMedia.CMSampleBufferRef
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.AtomicReference
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The iOS owns-the-camera-session scanner: runs an `AVCaptureSession` internally and streams a
 * [MrzScanResult] per analysed frame, so the consumer never touches `AVCaptureSession` /
 * `AVCaptureVideoDataOutput`
 * ([ADR-020](https://lightine.youtrack.cloud/articles/TES-A-49)).
 * It is the iOS analogue of the Android `CameraXMrzScanner`, built on the same platform-agnostic
 * [scan][MrzFrameAnalyzer.scan] engine: it adds an [AVCaptureVideoDataOutput] to the session
 * (`alwaysDiscardsLateVideoFrames = true` — the iOS counterpart of CameraX's
 * `STRATEGY_KEEP_ONLY_LATEST`; AVFoundation drops late frames rather than queueing stale ones),
 * delivers each `CMSampleBuffer` through the analyse-frame core, and releases it once analysed.
 *
 * **Headless.** No preview layer is created; a consumer that wants one attaches its own
 * `AVCaptureVideoPreviewLayer`. **The consumer holds camera permission** before [start] — this scanner
 * reads the authorization status ([AVCaptureDevice.authorizationStatusForMediaType], which only reads,
 * never prompts) and reports a [CameraError.PermissionDenied] on [results] when it is not granted; it
 * never calls `requestAccessForMediaType` (`scope.md` "permission boundary").
 *
 * **`CMSampleBuffer` lifetime — the genuinely-new iOS concern.** Unlike Android's `ImageProxy` (a JVM
 * object CameraX keeps alive until `close()`), a `CMSampleBuffer` delivered to the capture delegate is a
 * Core Foundation type valid only for the duration of the delegate callback, and Kotlin/Native's ARC
 * does **not** manage CF types. So this scanner takes explicit ownership: the delegate [CFRetain]s each
 * buffer before handing it across the coroutine boundary, and the buffer is [CFRelease]d exactly once —
 * either by [scan]'s `releaseFrame` after it is analysed, or by the frame channel's `onUndeliveredElement`
 * when it is dropped (a newer frame arrived first) or the session tears down. This is the retain/release
 * counterpart of Android's mandatory `ImageProxy::close`.
 *
 * **Back-pressure differs from Android by design.** Android blocks CameraX's analysis executor on a
 * rendezvous send; iOS must **not** block AVFoundation's serial sample-buffer queue (Apple's guidance —
 * blocking it stalls capture). Instead the delegate does a non-blocking send into a capacity-1
 * `DROP_OLDEST` channel: the newest frame always wins, and a frame the analyzer has not yet taken when a
 * newer one arrives is dropped (released) rather than analysed stale — the same "only the latest frame"
 * intent, reached without blocking the capture queue.
 *
 * **Verification status.** The contract this implements (the [MrzCameraScanner] interface and the
 * [scan][MrzFrameAnalyzer.scan] engine) is host-tested in `mrz-camera-core`, and the in-use interruption
 * reason check ([isVideoDeviceInUseReason]) is host-tested in `iosTest`. The AVFoundation wiring is compiled
 * on the `ios-compile` CI job. The live-camera behaviour — frame streaming and the `CMSampleBuffer`
 * retain/release accounting under load — is device-verified separately (the iOS Simulator has no camera),
 * exactly as the Android scanner's CameraX wiring was. Capture failures split the same way as Android's
 * recoverable-vs-critical CameraState contract: a *runtime error* is terminal ([CameraError.CameraUnavailable]);
 * an *interruption* is recoverable — the `videoDeviceInUseByAnotherClient` reason is surfaced as a
 * **non-terminal** [CameraError.CameraInUse] while the session stays bound and AVFoundation resumes capture
 * when the interruption ends (the other reasons recover silently). The live in-use scenario — a second client
 * taking then releasing the camera — is device-verified (see the project's issue tracker).
 *
 * @param recognizer the OCR seam. Defaults to the Apple Vision recognizer; a consumer-supplied
 *   recognizer that is [AutoCloseable] is released on [close], since the scanner owns the OCR resource
 *   for its session.
 * @param mode strict (default) or lenient candidate extraction, forwarded to the analyse-frame core.
 * @param telemetry where per-frame [CameraFrameEvent]s go. Defaults to the application's registered sink
 *   ([TelemetrySinkRegistry.current][TelemetrySinkRegistry]; [NoOpTelemetrySink] when none is registered).
 *   Pass an explicit sink to override per instance.
 * @param cameraPosition which lens to use; defaults to the back camera (documents face the rear lens).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public class AVCaptureMrzScanner(
    recognizer: MrzTextRecognizer<CMSampleBufferRef> = VisionMrzTextRecognizer(),
    mode: ParsingMode = ParsingMode.STRICT,
    telemetry: TelemetrySink = TelemetrySinkRegistry.current,
    private val cameraPosition: AVCaptureDevicePosition = AVCaptureDevicePositionBack,
) : MrzCameraScanner,
    AutoCloseable {
    private val analyzer = MrzFrameAnalyzer(recognizer, mode, telemetry)
    private val ownedRecognizer = recognizer as? AutoCloseable

    // Don't OCR every camera frame. The camera delivers ~30 fps, but Apple Vision (accurate, on-device) is
    // far heavier than that is useful for MRZ — a document is held still for a second or more, so a few
    // analyses per second already catch it. Capping the analysis rate bounds the amplitude of the memory
    // sawtooth (fewer Vision calls between GC passes → lower native-allocation peak; device-verified
    // 2026-05-31: ~96–253 MB at this interval) and avoids burning CPU on redundant frames. It is NOT what
    // prevents the capture stall — that is the strong [captureDelegate] reference below. Frames arriving
    // inside the interval are dropped at the capture delegate, never retained, so the buffer pool is
    // untouched.
    private val analysisInterval: Duration = 200.milliseconds

    // Vision recognition is CPU-bound and runs on the collecting coroutine; Dispatchers.Default keeps it
    // (and AVCaptureSession.startRunning, which can block) off the main thread. The capture delegate runs
    // on its own serial dispatch queue, created per session below.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Hot result stream, alive across start/stop. extraBufferCapacity + DROP_OLDEST means a slow collector
    // never suspends the analysis pipeline — it just misses the oldest pending result (the next frame is
    // milliseconds away), matching the "drop stale frames" intent of alwaysDiscardsLateVideoFrames.
    private val mutableResults =
        MutableSharedFlow<MrzScanResult>(
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val results: Flow<MrzScanResult> = mutableResults.asSharedFlow()

    // Opt-in live-preview seam (additive; the scanner is headless by default — ADR-020), the iOS mirror of
    // the Android scanner's [surfaceRequest]. Read at bind time in cameraFrames(): when armed, the running
    // AVCaptureSession is published on [previewSession] so a SwiftUI viewfinder can attach its own
    // AVCaptureVideoPreviewLayer. The scanner keeps owning the session and its lifecycle; the UI only draws.
    // @Volatile because enablePreview() may be called from any thread; the bind reads it on Dispatchers.Default.
    @Volatile private var previewEnabled = false

    // The bound camera device while a session runs (null otherwise), so focusOnRegion can retarget focus on
    // a LIVE session; and the latest requested focus point, remembered across session restarts. Atomic
    // references: both cross the caller's thread and the Dispatchers.Default session thread.
    private val activeDevice = AtomicReference<AVCaptureDevice?>(null)
    private val focusPoint = AtomicReference<FocusPoint?>(null)
    private val mutablePreviewSession = MutableStateFlow<AVCaptureSession?>(null)

    /**
     * The running [AVCaptureSession] to preview, or `null` when no preview is active. Emits the session once
     * the camera opens **after** [enablePreview] has armed the preview and [start] runs the session; back to
     * `null` when the session ends. A SwiftUI viewfinder (in `TesseraUI`) collects this and attaches an
     * `AVCaptureVideoPreviewLayer` — the UI only draws; this scanner keeps owning the session and lifecycle.
     * Stays `null` for the headless default (preview not armed).
     */
    public val previewSession: StateFlow<AVCaptureSession?> = mutablePreviewSession.asStateFlow()

    /**
     * Arms the live camera preview for subsequent [start]s: the next running session is published on
     * [previewSession] for a viewfinder to render. Opt-in so the default remains headless (ADR-020) — a
     * pure-OCR consumer never exposes its session. Idempotent; call before [start]. There is no disarm:
     * preview stays armed for this scanner's lifetime once set (the default UI arms once at construction).
     */
    public fun enablePreview() {
        previewEnabled = true
    }

    // Tracks the in-flight session and auto-clears it on completion, so start() works again after the
    // stream ends on its own (a terminal CaptureError), not only after stop(). See CameraSessionGate.
    private val session = CameraSessionGate()

    // STRONG reference to the capture delegate, held for the whole session. AVCaptureVideoDataOutput holds
    // its sample-buffer delegate WEAKLY (Cocoa convention). Kotlin/Native objects are reclaimed by the GC,
    // not by ARC, so if nothing on the Kotlin side retains the delegate the next GC pass collects it, the
    // output's weak pointer goes nil, and the camera silently stops calling it — capture stalls with no
    // interruption and no dropped-frame events (device-verified: forcing a GC each frame stalled capture
    // after a single frame). Keeping this reference for the session's lifetime is the fix.
    private var captureDelegate: SampleBufferDelegate? = null

    override fun start() {
        session.start {
            scope.launch {
                analyzer
                    .scan(
                        frames = cameraFrames(),
                        releaseFrame = ::releaseFrame,
                        captureErrorFor = ::cameraErrorFor,
                    ).collect(mutableResults::emit)
            }
        }
    }

    override fun stop() {
        session.stop()
    }

    /** Stops the session and closes the OCR recognizer it owns. */
    override fun close() {
        stop()
        scope.cancel()
        ownedRecognizer?.close()
    }

    // Explicit continuous autofocus, applied right after the device is discovered (session setup) — the
    // iOS counterpart of CameraX's CONTROL_AF_MODE_CONTINUOUS_PICTURE default (see CameraXMrzScanner's
    // comment on the one-shot-lock pitfall TES-86 found there: a one-shot focus converges once and then
    // holds, so a document brought in too close and then pulled back stays soft; continuous AF keeps
    // re-focusing as the scene changes, which is what a handheld MRZ scan needs). Made explicit rather than
    // left to whichever focus mode the device starts in, mirroring the Android decision even though this
    // particular failure mode has not been reproduced on iOS — device verification is TES-129 wave 4.
    // Never throws: a device that does not support continuous AF, or that cannot be locked for
    // configuration right now (e.g. already locked by another client), simply keeps its current focus mode
    // — a focus preference is an optimisation, not a precondition for capture.
    private fun setContinuousAutoFocusIfSupported(device: AVCaptureDevice) {
        if (!device.isFocusModeSupported(AVCaptureFocusModeContinuousAutoFocus)) return
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            if (device.lockForConfiguration(errorPtr.ptr)) {
                device.focusMode = AVCaptureFocusModeContinuousAutoFocus
                device.unlockForConfiguration()
            }
        }
    }

    // Pins a virtual device's zoom to its wide (1×) constituent. A virtual device's videoZoomFactor 1.0 is
    // its WIDEST constituent — the ultra-wide's 0.5× field — which would silently change the framing every
    // consumer expects; the first switch-over factor is, per the AVCaptureDevice.h contract, the boundary
    // to the next-longer constituent (the wide lens). No-op for a physical (non-virtual) device.
    private fun pinVirtualDeviceToWideZoom(device: AVCaptureDevice) {
        val wideFactor = (device.virtualDeviceSwitchOverVideoZoomFactors.firstOrNull() as? NSNumber) ?: return
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            if (device.lockForConfiguration(errorPtr.ptr)) {
                device.videoZoomFactor = wideFactor.doubleValue
                device.unlockForConfiguration()
            }
        }
    }

    /**
     * Aim continuous autofocus at the **MRZ guide region** the viewfinder shows, instead of the frame's
     * centre — the focus counterpart of `VisionMrzTextRecognizer.restrictToMrzBand(x:y:width:height:)`
     * (TES-132: with the guide band away from the frame centre, centre-weighted AF happily focuses the
     * background while the document blurs). All four values are in **AVFoundation's metadata-output space**
     * (normalized against the unrotated capture buffer, top-left origin) — the exact space
     * `AVCaptureVideoPreviewLayer.metadataOutputRectOfInterest(for:)` returns AND the space
     * `focusPointOfInterest` documents, so the viewfinder passes the same converted rect to both. The
     * region's centre becomes the focus point of interest.
     *
     * Safe to call at any time from any thread: applied immediately when the camera is running, remembered
     * and applied at the next session start otherwise. A device without focus-point support keeps
     * centre-weighted continuous AF — a focus preference is an optimisation, never a precondition.
     */
    public fun focusOnRegion(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
    ) {
        val point = FocusPoint(x = x + width / 2.0, y = y + height / 2.0)
        focusPoint.value = point
        activeDevice.value?.let { applyFocusPoint(it, point) }
    }

    // Applies a stored focus point-of-interest. Setting focusPointOfInterest takes effect only when the
    // focus MODE is set afterwards (the AVCaptureDevice.h contract), so continuous AF is re-asserted in the
    // same configuration lock.
    private fun applyFocusPoint(
        device: AVCaptureDevice,
        point: FocusPoint,
    ) {
        if (!device.focusPointOfInterestSupported) return
        if (!device.isFocusModeSupported(AVCaptureFocusModeContinuousAutoFocus)) return
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            if (device.lockForConfiguration(errorPtr.ptr)) {
                device.focusPointOfInterest = CGPointMake(point.x.coerceIn(0.0, 1.0), point.y.coerceIn(0.0, 1.0))
                device.focusMode = AVCaptureFocusModeContinuousAutoFocus
                device.unlockForConfiguration()
            }
        }
    }

    // The AVFoundation capture session bridged to a Flow. Setup runs lazily inside the flow (on the
    // collecting coroutine) so a setup failure — permission not granted, no camera, cannot configure —
    // is thrown here and routed through scan()'s catch -> cameraErrorFor into a CameraError, exactly like
    // the rest of the capture-availability failures (ADR-020: surfaced as a sealed result, never thrown
    // out of the stream). Asynchronous failures (interruption / runtime error) close the channel with a
    // cause, which receiveAsFlow rethrows into the same catch path.
    private fun cameraFrames(): Flow<CMSampleBufferRef> =
        flow {
            // All setup that can fail runs FIRST, before the frame channel or the capture delegate exist,
            // so that once they do exist the try/finally below reliably owns their teardown (no resource is
            // acquired on a path that can throw past it). Each failure is thrown here and routed through
            // scan()'s catch -> cameraErrorFor into a CameraError.

            // Read-only permission check — never requests (permission boundary).
            if (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) != AVAuthorizationStatusAuthorized) {
                throw CameraPermissionException("CAMERA permission not granted (authorization status not authorized)")
            }

            // Prefer a VIRTUAL device (triple, then dual-wide) over the bare wide-angle lens: a virtual
            // device automatically falls back to the constituent with the shorter minimum focus distance
            // when the subject is too close for the active camera (the AVCaptureDevice.h "fallback primary
            // constituent" contract) — and a document filling the MRZ guide box IS typically inside the
            // wide lens's minimum focus distance (maintainer-reported blur, TES-132). Discovery returns
            // devices in the requested order, so the richest available wins; the bare wide angle remains
            // the fallback for devices with a single back camera.
            val device =
                AVCaptureDeviceDiscoverySession
                    .discoverySessionWithDeviceTypes(
                        deviceTypes =
                            listOf(
                                AVCaptureDeviceTypeBuiltInTripleCamera,
                                AVCaptureDeviceTypeBuiltInDualWideCamera,
                                AVCaptureDeviceTypeBuiltInWideAngleCamera,
                            ),
                        mediaType = AVMediaTypeVideo,
                        position = cameraPosition,
                    ).devices
                    .firstOrNull() as? AVCaptureDevice
                    ?: throw CameraUnavailableException("no camera available for the requested position")

            // Explicit continuous autofocus (see setContinuousAutoFocusIfSupported) — set right after the
            // device is found, before it is wired into the session. For a virtual device this also pins the
            // zoom to the wide (1×) field of view: a virtual device's zoom factor 1.0 is its widest
            // constituent (the ultra-wide), which would change the preview framing consumers expect.
            setContinuousAutoFocusIfSupported(device)
            pinVirtualDeviceToWideZoom(device)
            activeDevice.value = device
            // A focus region requested before the camera opened (the viewfinder's layout usually resolves
            // first) applies now.
            focusPoint.value?.let { applyFocusPoint(device, it) }

            val input =
                memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                    AVCaptureDeviceInput.deviceInputWithDevice(device, errorPtr.ptr)
                        ?: throw CameraUnavailableException(
                            errorPtr.value?.localizedDescription ?: "could not open the camera input",
                        )
                }

            val output =
                AVCaptureVideoDataOutput().apply {
                    alwaysDiscardsLateVideoFrames = true
                }
            val session = AVCaptureSession()
            session.beginConfiguration()
            val canAddInput = session.canAddInput(input)
            if (canAddInput) session.addInput(input)
            val canAddOutput = session.canAddOutput(output)
            if (canAddOutput) session.addOutput(output)
            session.commitConfiguration()
            if (!canAddInput || !canAddOutput) {
                throw CameraUnavailableException("could not configure the capture session (input/output rejected)")
            }

            // Setup is now complete and cannot fail. Create the frame channel and attach the delegate, then
            // everything from startRunning onward is in a try/finally that releases them all.

            // capacity-1 + DROP_OLDEST: only the newest frame is kept; onUndeliveredElement releases a
            // CMSampleBuffer dropped by overflow or by channel close/cancel — the retain/release
            // counterpart of releaseFrame() for frames the analyzer DID take. Frames are CFRetain'd before
            // the send, so every retained buffer leaves through exactly one of these two release points.
            val frames =
                Channel<CMSampleBufferRef>(
                    capacity = 1,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                    onUndeliveredElement = ::releaseFrame,
                )
            val captureQueue = dispatch_queue_create("io.lightine.tessera.mrz.camera.avcapture", null)
            // Throttle gate (see [analysisInterval]). The delegate runs serially on captureQueue, so this
            // single mark is read/written from one thread — no synchronization needed.
            var lastAnalysisAt: TimeSource.Monotonic.ValueTimeMark? = null
            // Held in the class field [captureDelegate] (NOT just passed inline) so a strong Kotlin
            // reference keeps it alive for the session — the output retains it only weakly.
            val delegate =
                SampleBufferDelegate { buffer ->
                    val now = TimeSource.Monotonic.markNow()
                    val last = lastAnalysisAt
                    if (last != null && (now - last) < analysisInterval) {
                        // Within the throttle window: drop this frame. It was never CFRetain'd, so
                        // AVFoundation reclaims it as usual — the capture pool is never touched.
                        return@SampleBufferDelegate
                    }
                    lastAnalysisAt = now
                    // Take ownership for the trip across the coroutine boundary; release immediately if the
                    // channel is already closed (teardown raced this callback), else the channel owns it.
                    CFRetain(buffer)
                    if (!frames.trySend(buffer).isSuccess) releaseFrame(buffer)
                }
            captureDelegate = delegate
            output.setSampleBufferDelegate(delegate, captureQueue)

            // AVFoundation surfaces asynchronous failures through notifications, and they split exactly like
            // CameraX's recoverable-vs-critical CameraState codes. A *runtime error* is a genuine
            // media-services / hardware fault with no auto-recovery — it closes the frame channel with a
            // cause, routing through the same catch -> cameraErrorFor path as a setup failure (terminal). An
            // *interruption* is recoverable: AVFoundation posts AVCaptureSessionInterruptionEnded and resumes
            // capture when it ends. The "video device in use by another client" reason is surfaced as a
            // NON-TERMINAL CameraInUse observation — emitted onto results while the session stays bound, so
            // capture resumes when the other client releases the camera (the same auto-recovery the
            // backgrounded reason already relies on; the other reasons recover silently). This mirrors the
            // Android scanner: in-use is recoverable on both platforms (reader-not-oracle: report the in-use
            // observation; do not decide a transient interruption ends the session).
            val center = NSNotificationCenter.defaultCenter
            val interruptionObserver =
                center.addObserverForName(AVCaptureSessionWasInterruptedNotification, session, null) { notification ->
                    if (isVideoDeviceInUse(notification)) {
                        mutableResults.tryEmit(
                            MrzScanResult.CaptureError(
                                error = CameraError.CameraInUse("camera is in use by another client (session interrupted)"),
                                quality = ScanQuality(mrzRegionFound = false, ocrConfidence = null, recognizedLineCount = 0),
                            ),
                        )
                    }
                }
            // Deliberately a no-op. AVFoundation resumes the session and starts delivering frames again on
            // its own once the interruption clears — frames flowing again through the capture delegate IS
            // the recovery signal (mirrors Android's recoverable model, where a CameraState tick with no
            // error just clears the recoverable-code de-dup latch; AVFoundation's interruption-ended
            // notification fires once per interruption rather than on a repeating tick, so there is no
            // latch here to clear and nothing else to do). The observer exists so that is explicit and
            // torn down deliberately, rather than "nothing observes this notification" being ambiguous
            // between "handled elsewhere" and "never considered".
            val interruptionEndedObserver =
                center.addObserverForName(AVCaptureSessionInterruptionEndedNotification, session, null) {}
            val runtimeErrorObserver =
                center.addObserverForName(AVCaptureSessionRuntimeErrorNotification, session, null) {
                    frames.close(CameraUnavailableException("the capture session reported a runtime error"))
                }

            try {
                session.startRunning()
                // Publish the running session for an armed SwiftUI viewfinder to draw (headless default: null).
                if (previewEnabled) mutablePreviewSession.value = session
                emitAll(frames.receiveAsFlow())
            } finally {
                activeDevice.value = null
                mutablePreviewSession.value = null
                center.removeObserver(interruptionObserver)
                center.removeObserver(interruptionEndedObserver)
                center.removeObserver(runtimeErrorObserver)
                output.setSampleBufferDelegate(null, null)
                captureDelegate = null
                session.stopRunning()
                // Releases a frame still buffered at teardown (via onUndeliveredElement); a no-op if empty.
                frames.close()
            }
        }

    // CMSampleBuffer is a Core Foundation type; Kotlin/Native's ARC does not release it, so every buffer
    // this scanner retains is released here exactly once — as scan()'s releaseFrame after analysis, and as
    // the frame channel's onUndeliveredElement for dropped/undelivered buffers.
    private fun releaseFrame(frame: CMSampleBufferRef) {
        CFRelease(frame)
    }

    // Maps a TERMINAL capture-availability failure to a typed CameraError. The custom exceptions are the
    // ones cameraFrames() throws at setup or closes the channel with (permission / no-camera / runtime
    // error); a recoverable in-use interruption is surfaced non-terminally in cameraFrames and never
    // reaches here. Coroutine cancellation is never seen here — Flow.catch (in scan) propagates it rather
    // than passing it to this mapper. `internal` (not private) only so the unit test can exercise the
    // mapping directly, since the live camera failures that feed it cannot be triggered on the Simulator.
    internal fun cameraErrorFor(cause: Throwable): CameraError? =
        when (cause) {
            is CameraPermissionException -> CameraError.PermissionDenied(cause.message ?: "CAMERA permission not granted")
            is CameraUnavailableException -> CameraError.CameraUnavailable(cause.message ?: "camera unavailable")
            else -> CameraError.CameraUnavailable(cause.message ?: cause.toString())
        }

    private fun isVideoDeviceInUse(notification: NSNotification?): Boolean {
        // AVCaptureSessionInterruptionReason is a typealias to NSInteger here (not a Kotlin enum), so the
        // reason constant and the userInfo NSNumber's integerValue are both Long — compared directly.
        val reason = (notification?.userInfo?.get(AVCaptureSessionInterruptionReasonKey) as? NSNumber)?.integerValue
        return isVideoDeviceInUseReason(reason)
    }

    // The recoverable in-use interruption reason, factored out of the NSNotification read so it is a pure
    // function the iosTest exercises against AVFoundation's own reason constant (the Simulator cannot post a
    // real interruption). Only `videoDeviceInUseByAnotherClient` is the "another client took the camera"
    // reason the scanner surfaces as a non-terminal CameraInUse; every other reason (backgrounded, system
    // pressure) is left to recover silently. The Android counterpart host-tested this way is classifyCameraState.
    // `internal` (not private) only so the unit test can reach it.
    internal fun isVideoDeviceInUseReason(reason: Long?): Boolean =
        reason == AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient
}

// AVCaptureVideoDataOutput's delegate, bridging each delivered sample buffer to the frame channel. The
// protocol declares didOutputSampleBuffer and didDropSampleBuffer with selectors that collapse to the
// same Kotlin signature, so the override is annotated @ObjCSignatureOverride to bind the right one.
@OptIn(ExperimentalForeignApi::class)
private class SampleBufferDelegate(
    private val onFrame: (CMSampleBufferRef) -> Unit,
) : NSObject(),
    AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
    @ObjCSignatureOverride
    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputSampleBuffer: CMSampleBufferRef?,
        fromConnection: AVCaptureConnection,
    ) {
        onFrame(didOutputSampleBuffer ?: return)
    }
}

// Carry the kind of capture-availability failure cameraFrames() detected, so cameraErrorFor can map each
// to the right CameraError through scan()'s catch path. None carries recognized text (there is none).
// The centre of a requested focus region, in AVFoundation's point-of-interest space (normalized, unrotated
// picture, top-left origin). Immutable so an AtomicReference hands both coordinates over atomically.
internal data class FocusPoint(
    val x: Double,
    val y: Double,
)

// `internal` (not private) only so the unit test can construct them to exercise cameraErrorFor.
internal class CameraPermissionException(
    message: String,
) : Exception(message)

internal class CameraUnavailableException(
    message: String,
) : Exception(message)
