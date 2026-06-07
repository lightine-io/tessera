package io.lightine.tessera.mrz.camera

import kotlinx.coroutines.flow.Flow

/**
 * The owns-the-camera-session convenience: the SDK runs the platform camera internally and streams a
 * [MrzScanResult] per analysed frame, so the consumer never touches `bindToLifecycle` / `ImageAnalysis`
 * (Android) or `AVCaptureSession` (iOS). It is the second of the two camera-reading layers
 * ([ADR-020](https://github.com/lightine-io/tessera/blob/main/docs/decisions/0020-camera-reading-architecture.md)),
 * built on top of the analyse-frame core ([MrzFrameAnalyzer]) — the same OCR → extract → parse pipeline,
 * now driven by a live frame stream instead of a single hand-supplied frame.
 *
 * **Still headless.** The scanner draws nothing: a consumer that wants a live preview attaches its own
 * preview surface. **Permission requests and camera availability remain the consumer's responsibility**
 * (`scope.md` "permission boundary"); when a capture cannot proceed the scanner *reports* a typed
 * [CameraError] on [results] as a [`MrzScanResult.CaptureError`][MrzScanResult.CaptureError] — it never
 * throws from the stream, crashes, or hangs (ADR-020).
 *
 * **Contract shape — Kotlin frozen, Swift projection provisional.** This interface — and the
 * [MrzFrameAnalyzer.scan] engine it is built on — is the UI-agnostic, frame-source-agnostic contract
 * that iOS mirrors. Two freeze states apply at the `0.2.0` tag under
 * [ADR-007](https://github.com/lightine-io/tessera/blob/main/docs/decisions/0007-strict-backward-compat-from-0x.md):
 *
 * - **Kotlin/Android: freeze-ready and locked at the tag** (validated against the AVFoundation
 *   implementation). The member shapes here — [results] as a [Flow], [start], [stop] — are stable for
 *   the whole `0.x` line.
 * - **Objective-C/Swift projection: provisional through `0.x`.** How these members surface to a Swift
 *   caller — notably [results] as a Kotlin [Flow] handle rather than a native `AsyncSequence`, and
 *   `suspend` functions as completion handlers — is explicitly **not** locked at `0.2.0`. Tessera may
 *   add an idiomatic-Swift surface later in `0.x` (a hand-written `AsyncStream`/callback adapter, or a
 *   tool such as SKIE) even though that changes the Swift projection of existing members — a change
 *   ADR-007 would otherwise forbid. Marking the Swift projection provisional now is what keeps that
 *   later adapter a legal, non-breaking change; see "Swift `Flow` / coroutines ergonomics" in
 *   `docs/open-questions.md`.
 */
public interface MrzCameraScanner {
    /**
     * The stream of results, one per analysed frame, plus any capture failure surfaced as an
     * [`MrzScanResult.CaptureError`][MrzScanResult.CaptureError]. A hot stream: it emits only while the
     * scanner is running (between [start] and [stop]); collectors that join late see results from that
     * point on, not a replay. In a live stream the consumer reads each result and waits for the next — a
     * noisy frame is simply [`NoMrzFound`][MrzScanResult.NoMrzFound] and the next clean frame arrives
     * within milliseconds (strict + next-frame retry).
     *
     * Most [`CaptureError`][MrzScanResult.CaptureError]s are **non-terminal** — a per-frame OCR failure,
     * or a recoverable camera-in-use / temporarily-unavailable observation — and the stream continues. A
     * **critical** capture failure (permission denied, fatal hardware) ends the stream. Branch on
     * [`CaptureError.error`][MrzScanResult.CaptureError] to tell them apart; after the stream ends you
     * may [start] again (e.g. once the user grants permission).
     */
    public val results: Flow<MrzScanResult>

    /**
     * Starts the camera session and begins emitting on [results]. Idempotent while running: calling
     * [start] on an already-running scanner does nothing. Once the session has ended — via [stop], or
     * because the stream ended on a terminal capture failure — [start] begins a fresh session (the
     * prompt-and-retry path). The consumer must hold the camera permission first (the scanner reports,
     * it does not request).
     *
     * **Threading.** The lifecycle methods ([start], [stop], and a platform `close`) are not
     * thread-safe; call them from a single thread — typically the UI thread / Swift main actor, the
     * idiomatic place for camera lifecycle. Concurrent calls from multiple threads are not supported and
     * may race (e.g. two `start`s both passing the idempotence check). Collecting [results], by contrast,
     * is safe from any coroutine.
     */
    public fun start()

    /**
     * Stops the camera session and ends emission on [results]. Idempotent. After [stop] the scanner may
     * be [start]ed again. Releasing the scanner entirely (closing its camera resources) is the platform
     * type's own lifecycle concern, documented there.
     */
    public fun stop()
}
