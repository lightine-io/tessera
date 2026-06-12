# Android Integration — Live-Camera MRZ Scanning

How to go from an empty Android app to a working MRZ scan with `CameraXMrzScanner`. This is the task-oriented walkthrough; the capability reference is [MRZ Camera Reading](../features/mrz-camera-reading.md), and the platform-free core operations (parse/validate/generate) are in [Getting Started](../getting-started.md).

**Verified: 2026-06-12.** Snippets are symbol-checked against the shipped `0.2.1` source (the usage-example rule), and the dependency + wiring claims are verified against a real consumer app: a harness declaring *only* the Tessera artifact compiled and scanned on a physical device (2026-06-01).

---

## Prerequisites

- An Android app with `minSdk` 23+ (the SDK's committed floor — [Platforms](../../README.md#platforms)).
- A physical device for end-to-end testing. Emulators have no real lens; the scanner runs, but pointing a camera at a document does not happen there.
- Nothing else: **the SDK brings CameraX and ML Kit itself** — do not add them to your app. The ML Kit text recognizer is the *bundled-model* variant, so OCR runs on-device with no model download and no network. (ML Kit does pull Google Play Services / data-transport libraries transitively; the SDK initializes none of them — details in [reading-risks.md](../reading-risks.md), "Third-party OCR dependency surface".)

## 1. Add the dependency

```kotlin
dependencies {
    implementation(platform("io.lightine.tessera:tessera-bom:0.2.1"))
    implementation("io.lightine.tessera:tessera-mrz-camera-android")
}
```

This is the only Tessera line an Android camera consumer needs — it transitively exposes the shared contract (`MrzScanResult`, `CameraError`, `ParseResult`, the parsed document types). One nuance: if you want to pass a **custom** `cameraSelector` or `recognizer` to the constructor, add `androidx.camera:camera-core` to your own compile classpath (those parameter types are CameraX types); with the defaults you need nothing.

## 2. Declare and request the CAMERA permission

The SDK **never requests permission itself** — it only checks, and reports a typed error if the permission is missing (`scope.md`, "permission boundary"). Your app owns the permission flow.

`AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

Request it before starting the scanner — the standard AndroidX pattern:

```kotlin
private val requestCamera =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startScanning()
    }

// at the point you want to scan:
requestCamera.launch(Manifest.permission.CAMERA)
```

If you skip this and `start()` anyway, nothing throws — a `CameraError.PermissionDenied` (`camera.permission_denied`) arrives **asynchronously on the results stream** and the stream ends (CameraX reports a failed open through camera state, not as an exception at bind time).

## 3. Create the scanner

```kotlin
import io.lightine.tessera.mrz.camera.CameraXMrzScanner

val scanner = CameraXMrzScanner(
    appContext = applicationContext,   // application Context — not the Activity (leak avoidance)
    lifecycleOwner = this,             // the camera binds to this lifecycle
)
```

That is the complete required surface — exactly how the verified harness app instantiates it. The remaining constructor parameters are optional, with these defaults:

| Parameter | Default | When to touch it |
|---|---|---|
| `recognizer` | bundled ML Kit recognizer (owned + released by the scanner's `close()`) | Supplying your own OCR engine |
| `mode` | `ParsingMode.STRICT` | `LENIENT` forgives benign format noise; values are never changed |
| `telemetry` | the app-registered sink (`TelemetrySinkRegistry.current`); no-op if none | Per-instance diagnostics sink |
| `cameraSelector` | back camera (documents face the rear lens) | Front lens / specific camera |

The scanner is **headless** — it creates no preview surface. If your UI shows a viewfinder, that preview is yours to own (ADR-020; the SDK makes no presentation assumptions).

## 4. Start and collect results

`results` is a hot `Flow<MrzScanResult>` — one result per analysed frame, emitted on the **main dispatcher** (analysis itself runs on an internal background executor; a slow collector never stalls the camera, it just misses stale frames):

```kotlin
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.parsing.ParseResult

lifecycleScope.launch {
    scanner.results.collect { result ->
        when (result) {
            is MrzScanResult.Decoded -> when (val parse = result.parse) {
                is ParseResult.Success -> onMrz(parse.document)   // your handler — e.g. stop scanning
                is ParseResult.PartialSuccess -> { /* data + validation failures; you decide */ }
                is ParseResult.Failure -> { /* MRZ-shaped but unparseable; next frame may be cleaner */ }
            }
            is MrzScanResult.NoMrzFound -> { /* normal between locks — wait for the next frame */ }
            is MrzScanResult.CaptureError -> handleCameraError(result.error)
        }
    }
}
scanner.start()
```

Every result also carries `result.quality` (`ScanQuality`: `mrzRegionFound`, `ocrConfidence`, `recognizedLineCount`) — observational metadata you may use to prompt the user; the SDK never gates on it.

**Do not log results verbatim** — `Decoded` and `NoMrzFound` carry document PII (the parsed fields and the raw recognized text); their `toString()` exposes it. Log the variant and non-PII fields (`quality`, `error.code`), or use the telemetry module's redaction helpers.

## 5. Stop and close

```kotlin
override fun onDestroy() {
    super.onDestroy()
    scanner.close()   // REQUIRED: stops the session, releases the analysis executor + owned ML Kit recognizer
}
```

- `stop()` ends the scan session (the camera also follows your `lifecycleOwner` — backgrounding stops capture). `start()` works again afterwards, including after a terminal error.
- `close()` is **not optional**: it releases the single-thread analysis executor and closes the ML Kit recognizer the scanner owns. Call it when done with the scanner instance — `onDestroy`, or `ViewModel.onCleared()` if the scanner lives there.

## Error semantics — terminal vs. recoverable

`CaptureError` does not always mean the scan is over. The signal is **whether the stream ends**:

| You observe | Meaning | What to do |
|---|---|---|
| `CaptureError(camera.in_use)` and the stream stays alive | Another app holds the camera — **recoverable**; CameraX keeps retrying and the stream resumes by itself when the camera frees up | Optionally show "camera busy"; do nothing else |
| `CaptureError(camera.permission_denied)` / `camera.unavailable` and the stream **completes** | Critical — permission missing or a fatal camera failure | Fix the cause (request permission), then `start()` again |
| `CaptureError(camera.ocr_failed)` | One frame's OCR failed; the stream continues | Nothing — next frame proceeds |

This split is device-verified (a transient camera grab by another app produced exactly one non-terminal `camera.in_use`, then the stream resumed on its own).

## Known limitation — decode reliability (read this)

Locating the MRZ in real OCR output is the SDK's current weak spot: the candidate matcher expects exact ICAO line shapes, and ML Kit collapses long `<` filler runs (and reads `<<` as `«`), so **a screen-rendered MRZ will generally not decode** — frames stream, `NoMrzFound`/rejected candidates come back, and the parser correctly refuses the garbage. This is recorded, not hidden: see "Camera MRZ-candidate detection vs real OCR output" in [open-questions](../open-questions.md); a length-tolerant matcher / ROI crop is planned with the 0.3.0 work. Test against a **printed** document with a real OCR-B zone, and treat decode-rate tuning as in-progress.

## Troubleshooting

- **Stream emits only `NoMrzFound`** — see the limitation above; you are likely pointing at a screen or a low-contrast print. Check `result.quality.recognizedLineCount` — if it is 0, the camera image is the problem (focus/light), not the matcher.
- **`PermissionDenied` despite requesting** — the error arrives on the *results* stream, possibly before your request completed; `start()` again after the grant.
- **Nothing at all happens** — `start()` before a collector is fine (the flow is hot with a small buffer), but make sure the `lifecycleOwner` is at least STARTED; CameraX will not open a camera for a stopped lifecycle.

## Next

- [MRZ Camera Reading](../features/mrz-camera-reading.md) — the capability reference: parsing modes, quality signals, the analyse-frame core for custom camera stacks.
- [Getting Started](../getting-started.md) — what to do with the `ParseResult` you just received.
- [reading-risks.md](../reading-risks.md) — what a live-camera read does and does not establish.
