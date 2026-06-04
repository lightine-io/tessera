# ADR-021: Shared `mrz-camera-core` module

**Status:** Accepted

---

## Context

The platform-agnostic camera-reading contract — the analyse-frame core (`MrzFrameAnalyzer<F>`), the `scan(Flow<F>)` streaming engine, the `MrzCameraScanner` owns-session interface, the `MrzTextRecognizer<F>` OCR seam, and the shared `MrzScanResult` / `CameraError` / `ScanQuality` / `RecognizedText` types — was built Android-first ([ADR-020](0020-camera-reading-architecture.md)) and lived in **`mrz-camera-android`'s `commonMain`**, host-tested through that module's `jvm()` target. Nothing in it is Android-specific.

The iOS module (`mrz-camera-ios`) needs the same contract. [`open-questions.md`](../open-questions.md) ("Shared camera-contract module vs keeping the contract in `mrz-camera-android`") parked the choice for the iOS slice, to be made with the contract shape validated against AVFoundation/Vision — because [ADR-007](0007-strict-backward-compat-from-0x.md) locks the module structure and public surface from `0.x`, so it must settle before the `0.2.0` tag. Per [Principle 11](../principles.md) (internal-package-first), a standalone module is justified once a second consumer exists — the iOS scanner is that consumer.

## Decision

**Extract the contract into a standalone `mrz-camera-core` module.** `mrz-camera-android` and `mrz-camera-ios` become thin platform-I/O modules that depend on it.

- `mrz-camera-core` declares the full target set — `jvm` (host tests), `android`, and `iosArm64` / `iosSimulatorArm64` (ARM-only; the `iosX64` Intel-simulator target was dropped before the `0.2.0` tag, see [ADR-017](0017-mobile-targets-and-build-stack.md)) — and is pure `commonMain` (no platform code of its own); it `api`-exports `types`, `mrz-core`, `telemetry`, and kotlinx-coroutines.
- `mrz-camera-android` keeps only `androidMain` (CameraX + ML Kit) and depends on `mrz-camera-core`; it no longer declares a `jvm()` target (the host tests moved with the contract).
- `mrz-camera-ios` (this slice) holds only `iosMain` (AVFoundation + Apple Vision) and likewise depends on `mrz-camera-core`.
- The Kotlin package stays `io.lightine.tessera.mrz.camera` across all three modules (a package may span modules); only the Android `namespace` differs per module.

This validated the contract against AVFoundation/Vision with **no change to its shape** (the iOS frame type binds `F = CMSampleBufferRef`, mirroring Android's `F = ImageProxy`).

## Consequences

**Positive:**
- One source of truth for the contract — no duplication between platforms, and the host tests live in one place, running on both the JVM and the iOS simulator.
- The core carries **no platform camera dependency**, so the analyse-frame seam ([ADR-020](0020-camera-reading-architecture.md)'s extension point) is consumable on its own by a future non-camera frame source (USB document reader, webcam, desktop, web) without dragging CameraX or AVFoundation.
- Each platform module's distribution boundary is crisp: `mrz-camera-android` → Maven AAR, `mrz-camera-ios` → SPM XCFramework ([ADR-019](0019-ios-distribution-via-spm.md)).

**Negative:**
- One more module in the graph than the Android-first layout had.
- At the SPM packaging slice, the XCFramework built from `mrz-camera-ios` must **export** `mrz-camera-core` (Kotlin/Native `export(...)`) so the contract types are visible to Swift consumers. *(Done — the `Tessera` XCFramework explicitly exports `mrz-camera-core` plus `mrz-core` / `types` / `telemetry`; see [ADR-019 execution notes](0019-ios-distribution-via-spm.md#execution-notes-020-ios-slice).)*

**Neutral:**
- No public API change — the types and packages are identical, only relocated. Nothing is newly published; `mrz-camera-core` is unpublished this slice, exactly as `mrz-camera-android` is (coordinates/BOM are a `0.2.0`-release-slice concern).

## Alternatives Considered

- **A single `mrz-camera` module** with `commonMain` + `androidMain` + `iosMain` all in it. Rejected: it contradicts the architecture's separate-platform-module commitment ([architecture.md](../architecture.md), [ADR-003](0003-modular-architecture.md)), and it offers no dependency-free core for a non-camera frame source to depend on.
- **Keep the contract in `mrz-camera-android`** and have `mrz-camera-ios` depend on it. Rejected as incoherent: an iOS module depending on an Android-named one (and `mrz-camera-android` would have to declare iOS targets purely to host the shared contract).

## Related Decisions

- [ADR-020](0020-camera-reading-architecture.md) — the camera-reading contract this relocates; names the analyse-frame seam as the extension point that benefits from a dependency-free core.
- [ADR-003](0003-modular-architecture.md) — modular architecture; this keeps platform I/O in per-platform modules and pure logic in a `-core` module.
- [ADR-017](0017-mobile-targets-and-build-stack.md) — the mobile targets this module declares.
- [ADR-019](0019-ios-distribution-via-spm.md) — iOS distribution; the XCFramework must export this core.

## Related Documents

- [`../architecture.md`](../architecture.md) — the module list and dependency graph (the `mrz-camera-core` node).
- [`../open-questions.md`](../open-questions.md) — "Shared camera-contract module…" (resolved here).
- [`../features/mrz-camera-reading.md`](../features/mrz-camera-reading.md) — the feature this contract powers.
