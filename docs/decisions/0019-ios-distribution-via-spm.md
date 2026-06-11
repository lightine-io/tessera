# ADR-019: iOS distribution via Swift Package Manager

**Status:** Accepted (execution deferred to the iOS slice)

---

## Context

`0.2.0` ships Tessera for iOS ([ADR-017](0017-mobile-targets-and-build-stack.md)). The JVM and Android artifacts are distributed through Maven Central ([ADR-016](0016-maven-coordinates-and-first-publish.md)), but **native Swift/iOS consumers do not use Maven Central or Gradle** — Xcode integrates dependencies through CocoaPods or Swift Package Manager (SPM). To let an iOS developer consume Tessera, it must be published in a form their toolchain understands. The open question "Distribution channels" ([`open-questions.md`](../open-questions.md)) left the iOS channel undecided.

## Decision

**Distribute the iOS build via Swift Package Manager.** Kotlin/Native produces an **XCFramework**, wrapped in a **Swift package** (`Package.swift` referencing the framework) that an iOS developer adds in Xcode. CocoaPods is **not** used.

This sits within a **one-channel-per-ecosystem** distribution model, all fed from the single KMP codebase and **lockstep-versioned** (extending [ADR-016](0016-maven-coordinates-and-first-publish.md)):

| Consumer ecosystem | Channel |
|---|---|
| JVM, Android, desktop-JVM | Maven Central (already live) |
| iOS / Swift | **SPM** (XCFramework → Swift package) |
| Web (JS/Wasm) | npm (future, when the web target activates) |

**Execution is deferred to the iOS implementation slice.** One slice-time detail — whether `Package.swift` lives in this repository or a dedicated distribution repository — is left open here; both are viable and the choice does not affect this decision. *(Resolved at the slice: dedicated distribution repository — see [Execution notes](#execution-notes-020-ios-slice).)*

## Consequences

**Positive:**
- iOS consumers integrate Tessera through their native, first-party dependency manager.
- One codebase, multiple ecosystem channels, all on the same version — no divergence.
- Avoids CocoaPods, which is in reduced maintenance.

**Negative:**
- SPM distribution of a KMP binary (XCFramework + Swift package, often via a Git repo rather than a central registry) is more bespoke than Maven Central's central-repository model; the packaging mechanics are built at the iOS slice.
- The Swift-facing API surface depends on Kotlin/Native's Objective-C/Swift export; a clean Swift experience requires the headless API to be designed Swift-friendly (see [ADR-020](0020-camera-reading-architecture.md)).

**Neutral:**
- The JVM / Android distribution (Maven Central) is unchanged.

## Alternatives Considered

- **CocoaPods.** Rejected: effectively legacy / reduced-maintenance; SPM is Apple's official, Xcode-integrated manager and the forward-looking choice.
- **Both SPM and CocoaPods.** Rejected for now: doubles packaging maintenance for a declining channel; revisit only if real consumer demand for CocoaPods appears.

## Execution notes (0.2.0 iOS slice)

The packaging mechanics were built in the `mrz-camera-ios` slice; these resolve the slice-time details left open above. The actual publication (creating the distribution repo, attaching the release asset) landed with the `0.2.x` release cut — **executed 2026-06-07, shipped as `v0.2.1`**. The verified step-by-step flow is recorded as the [SPM release runbook in `publishing-setup.md`](../publishing-setup.md).

- **Umbrella module + name.** The `mrz-camera-ios` module is the umbrella: it assembles a single **`Tessera` XCFramework** (`import Tessera`) via the `XCFramework("Tessera")` DSL + a static framework binary on each iOS target (`iosArm64`, `iosSimulatorArm64` — ARM-only; the `iosX64` Intel-simulator slice was dropped before the `0.2.0` tag, see [ADR-017](0017-mobile-targets-and-build-stack.md)). One product, one import — the lockstep one-SDK model ([ADR-016](0016-maven-coordinates-and-first-publish.md)); split into submodules only if the SDK grows large. `isStatic = true` (an SPM `binaryTarget` consumer embeds no dynamic framework to code-sign).
- **Exported surface.** The framework explicitly `export`s `mrz-camera-core`, `mrz-core`, `types`, and `telemetry`, so their public types (`AVCaptureMrzScanner`, `MrzFrameAnalyzer`, `MrzCameraScanner`, `CameraError`/`MrzScanResult`, `ParseResult`, `MrzFormat`, the format specs, `TelemetrySink`) appear in the generated Obj-C/Swift headers rather than as opaque handles. Explicit exports (not the experimental `transitiveExport`) keep the surface controlled and avoid dragging coroutines' internal `atomicfu` interop into the export.
- **`Package.swift` location → dedicated repository.** Per JetBrains' KMP distribution guidance, the Swift package manifest lives in a **dedicated distribution repo** (planned: `lightine-io/tessera-swift`) with a `binaryTarget(url:checksum:)` pointing at the zipped XCFramework attached to the main repo's GitHub release. This keeps the binary out of git and gives iOS consumers a lean clone, and — since 0.2.0 is the first iOS release — fixes a stable consumption URL from the start. Chosen over an in-repo `Package.swift` (heavier consumer clone, tag-namespace coupling); revisitable, but the first-release URL stability argues for doing it cleanly now.
- **Distributable pipeline (wired now).** `./gradlew :mrz-camera-ios:packTesseraXCFramework` assembles the release XCFramework and zips it to `build/distributions/Tessera.xcframework.zip` — the artifact an SPM `binaryTarget` consumes. The `ios-compile` CI job assembles the (debug) XCFramework on every PR, so a broken `export(...)` fails CI rather than the release. **Deferred to the release cut (slice 9):** create the distribution repo, attach the zip to the 0.2.0 GitHub release, run `swift package compute-checksum`, and write the final `Package.swift`. *(Executed 2026-06-07 with `v0.2.1`: `lightine-io/tessera-swift` created and protected, zip attached by the release workflow, checksum verified, `Package.swift` written — `swift-tools-version:6.0`, since `.iOS(.v18)` is rejected under 5.9.)*
- **Artifact authenticity (decide at the release).** The SPM `binaryTarget` `checksum` is a SHA-256 of the zip: it binds consumers to *integrity* (a tampered zip is rejected) but not *authenticity* (nothing ties the zip to a project-controlled key, and a GitHub release asset URL is mutable — a publisher-account compromise could swap both the asset and the `Package.swift` checksum). For a `0.x` first release to a small audience this is a low-probability gap, but the release runbook should make a deliberate call: at minimum publish the expected checksum in a second, independent channel (e.g. the main repo's `CHANGELOG`/release notes, so an attacker must compromise both repos), and consider Apple Developer ID `codesign`ing the framework slices before zipping (cost: Apple Developer Program + certificate management). Surfaced by the slice's security review. *(Decided at `v0.2.1`: the SHA-256 is published in the main repo's GitHub Release body as the second, independent channel; Developer ID codesigning stays deferred.)*
- **Swift `Flow` ergonomics.** `MrzCameraScanner.results` is a Kotlin `Flow`, exposed to Swift as a Kotlin handle rather than Swift-native async/`AsyncSequence`. A nicer Swift experience (e.g. via SKIE, or a suspend/callback adapter) is a deferred refinement, tracked in [`open-questions.md`](../open-questions.md) — it does not block first distribution.

## Related Decisions

- [ADR-016](0016-maven-coordinates-and-first-publish.md) — Maven coordinates and lockstep versioning; this extends the distribution model to iOS.
- [ADR-017](0017-mobile-targets-and-build-stack.md) — enables the iOS target whose output this distributes.
- [ADR-020](0020-camera-reading-architecture.md) — the Swift-friendly headless API that determines the SPM consumer's experience.

## Related Documents

- [`../scope.md`](../scope.md) — distribution channels.
- [`../architecture.md`](../architecture.md) — iOS modules and outputs.
- [`../open-questions.md`](../open-questions.md) — "Distribution channels" (iOS portion resolved here; npm/web remains future).
