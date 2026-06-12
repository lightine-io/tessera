# iOS Integration — Live-Camera MRZ Scanning

How to go from an empty iOS app to a working MRZ scan with `AVCaptureMrzScanner`, in Swift. The capability reference is [MRZ Camera Reading](../features/mrz-camera-reading.md); the Android counterpart of this guide is [Android Integration](android-integration.md).

**Verified: 2026-06-12.** Claims are checked against the shipped `0.2.1` source, and every Swift pattern below is transcribed from the harness app that was device-verified on a physical iPhone (2026-05-31 → 2026-06-01: 770 analysed frames without a stall, bounded memory, and a live decode of a real document).

**The Swift surface is provisional through `0.x`** — the scanner exposes a Kotlin `Flow`, which Swift consumes through Kotlin-interop types rather than a native `AsyncSequence` (a Swift-idiomatic adapter is a recorded deferral). The patterns below are the verified way to consume it today; expect them to get nicer before `1.0.0`.

---

## Prerequisites

- An iOS app with **deployment target 18+** (the SDK's committed floor — [Platforms](../../README.md#platforms)), built in Xcode.
- A **physical iPhone** for end-to-end testing — the iOS Simulator has no camera (Apple Vision still runs there on still images, but live scanning cannot be exercised).
- Nothing else: OCR is Apple Vision (a system framework) — on-device, no model download, no third-party OCR dependency.

## 1. Add the package

In Xcode: **File → Add Package Dependencies…** → `https://github.com/lightine-io/tessera-swift` → version `0.2.1`. Or in `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/lightine-io/tessera-swift", from: "0.2.1"),
]
```

Then `import Tessera` — one product, the `Tessera` XCFramework, carrying the scanner and all core types (`MrzScanResult`, `ParseResult`, the document types).

## 2. Camera permission

Two pieces, both yours (the SDK **never prompts** — it reads authorization and reports a typed error if access is missing):

**Info.plist** — required; iOS terminates an app that touches the camera without it:

```xml
<key>NSCameraUsageDescription</key>
<string>Scans the machine-readable zone of identity documents.</string>
```

**Request at runtime** — the verified pattern:

```swift
switch AVCaptureDevice.authorizationStatus(for: .video) {
case .notDetermined:
    AVCaptureDevice.requestAccess(for: .video) { granted in
        DispatchQueue.main.async { startScanner() }   // granted or not — see below
    }
default:
    startScanner()
}
```

Starting without access does not crash: a `CameraError.PermissionDenied` (`camera.permission_denied`) arrives on the results stream and the stream ends.

## 3. Create the scanner

Kotlin default arguments do not project into Swift, so pass all four parameters explicitly (this is part of the provisional-surface roughness):

```swift
let scanner = AVCaptureMrzScanner(
    recognizer: VisionMrzTextRecognizer(),       // Apple Vision OCR (the default engine)
    mode: ParsingMode.strict,                    // or .lenient — values are never changed either way
    telemetry: TelemetrySinkRegistry.shared.current,
    cameraPosition: 1                            // AVCaptureDevicePositionBack — documents face the rear lens
)
```

The scanner is **headless** (no preview layer; your UI owns any viewfinder) and owns its capture session: backgrounding pauses capture and foregrounding auto-resumes it — you do nothing. Analysis is internally throttled to ~5 frames/sec (Vision is heavy; a held document needs no more), which also bounds memory; the camera itself runs at full rate, so any preview you draw stays smooth.

## 4. Collect results

`results` is a Kotlin `Flow`. From Swift you collect it with a small adapter implementing the Kotlin `FlowCollector` protocol — this is the verified pattern from the device-tested harness:

```swift
final class ResultCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onResult: (MrzScanResult) -> Void
    init(_ onResult: @escaping (MrzScanResult) -> Void) { self.onResult = onResult }
    func emit(value: Any?) async throws {
        if let r = value as? MrzScanResult { onResult(r) }
    }
}

let collectTask = Task {
    try await scanner.results.collect(collector: ResultCollector { result in
        Task { @MainActor in handle(result) }
    })
    // reaching here means the stream COMPLETED — a terminal capture error ended the session
}
scanner.start()
```

The sealed variants flatten to Obj-C-style names in Swift; branch with casts:

```swift
func handle(_ r: MrzScanResult) {
    switch r {
    case let d as MrzScanResultDecoded:
        // d.parse is the mrz-core verdict: ParseResultSuccess / ParseResultPartialSuccess / ParseResultFailure
        if let success = d.parse as? ParseResultSuccess { onMrz(success.document) }
    case is MrzScanResultNoMrzFound:
        break   // normal between locks — wait for the next frame
    case let e as MrzScanResultCaptureError:
        handleCameraError(e.error)   // see the error-semantics table below
    default:
        break
    }
}
```

Every result carries `quality` (`mrzRegionFound`, `ocrConfidence`, `recognizedLineCount`) — informational, never a gate. **Do not log results verbatim** — `Decoded`/`NoMrzFound` carry document PII; log the variant and `error.code`/`quality` only.

## 5. Stop and close

```swift
collectTask.cancel()
scanner.stop()    // end the scan session; start() works again afterwards
scanner.close()   // REQUIRED when done with the instance — releases the session and the owned Vision recognizer
```

## Error semantics — terminal vs. recoverable

Same contract as Android — the signal is whether the stream ends:

| You observe | Meaning | What to do |
|---|---|---|
| `CaptureError(camera.in_use)`, stream stays alive | Another client (e.g. Continuity Camera) holds the lens — recoverable; AVFoundation resumes capture when the interruption ends | Optionally show "camera busy"; nothing else |
| Backgrounding | An interruption that recovers silently on foreground — no error surfaced | Nothing |
| `CaptureError(camera.permission_denied)` / `camera.unavailable`, stream **completes** | Critical (missing permission / an AVFoundation runtime error) | Fix the cause, then `start()` again |

Device-verified: a real `videoDeviceInUseByAnotherClient` interruption surfaced one non-terminal `camera.in_use` and streaming resumed on its own.

## Known limitation — decode reliability

MRZ-candidate detection expects exact ICAO line shapes and is brittle against messy OCR: **a screen-rendered MRZ generally will not decode** (test against a printed document). On iOS the real-document path is proven — the device run decoded a live passport-style document (dozens of clean parses) — but detection tuning (length-tolerant matching, ROI crop) is recorded, in-progress work: see "Camera MRZ-candidate detection vs real OCR output" in [open-questions](../open-questions.md).

## Troubleshooting

- **App is killed the moment scanning starts** — missing `NSCameraUsageDescription` in Info.plist.
- **"results flow completed" immediately** — a terminal error ended the session; almost always permission. Check `AVCaptureDevice.authorizationStatus(for: .video)`.
- **Nothing on the Simulator** — it has no camera; use a device for live scanning.
- **Only `NoMrzFound`** — see the limitation above; check `quality.recognizedLineCount` first (0 means the image, not the matcher: focus, light, distance).

## Next

- [MRZ Camera Reading](../features/mrz-camera-reading.md) — the capability reference, including the analyse-frame core for custom capture stacks.
- [Getting Started](../getting-started.md) — what to do with the `ParseResult`.
- [reading-risks.md](../reading-risks.md) — what a live-camera read establishes and what it cannot.
