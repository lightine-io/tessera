# MRZ Camera Reading

Headless live-camera reading of the Machine Readable Zone: the SDK receives camera frames, locates and OCRs the MRZ region, and returns the same structured, validated result that string parsing produces — with no UI of its own. The consumer owns all presentation (camera preview, overlay, prompts); a ready-made scanner UI is a later release (0.5.0).

**Status:** Living
**Available since:** 0.2.0
**Platform availability:** Android (CameraX + ML Kit Text Recognition) and iOS (AVFoundation + Apple Vision). The analyse-frame core is frame-source-agnostic, so future sources (USB document readers, web, desktop) can feed the same API.

> The Kotlin-flavored API shapes below are **illustrative, not authoritative** — exact names, signatures, and visibility are finalized as the 0.2.0 slices land and are locked at the 0.2.0 tag (per [ADR-007](../decisions/0007-strict-backward-compat-from-0x.md)). The architectural decisions behind this feature are recorded in [ADR-020](../decisions/0020-camera-reading-architecture.md).

---

## What it does

Camera reading is an **I/O layer** on top of the pure `mrz-core` parser. Each frame travels: *camera frame → locate MRZ region → OCR (platform engine) → candidate string → `mrz-core` parse/validate → result*. The OCR step is platform-specific (ML Kit on Android, Apple Vision on iOS); everything from the candidate string onward is the existing, shared parsing/validation logic ([mrz-parsing.md](mrz-parsing.md), [mrz-validation.md](mrz-validation.md)). Camera reading adds **no new trust decisions** — it is a new way to *obtain* the string, not a new way to *judge* it (Principle 1).

Results carry [read-method metadata](mrz-data-model.md) identifying the source as live camera.

## Two integration layers

The public API ships in two layers; the convenience is built on the core (see [ADR-020](../decisions/0020-camera-reading-architecture.md)).

### 1. Analyse a frame — the core

The low-level entry point: hand it a platform frame, get a result. It owns no camera, so it is unit-testable with injected frames + mock OCR, and it is the seam any frame source feeds. The OCR engine is itself injected through a generic seam, `MrzTextRecognizer<F>`, whose frame type `F` is the extension point — Android binds `F = ImageProxy`; a future USB/desktop/web source binds its own.

```kotlin
// Illustrative — not authoritative. Shapes as of the analyse-frame slice; locked at the 0.2.0 tag.
fun interface MrzTextRecognizer<in F> {
    suspend fun recognize(frame: F): RecognizedText   // OCR only; throwing surfaces CameraError.OcrFailed
}
// RecognizedText(lines = List<RecognizedLine(text, confidence)>) — the raw OCR, exposed verbatim.

class MrzFrameAnalyzer<F>(
    recognizer: MrzTextRecognizer<F>,
    mode: ParsingMode = ParsingMode.STRICT,            // STRICT | LENIENT
    telemetry: TelemetrySink = TelemetrySinkRegistry.current, // one non-PII CameraFrameEvent/frame → the registered sink
) {
    suspend fun analyse(frame: F): MrzScanResult
}

// Android binds F = ImageProxy via the bundled ML Kit recognizer:
val analyzer = MrzFrameAnalyzer(MlKitMrzTextRecognizer())
```

`MrzScanResult` is a sealed result — `Decoded` (carrying the parser's `ParseResult` verdict and the raw `RecognizedText`), `NoMrzFound`, or `CaptureError` — and every variant exposes `ScanQuality` metadata (MRZ-region found?, OCR confidence, recognized-line count).

### 2. Own the camera session — the convenience

The common path: the SDK runs the platform camera internally and streams results. The consumer never touches `bindToLifecycle` / `ImageAnalysis` / `AVCaptureSession`.

```kotlin
// Illustrative — not authoritative. Shapes as of the headless-contract slice; locked at the 0.2.0 tag.
interface MrzCameraScanner {
    val results: Flow<MrzScanResult>   // hot stream: emits between start() and stop(); Compose-friendly, bridges to Swift
    fun start()                        // idempotent; the consumer holds the CAMERA permission first
    fun stop()                         // idempotent; the scanner may be started again
}

// Android binds F = ImageProxy and runs CameraX internally:
val scanner = CameraXMrzScanner(context.applicationContext, lifecycleOwner)

// iOS binds F = CMSampleBufferRef and runs an AVCaptureSession internally:
val scanner = AVCaptureMrzScanner()
```

Both platform scanners are built on one frame-source-agnostic streaming engine — `MrzFrameAnalyzer<F>.scan(frames: Flow<F>): Flow<MrzScanResult>` — which runs each frame of a live stream through the analyse-frame core and releases it afterward. Android feeds it a CameraX `ImageProxy` stream; iOS an AVFoundation `CMSampleBuffer` stream; a USB/desktop/web source feeds its own frame `Flow`. The engine is the host-tested part of the contract (no device needed); the platform camera *session* wiring sits in the platform scanner (`CameraXMrzScanner` on Android, `AVCaptureMrzScanner` on iOS), compiled on CI — the Android scanner is device-verified, and the iOS scanner's live behaviour is device-verified separately because the iOS Simulator has no camera. Capture-availability failures (the camera could not start) are surfaced on the same `results` stream as a `MrzScanResult.CaptureError`, never thrown.

Still headless: if the consumer wants a live preview, they attach their own preview surface (a "preview hook"); the SDK draws nothing.

## Usage

The illustrative shapes above sketch the surface; the end-to-end example below compiles against the current Android owns-session API. The **Kotlin/Android camera contract locks at the 0.2.0 tag** ([ADR-007](../decisions/0007-strict-backward-compat-from-0x.md)); the **iOS/Swift projection stays provisional through `0.x`** (the iOS scanner mirrors this same contract, but you consume `AVCaptureMrzScanner` from Swift, where the `Flow`→Swift bridging is not yet locked — see the iOS notes under "Errors" and [ADR-019](../decisions/0019-ios-distribution-via-spm.md)), so no Swift example is pinned here yet.

```kotlin
import io.lightine.tessera.mrz.camera.CameraXMrzScanner
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.parsing.ParseResult
// context, lifecycleOwner, and lifecycleScope are the consumer's standard Android objects.

// The consumer requests/holds the CAMERA permission before start(). CameraXMrzScanner is
// AutoCloseable — close() releases the analysis executor and the OCR recognizer.
val scanner = CameraXMrzScanner(context.applicationContext, lifecycleOwner)
scanner.start() // idempotent; begins streaming on `results`

lifecycleScope.launch {
    scanner.results.collect { result ->
        when (result) {
            is MrzScanResult.Decoded -> {
                // result.parse is the SAME ParseResult as string parsing — a camera-sourced MRZ
                // validates identically to a typed-in one; result.recognizedText is the raw OCR.
                val parse = result.parse
                if (parse is ParseResult.Success) {
                    println(parse.document.commonFields.documentNumber)
                }
            }
            is MrzScanResult.NoMrzFound -> {
                // Normal for a frame not yet showing the MRZ — just wait for the next frame.
            }
            is MrzScanResult.CaptureError -> {
                // Typed, never thrown: result.error is PermissionDenied / CameraUnavailable /
                // CameraInUse / OcrFailed, each with a stable result.error.code to localize.
            }
        }
    }
}

// when finished:
scanner.stop()  // idempotent; the scanner can be start()ed again
scanner.close() // release the executor + recognizer
```

For a custom frame source (a USB reader, desktop, or web) rather than the platform camera, feed the lower-level core directly: `MrzFrameAnalyzer(recognizer).analyse(frame)` per frame, or `analyzer.scan(frames)` over a `Flow` of frames.

## Parsing modes

Consumer-chosen; **strict is the default**, and the **raw OCR reading is always exposed** regardless of mode.

- **Strict** — accept only a conformant MRZ; otherwise report a typed error. For live camera this pairs with **next-frame retry**: a noisy frame fails and is dropped, and a clean frame arrives within milliseconds, so no correction is needed.
- **Lenient** — tolerate benign formatting noise (e.g. stray whitespace) without changing any data value.
- **Tolerant** (check-digit-guided OCR disambiguation) is **not** in 0.2.0 — it is deferred to 0.3.0 (still-image reading), and when added it will *surface* candidate corrections, never silently overwrite. See [open-questions.md](../open-questions.md) ("Lenient and tolerant parsing modes").

## Quality signals — exposed, never gated

The result surfaces the natural signals the pipeline produces — whether an MRZ region was found, OCR confidence, and the parse/validation outcome — as **metadata**. The SDK never refuses to return data on quality grounds; the consumer sets any threshold and decides whether to prompt for a re-capture (Principle 1, Principle 5). A richer quality scorer (blur/glare) is deferred to 0.3.0.

## Errors

Capture-layer failures are a **separate `Camera…` typed family**, distinct from the `mrz-core` parse/validation taxonomy ([mrz-error-taxonomy.md](mrz-error-taxonomy.md)). They are surfaced as a **sealed result** (not thrown, never crashing or hanging), with stable English codes the consumer localizes — e.g. camera unavailable, permission denied, camera in use. **Permission requests and camera availability are the consumer's responsibility** (scope.md "permission boundary"); the SDK only reports clearly-typed errors when a capture cannot proceed. The exact `Camera…` set grows through implementation (per the "new error → taxonomy + test" rule).

On Android, a failed camera *open* surfaces **asynchronously through CameraX's camera state**, not as a bind-time exception — the owns-session scanner observes that state and emits the matching `CaptureError` on `results` rather than going silent. One reader-not-oracle nuance verified on a device: CameraX collapses a *permission* denial into a generic fatal state error with no cause, so the scanner reads the (observable) `CAMERA` permission state — read-only, never requesting it — to report `PermissionDenied` rather than a vague unavailability when permission is the actionable cause.

On iOS, `AVCaptureMrzScanner` reads the authorization status (`AVCaptureDevice.authorizationStatus(for:)`, which only reads, never prompts) and reports `PermissionDenied` when it is not authorized; no camera for the requested position is `CameraUnavailable`. Asynchronous failures arrive as `AVCaptureSession` notifications: an interruption whose reason is *video device in use by another client* surfaces a **non-terminal** `CameraInUse` (the session stays bound and AVFoundation resumes capture when the interruption ends), while a session runtime error is a terminal `CameraUnavailable` — both surfaced on `results` like the Android camera-state path. Other interruption reasons (the app backgrounded, system pressure) recover silently.

## Status of Implementation

| Capability | Status |
|---|---|
| Analyse-frame core (platform-agnostic) | Implemented (0.2.0, `mrz-camera-core`) — host-tested with mock OCR on JVM + iOS simulator |
| Android ML Kit recognizer (bundled model) | Implemented (0.2.0) — compiled on CI; device/emulator OCR verified in a later slice |
| Strict + lenient modes | Implemented (0.2.0) |
| Quality signals as metadata | Implemented (0.2.0) |
| `Camera…` error family | Implemented (0.2.0) — `OcrFailed` (analyse-frame) + `CameraUnavailable` / `PermissionDenied` / `CameraInUse` (owns-session); async camera-state surfacing device-verified; the iOS scanner's failure→`CameraError` mapping is unit-tested on the Simulator (`AVCaptureMrzScannerErrorMappingTest`); `CameraInUse` is device-verified on both platforms (2026-06-01, non-terminal/recovering); the iOS `CameraUnavailable` runtime-fault trigger remains device-pending (a media-services fault not deliberately reproducible) |
| Streaming engine (`scan`) + `MrzCameraScanner` contract | Implemented (0.2.0) — host-tested; the frame-source-agnostic contract iOS mirrors |
| Owns-camera-session convenience (Android, `CameraXMrzScanner`) | Implemented (0.2.0) — **device-verified** (live-device slice): back-camera open, frame streaming, `ImageProxy` lifecycle, and async camera-state error surfacing |
| iOS Apple Vision recognizer (`VisionMrzTextRecognizer`) | Implemented (0.2.0, `mrz-camera-ios`) — compiled on CI; the Vision pipeline is smoke-tested on the iOS simulator (`VNRecognizeTextRequest`, language-correction off); **device-verified reading a real MRZ live** (2026-05-31, iPhone 15 Pro): runs Vision on a reused, stride-correct heap copy of each frame (keeps Vision off the finite capture pool), yielding live `ParseResult.Success` decodes |
| Owns-camera-session convenience (iOS, `AVCaptureMrzScanner`) | Implemented (0.2.0, `mrz-camera-ios`) — compiled on both iOS targets on CI; runs `AVCaptureSession` + `AVCaptureVideoDataOutput` internally (`alwaysDiscardsLateVideoFrames` = the KEEP_ONLY_LATEST analogue). **Device-verified (2026-05-31, iPhone 15 Pro):** sustained live streaming with no stall (770 frames, bounded ~96–253 MB memory), live `Decoded` from a real document, the `CMSampleBuffer` retain/release accounting, interrupt/resume, and `CameraInUse`. Holds a strong reference to the capture delegate (Cocoa holds it weakly; Kotlin/Native's GC would otherwise reclaim it and silently stop capture) and analyses at ~5 frames/sec. The `CameraUnavailable` runtime-error path remains device-pending |
| Tolerant mode; richer quality scorer | Deferred (0.3.0) |
| Scanner UI | Deferred (0.5.0) |

## Behavioral commitments

- **Headless** — no UI; the consumer owns all presentation.
- **Reader, not oracle** — quality and OCR ambiguity are *surfaced*, never gated or silently corrected; the raw reading is the source of truth.
- **No new persistence or network** — frames are processed in memory and released promptly (memory hygiene); no frame or result is stored or transmitted by the SDK.
- **Same result type as string parsing** — a camera-sourced MRZ validates identically to a typed-in one; only the read-method metadata differs.

## Relationship to other features

- **[mrz-parsing.md](mrz-parsing.md) / [mrz-validation.md](mrz-validation.md)** — reused unchanged once a candidate string is produced.
- **[mrz-error-taxonomy.md](mrz-error-taxonomy.md)** — gains the `Camera…` capture-error family.
- **[reading-risks.md](../reading-risks.md)** — the live-camera risk profile.
- **[telemetry.md](telemetry.md)** — camera is the first real emitter of telemetry events (frame/OCR/parse).

## Related principles

- **Principle 1 (Reader, not oracle)** — surface, don't decide.
- **Principle 5 (Transparency)** — raw reading + quality signals exposed.
- **Principle 8 (Native fit)** — native camera/OCR per platform.

## Related documents

- [ADR-020](../decisions/0020-camera-reading-architecture.md) — camera reading architecture.
- [ADR-017](../decisions/0017-mobile-targets-and-build-stack.md) — the mobile targets this runs on.
- [ADR-019](../decisions/0019-ios-distribution-via-spm.md) — iOS distribution: the `Tessera` XCFramework via Swift Package Manager.
- [scope.md](../scope.md) — reading methods.
- [development-setup.md](../development-setup.md) — building/running the camera modules.
