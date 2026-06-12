# Open Questions — Resolved Archive

Fully-resolved entries graduated out of [`open-questions.md`](open-questions.md), grouped under the section each entry originally lived in. Entries here are **closed**: the decision was made and recorded where the entry's Resolution says. Nothing is deleted — this file preserves the full deferral-to-resolution history; `open-questions.md` stays lean for its actual job (what is open *now*).

Convention: when an entry in `open-questions.md` becomes fully resolved (no open sub-question, no live trigger), a housekeeping pass moves it here verbatim. Partially-resolved entries stay in the main file until every part closes.

---
## Deferred to Implementation Time

### Code style tooling

Code style conventions (formatter, linter, configuration choices) are deferred until implementation begins. The current commitment is that code follows the idiomatic conventions of each target language; the specific tools are chosen and documented when first used.

**Source:** `conventions.md` ("Code Style" section)

**Resolution:** Resolved — Kotlin code is formatted and linted via Spotless (applied at the project root) with a ktlint backend, configured against `.editorconfig` and the Kotlin official style. See the "Code Style" section of `conventions.md` for details.

### Project root namespace

The root namespace for package paths is **`io.lightine.tessera`**. The `io.` prefix follows the modern convention for developer tools and SDKs; `lightine` is the brand segment; `tessera` is the project segment.

**Source:** `architecture.md`, `conventions.md`

**Resolution:** Resolved — root namespace is `io.lightine.tessera`. Sub-package structure (e.g., `io.lightine.tessera.mrz.parsing`) emerges as code is written.

### Specific date inference thresholds

The validator uses specific thresholds for date plausibility checks (130 years for date of birth, etc.). These are documented as defaults and configurable; the actual default values may be tuned during implementation based on testing against real-world data.

**Source:** `mrz-validation.md` ("Date Range Conventions")

**Resolution:** Resolved — the defaults are implemented and documented (e.g. the 130-year date-of-birth plausibility window) in [`mrz-validation.md`](features/mrz-validation.md). They remain configurable; further tuning would update that doc.

### Country code recognition validation (`MrzUnknownCountryCode`)

`mrz-error-taxonomy.md` lists `MrzUnknownCountryCode` as a representative warning: the issuing state or nationality code is not in the recognized lookup tables. The first validator slice did not produce this output because the SDK did not yet have a `CountryCode` value class or `CountryCodeTable`.

**Source:** First validator implementation slice; aligns with `lookup-tables.md` ("Initial Country Code Coverage").

**Resolution:** Resolved — implemented for all five formats (TD3, TD2, TD1, MRV-A, MRV-B) in `MrzValidator`. `CountryCode` value class and `CountryCodeTable` landed in `mrz-core` (per [ADR-012](decisions/0012-recognition-types-live-with-tables.md)); `CommonFields.issuingState` and `CommonFields.nationality` changed from `String` to `CountryCode`. The validator emits up to two `MrzUnknownCountryCode` warnings per document — one for `issuingState` (line 1 position 2 on every format) and one for `nationality` (position 54 for TD3 / MRV-A, 46 for TD2 / MRV-B, 45 for TD1) — distinguished by a `field: MrzField` discriminator. The categorical placement (warning, not failure) is the same as `MrzUnknownDocumentTypeCode` per [ADR-013](decisions/0013-recognition-failures-are-warnings.md). The table-completeness question is tracked separately under "Country code table completeness" below.

### Document type code recognition validation (`MrzUnknownDocumentTypeCode`)

`mrz-error-taxonomy.md` lists `MrzUnknownDocumentTypeCode`. The `DocumentType` value class and `DocumentTypeCodeTable` already exist (with a starter set), so the recognition signal is available via `DocumentType.isRecognized`. The first validator slice did not produce this output to keep the slice focused on closing the check-digit translation-owed loop.

**Source:** First validator implementation slice.

**Resolution:** Resolved — implemented for all five formats (TD3, TD2, TD1, MRV-A, MRV-B) in `MrzValidator`. The categorical placement (warning vs. validation failure) is recorded in [ADR-013](decisions/0013-recognition-failures-are-warnings.md): a recognition-table-derived check that reduces to "this code is not in our table" is a warning, because the SDK's tables are deliberately incomplete and overclaiming non-conformance would violate Principle 1 (Reader, not oracle) and Principle 4 (Honest about what we know). The check runs unconditionally for every parsed document; the warning carries the verbatim `rawCode` and the field's start position (always 0 — the document type slot is at line 1 character 1 on every format). The table-completeness question is tracked separately under "Document type code table completeness" below. The same categorical placement applies to `MrzUnknownCountryCode` (resolved above).

### Date-in-calendar validation (`MrzDateNotInCalendar`)

`mrz-error-taxonomy.md` lists `MrzDateNotInCalendar` as a representative validation failure: a date is structurally well-formed (six digits) but does not represent a real calendar date (e.g., February 30). The current parser already tolerates this — `MrzDate.parseBirth` and `parseExpiry` return `MrzDateInferenceMethod.RAW_ONLY` when the components do not form a valid date — but the validator does not surface a failure for it.

**Source:** First validator implementation slice; aligns with `mrz-validation.md` "Layer 3 — Semantic" and `mrz-error-taxonomy.md`.

**Resolution:** Resolved — implemented for all five formats (TD3, TD2, TD1, MRV-A, MRV-B) in `MrzValidator` for both `dateOfBirth` and `dateOfExpiry`. The dispatch is signal-driven from a new tri-state property on the model: `MrzDate.componentsFormCalendarDate: Boolean?`. The original `RAW_ONLY` enum value collapsed three distinct failure modes; the new property disambiguates them so the validator can emit `MrzDateNotInCalendar` only for the "components numeric but no calendar date" case, leaving "non-numeric components" (Layer-1 territory) and "calendar-valid but outside the parser's inference window" (a date that IS in the calendar) untouched. See `docs/features/mrz-data-model.md` "MrzDate" and `docs/features/mrz-validation.md` "Status of Implementation."

### Expiry-date warnings (`MrzExpiryDatePast`, `MrzExpiryDateImplausiblyFar`)

`mrz-error-taxonomy.md` lists `MrzExpiryDatePast` and `MrzExpiryDateImplausiblyFar` as representative warnings. The first validator slice produces no warnings (`ValidationResult.warnings` is always empty for now); these are the natural first warning slice.

**Source:** First validator implementation slice; aligns with `mrz-validation.md` "Date Range Conventions" and `mrz-error-taxonomy.md`.

**Resolution:** Resolved — both warnings are implemented for all five formats (TD3, TD2, TD1, MRV-A, MRV-B) in `MrzValidator`. `MrzValidator.validate(...)` accepts an explicit `referenceTime` (defaulting to `Clock.System.now()`); each format-specific parser threads its own `referenceTime` through. `MrzExpiryDateImplausiblyFar` carries `thresholdYears` (defaulting to 10) on the warning itself. Configurability of the threshold is its own deferred question — see "Validator options (configurable thresholds)" below.

### TD1 validator path

The first validator slice handles only TD3. For TD1 inputs, `MrzValidator.validate(...)` returns an empty `ValidationResult` (no failures, no warnings) because TD1 has no parser yet, so there is no integration test path that would exercise a TD1 validator end-to-end. Implementing TD1's composite check digit formula without a TD1 parser to drive it would produce code that compiles and runs but is not meaningfully tested against real parsed input.

**Source:** First validator implementation slice; aligns with the TD1 data-class-only state (PR #1 slice 8).

**Resolution:** Resolved — the TD1 parser and validator landed together in the TD1 parser slice. `MrzValidator.validate(...)` dispatches `is TD1` to a real `validateTD1` (replacing the previous empty-result stub) covering per-field check digits, composite check digit, sex range, calendar-date validation, expiry warnings, birth-age warning, recognition warnings, and name truncation — all driven by integration tests through `MrzParser.parseTD1`. See `docs/features/mrz-validation.md` "Status of Implementation" for the table.

---

## Deferred to a Future Release

### GitHub repository topics for discoverability

The repository on GitHub has no topics set, which limits discoverability through GitHub's topic search and the homepage's topic-based recommendations. Candidate topics include `kotlin`, `kotlin-multiplatform`, `mrz`, `icao-9303`, `passport`, `identity-document`; per-release additions follow as new capabilities land (e.g., `nfc` and `emrtd` when 0.6.0 ships, `android` and `ios` when platform-I/O modules activate). The question deferred is *which* topics and *when*, not *whether* to have them.

**Source:** `SESSION-HANDOFF-2026-05-21-1348-v-0-1-0-shipped-and-protected.md` "Things to Watch For" carry-forward; reaffirmed in the 2026-05-22 session close-out conversation.

**Resolution:** Pick an initial set and apply via `gh repo edit --add-topic ...`. Establish a maintenance rhythm of reviewing topics at each release milestone — add topics as capabilities land, remove ones that no longer describe scope. Either a small follow-up PR or fold into the next release-prep pass.

**Resolved (2026-06-12):** applied via `gh repo edit` — `kotlin`, `kotlin-multiplatform`, `mrz`, `icao-9303`, `passport`, `identity-document`, plus `android` and `ios` now that the platform I/O modules are published. The maintenance rhythm stands: review topics at each release milestone (e.g. add `nfc`/`emrtd` when 0.6.0 ships).

### Pre-tag: decided context is being lost across sessions (handoff/memory gap)

**Flagged 2026-06-06 (maintainer).** During pre-0.2.0 work, Claude repeatedly re-stated *superseded* facts as if current — e.g. citing local signing via `~/.gradle/gradle.properties` and a key id "in the publishing config" **after** the project had already decided (Q5/Q10) to publish from **GitHub Actions with credentials in GitHub secrets only, never on the laptop**, and to **remove the Homebrew Gradle + local secrets at A3 cleanup**. Decided context is not surviving reliably between sessions; the session-handoff / memory mechanism appears to have a gap.

**Why pre-tag:** A3 (the release) is credential-sensitive — context drift there risks both mistakes and credential mishandling.

**To discuss (not resolved here):** what is failing (handoff content, memory, or Claude not reconciling against them?), and how to make decided facts stick before release work — e.g. a release-time checklist Claude must verify against, stronger handoff structure, or graduating the A3 decisions into a committed runbook.

**Resolution (2026-06-10):** Resolved structurally, after a second confirmed instance pinned the root cause. The instance: at the first post-release session start, the deferred post-tag action-plan items (`.plans/` §5, trigger "after the tag" — which had just fired) were skipped entirely, because the release handoff honestly-but-wrongly reported "nothing is in flight" and the session-start ritual consulted no other store. Diagnosis: **handoffs are snapshots authored from the closing session's working memory; standing obligations live in stores (`.plans/`, open-questions Trigger lines) that neither handoff-writing nor session-start swept** — so anything outside the closing session's head silently dropped out of the chain. The mechanism (landed 2026-06-10): (1) action-plan items carry `- [ ]`/`- [x]` status, making open work greppable; (2) the CLAUDE.md session-start ritual gains a standing-obligations sweep step; (3) the handoff template ([`.claude/session-handoff-template.md`](../.claude/session-handoff-template.md)) is rewritten for its actual reader — the next AI session — with a mandatory "⭐ START HERE" state snapshot, a "Standing Obligations" section *filled by looking, not remembering*, and a "Superseded This Session" section that retires stale facts; (4) a `SessionStart` hook ([`scripts/standing-obligations.sh`](../scripts/standing-obligations.sh)) injects unchecked plan items into every session mechanically — structural, not willpower. The entry's other named failure — a committed doc still teaching a superseded fact (`publishing-setup.md` presenting local credential storage as current) — is confirmed by the 2026-06-10 repo audit and is fixed separately as a doc-currency item.

### Code-review subagent (vs. the existing review skills)

A code-*review* subagent — a read-only reviewer in the `doc-consistency-reviewer` / `security-reviewer` / `qa-coverage-reviewer` family, focused on the correctness and quality of a diff — was raised as a possible fourth project agent. It was **declined for now** (2026-06-06, after discussion); the maintainer will revisit it on his own initiative. The question it weighed was whether a dedicated standing subagent earns its keep *over the code-review capability that already exists as skills* (`/code-review`, `/review`, `/simplify`, and `/security-review` for the security slice): a subagent buys an isolated context window and a fixed, project-tuned remit (the value the existing three reviewers provide), but general code review is broad and judgment-heavy — closer to `security-reviewer`'s advise-don't-dictate shape than to the mechanical doc-sync / coverage checks — so the risks are (a) overlap with the skills and (b) the review-**Q9** caution to "defer other agents (conformance, API-ergonomics, …) until a real recurring need shows — avoid the over-build trap."

**Source:** raised by the maintainer 2026-06-06 alongside the `qa-coverage-reviewer` work (action-plan C5); source-of-truth framing for "add one agent, defer the rest" is review **Q9** (`.reviews/REVIEW-2026-06-01-decisions.md`).

**Resolution:** **Declined for now (2026-06-06).** Use the existing review skills (`/code-review`, `/simplify`, `/security-review`) for diff-level review; no fourth agent is added at this time. The maintainer will revisit on his own initiative if a real recurring need appears. Entry kept (not deleted) so the decision and its framing stay on record.

### Android target configuration on core modules

Core modules are scaffolded with the JVM target only. The Android target is intentionally deferred until 0.2.0 work begins, when the first Android-touching module (`mrz-camera-android`) is introduced and AGP needs to be added to the build anyway. Adding `androidTarget()` to the pure-logic core modules earlier would buy only theoretical insurance against Android-incompatible APIs sneaking into `commonMain`, at the cost of pulling AGP and its version-coupling constraints into the build before they earn their keep (Principle 2: the option that assumes less wins; Principle 11: don't promote infrastructure before it's justified). The Android SDK is already installed on the development machine; the deferral is by intent, not by tooling gap.

**Source:** Pre-implementation scaffolding session; aligns with Principles 2 and 11.

**Resolution:** Resolved (2026-05-29 0.2.0 pre-release review) — the Android target is enabled on the core modules per [ADR-017](decisions/0017-mobile-targets-and-build-stack.md), implemented in the 0.2.0 build-foundation slice. **The `androidTarget()` approach in this entry's original resolution is superseded:** on Kotlin 2.3.21 + AGP 9 the `androidTarget` block errors, so the target is added via Google's `com.android.kotlin.multiplatform.library` plugin instead.

### Shared camera-contract module vs keeping the contract in `mrz-camera-android`

The platform-agnostic camera-reading contract — the `MrzCameraScanner` interface, the `scan(Flow)` streaming engine, the analyse-frame core (`MrzFrameAnalyzer`), and the shared types (`MrzScanResult`, `CameraError`, `ScanQuality`, `RecognizedText`) — currently lives in **`mrz-camera-android`'s `commonMain`**, host-tested via that module's `jvm()` target. Nothing in it is Android-specific (ADR-020). The open question is whether to **extract it into a shared module** (e.g. `mrz-camera-core`) that both `mrz-camera-android` and the future `mrz-camera-ios` depend on, or to keep it where it is.

**Source:** Surfaced at the 0.2.0 headless-contract slice (slice 4), which the prior slice flagged as the point to decide module structure.

**Status:** Deferred to the `mrz-camera-ios` slice (Principle 11 — internal-package-first; promote to a standalone module only when a second consumer justifies it). Keeping the contract in `mrz-camera-android` for now costs nothing — the `jvm()` target already host-tests it with no device — and avoids standing up a module before there is a second consumer. The forcing function is the iOS scanner: when `mrz-camera-ios` is written, either it depends on `mrz-camera-android` (awkward — an iOS module depending on an Android-named one) or the contract is extracted first. The latter is the likely outcome, decided then with the contract shape validated against AVFoundation (the validation ADR-020 already calls for before the 0.2.0 tag).

**Trigger:** Start of the `mrz-camera-ios` slice.

**Resolution:** Resolved (2026-05-30, start of the `mrz-camera-ios` slice) — **the contract was extracted into `mrz-camera-core`**, per [ADR-021](decisions/0021-shared-mrz-camera-core-module.md) (the "likely outcome" above). `mrz-camera-android` becomes an Android-only platform-I/O module depending on the core; `mrz-camera-ios` mirrors it. The contract shape was validated against AVFoundation/Vision first — it holds unchanged (the iOS frame type binds `F = CMSampleBufferRef`, mirroring Android's `F = ImageProxy`). The deciding factor beyond the awkward-dependency forcing function: a dependency-free `mrz-camera-core` lets ADR-020's analyse-frame extension seam (USB / desktop / web frame sources) be consumed without dragging either platform's camera stack.

### `CameraInUse` live verification (Android + iOS)

`CameraXMrzScanner` surfaces capture-open failures from CameraX's camera state (the live-device slice — see [`mrz-error-taxonomy.md`](features/mrz-error-taxonomy.md)). The `PermissionDenied` / `CameraUnavailable` paths were device-verified (revoke the `CAMERA` permission → `PermissionDenied`; granted → clean streaming, no spurious error). The `CameraInUse` mapping (`ERROR_CAMERA_IN_USE` / `ERROR_MAX_CAMERAS_IN_USE` → `CameraError.CameraInUse`) is in place but was **not** device-exercised, because reproducing it needs a second client holding the camera.

The authoritative androidx [`CameraState`](https://developer.android.com/reference/androidx/camera/core/CameraState) classification splits the error codes into **recoverable** — `ERROR_MAX_CAMERAS_IN_USE` (1), `ERROR_CAMERA_IN_USE` (2), `ERROR_OTHER_RECOVERABLE_ERROR` (3): CameraX *retries automatically* and parks the camera in `PENDING_OPEN` while it waits — and **critical** — `ERROR_STREAM_CONFIG` (4), `ERROR_CAMERA_DISABLED` (5), `ERROR_CAMERA_FATAL_ERROR` (6), `ERROR_DO_NOT_DISTURB_MODE_ENABLED` (7), `ERROR_CAMERA_REMOVED` (8): no retry, camera goes `CLOSED`. (There is **no** dedicated permission code; a denial surfaces as the generic critical `ERROR_CAMERA_FATAL_ERROR` — which is why the scanner classifies it via a read-only permission check.) Two things stay unverified for the in-use case: (1) that CameraX reports an in-use *open* as one of the recoverable codes on a real device, and (2) whether the current **terminal** contract is right for it — the scanner closes the stream on *any* state error, but for a *recoverable* code CameraX is still retrying, so terminating is likely premature. The verified path (permission → critical `FATAL`) *is* genuinely terminal, so the current behavior is correct there; the open design point is whether recoverable codes should instead be surfaced non-terminally (or not at all, letting CameraX recover) rather than ending the session.

The iOS scanner (`AVCaptureMrzScanner`) now adds a second dimension to the same question. It maps an `AVCaptureSession` interruption with reason `videoDeviceInUseByAnotherClient` to `CameraInUse`, and a session runtime error to `CameraUnavailable`; permission is read from `AVCaptureDevice.authorizationStatus(for:)`. None of this is exercised on the iOS Simulator (it has no camera), so the iOS scanner is **compile-verified on CI and device-verified separately**, exactly as the Android scanner shipped.

**iOS device run (2026-05-31, iPhone 15 Pro / iOS 26.5, throwaway SwiftUI harness consuming the `Tessera` XCFramework).** Verified on-device: live frame streaming; the permission-granted path (`notDetermined` → prompt → `authorized` → streaming); background → `INTERRUPTED reason=1` → foreground → `INTERRUPTION_ENDED` → auto-resume, repeatedly, with reason 1 correctly **not** surfaced as a terminal error; and — newly closing the iOS half of the gap above — the **`CameraInUse` path live**: an interruption with `reason=3` (`videoDeviceInUseByAnotherClient`) mapped to `CameraError.CameraInUse` (`camera.in_use`) and surfaced as a terminal `CaptureError`. Apple Vision runs on the live frames (recognized-line counts > 0). Still not device-exercised on iOS: a clean end-to-end `Decoded` lock from a live frame (screen-rendered MRZ is poor OCR input — glare/sub-pixel; a printed page or real passport would decode), and the `CameraUnavailable` runtime-error path.

**RESOLVED (2026-05-31) — the iOS capture stall was the Kotlin/Native GC reclaiming the weakly-held sample-buffer delegate.** Symptom: with Vision active, `AVCaptureVideoDataOutput` stopped delivering frames (session still running, no interruption, **zero dropped-frame callbacks**) after a few seconds; the frame count to stall varied (≈17–124) with how much memory each frame allocated. Two layered causes were found; the second was decisive:

1. **Vision must run on an independent copy, not the camera buffer.** Per Apple's [AVFoundation guidance](https://developer.apple.com/forums/thread/679250), a sample buffer held beyond the delegate callback (Vision is slower than the frame interval) must be copied so the capture pool can recycle. `VisionMrzTextRecognizer` byte-copies each frame — row by row, honouring each plane's stride — into a single reused, plain heap `CVPixelBuffer` and runs Vision on the copy, keeping Vision off the finite `AVCaptureVideoDataOutput` pool. (Two earlier hypotheses were **wrong**: that the persistent stall was pool starvation, and that the copy needed to be *IOSurface-backed* for Vision to read it. An IOSurface copy stalled identically — it was cause #2 all along — and OCR works fine on the plain heap copy; the equal source/destination strides confirmed the copy was never the OCR blocker.)

2. **The decisive cause — the capture delegate was being garbage-collected.** `AVCaptureVideoDataOutput` holds its `setSampleBufferDelegate` target **weakly** (Cocoa convention, like most delegates). The delegate was created inline with no strong Kotlin reference; Kotlin/Native reclaims unreferenced objects by **GC, not ARC**, so the next GC pass freed the delegate, the output's weak pointer went `nil`, and the camera silently stopped — no error, no drops. This is why the stall scaled with allocation (more memory → GC fires sooner → delegate dies sooner) and why earlier theories chasing the *symptom* (frame counts, IOSurface, memory pressure) all missed. **Proven by forcing `GC.collect()` every frame: capture stalled after a single frame at only 70 MB; adding a strong class-field reference to the delegate → 5,000+ frames with per-frame GC still running, zero stalls.** Fix: `AVCaptureMrzScanner` keeps the delegate in a field (`captureDelegate`) for the session's lifetime, cleared on teardown.

**Memory** is bounded by Kotlin/Native's automatic GC plus an **OCR-rate throttle** (`analysisInterval`, ~200 ms ≈ 5 analyses/sec in the scanner — the project's "don't OCR every frame" practice); a single `VNRecognizeTextRequest` is reused across frames to trim per-frame allocation. **No forced GC is needed in shipping code** — the automatic GC reclaims Vision's native allocations on its own.

**Device-verified (2026-05-31, iPhone 15 Pro / iOS 26.5, throwaway SwiftUI harness on the `Tessera` XCFramework):** the clean shipping build streamed **770 analysed frames with zero freezes and zero dropped frames**, memory a bounded sawtooth **96–253 MB** (auto-GC reclaiming on its rhythm), and **live `Decoded` from a real document — 34 `ParseResult.Success` + 86 `PartialSuccess`**. This also closes the previously-open **live `Decoded` lock** gap. The pitfall (Cocoa weak delegate + K/N GC) is recorded in [`.claude/known-pitfalls.md`](../.claude/known-pitfalls.md).

The full capture-failure → `CameraError` mapping (`PermissionDenied`, `CameraInUse`, `CameraUnavailable`, plus an unknown-failure fallback) is locked by an `iosTest` unit test (`AVCaptureMrzScannerErrorMappingTest`): every failure the scanner detects is surfaced to the consumer as the correct sealed `MrzScanResult.CaptureError` — never thrown, never decided for the caller — matching the Android contract (ADR-020, reader-not-oracle). The one path that **cannot** be device-exercised is the `CameraUnavailable`-from-*runtime-error* trigger: per Apple's [`runtimeErrorNotification`](https://developer.apple.com/documentation/avfoundation/avcapturesession/runtimeerrornotification) and [`AVErrorMediaServicesWereReset`](https://developer.apple.com/documentation/avfoundation/averror-swift.struct/code/mediaserviceswerereset) docs a session runtime error is a genuine media-services / hardware fault, not something an app can summon — so it is accepted as device-unverified-by-necessity, the same standing as Android's `CameraInUse` live scenario. (Interruptions an app *can* cause — backgrounding, another client grabbing the camera — are handled separately and device-verified.)

**RESOLVED — Android in-use behaviour + the terminal-vs-recoverable contract (2026-06-01, Galaxy S24 / API 36, an out-of-repo composite-build harness driving `CameraXMrzScanner`).** Both Android sub-questions are answered. (1) **Which code:** a second camera client grabbing the lens while the scanner runs makes CameraX report `ERROR_CAMERA_IN_USE` (state error **2**) — a *recoverable* code, confirming the hypothesis. (2) **The contract:** the scanner no longer terminates on a recoverable code. The state classification is extracted into a pure `classifyCameraState(code, hasCameraPermission)` returning `Recoverable` (the two in-use codes → `CameraInUse`; `ERROR_OTHER_RECOVERABLE_ERROR` → `CameraUnavailable`) or `Terminal` (every critical code → `CameraUnavailable`, or `PermissionDenied` when the read-only permission check shows it is not held — CameraX's `ERROR_CAMERA_FATAL_ERROR` permission-collapse). A `Recoverable` decision is surfaced as a **non-terminal** `MrzScanResult.CaptureError` while the session stays bound, so CameraX recovers and the stream resumes when the blocker clears; only a `Terminal` decision closes the flow. The decision is host-tested in `mrz-camera-android`'s new `androidHostTest` source set (six tests over all eight `CameraState` codes — the Android counterpart of the iOS mapping test, now run by the `android-compile` CI job). Device-verified and reproduced across runs: the contention surfaced exactly one non-terminal `CaptureError(camera.in_use)` (a dedup latch suppresses repeats while parked) and the stream resumed (`NoMrzFound` again) with **no** terminal completion. Notably the in-use condition here was *transient* — CameraX recovered within a frame interval — so the old terminal contract would have killed the entire scan on a momentary blip; the reader-not-oracle reasoning settled it: report the in-use observation, do not decide a transient condition ends the session. **RESOLVED — iOS symmetry (2026-06-01, iPhone 15 Pro / iOS 26.5, the SwiftUI XCFramework harness).** `AVCaptureMrzScanner` now mirrors Android: a `videoDeviceInUseByAnotherClient` interruption is surfaced as a **non-terminal** `CameraInUse` (a `tryEmit` onto `results`) and the session stays bound, so AVFoundation's `AVCaptureSessionInterruptionEnded` auto-resumes capture (the same recovery the backgrounding reason already used) — the terminal `CameraInUseException` close path is removed. The recoverable reason check (`isVideoDeviceInUseReason`) is host-tested in `iosTest` (the iOS counterpart of Android's `classifyCameraState` test). Device-verified by staging a real reason-3 via **Continuity Camera** (the Mac borrowing the iPhone's lens — app-switching and Continuity both otherwise yield to the foreground app, so a Mac-side camera grab is what produces a genuine `videoDeviceInUseByAnotherClient` against a running foreground session): the interruption surfaced `CAPTURE_ERROR camera.in_use` while the frame counter kept climbing, then `INTERRUPTION_ENDED → DID_START_RUNNING` resumed streaming — reproduced twice, with **no** `results flow completed` (the stream never terminated). Both platforms now treat in-use as recoverable-and-recovering.

**Status:** Resolved (2026-06-01) — both platforms surface a recoverable in-use as a **non-terminal** observation and recover: Android `ERROR_CAMERA_IN_USE` → non-terminal `CameraInUse`, CameraX auto-retries; iOS `videoDeviceInUseByAnotherClient` → non-terminal `CameraInUse`, AVFoundation auto-resumes. Each is host-tested (the `classifyCameraState` classification / the `isVideoDeviceInUseReason` check) **and** device-verified (Galaxy S24; iPhone 15 Pro via Continuity Camera, see above). Also closed earlier: iOS capture stall (RESOLVED), iOS live `Decoded` (device-verified), the capture-error → `CameraError` mappings (unit-test-locked). The one accepted-by-necessity gap is the iOS `CameraUnavailable`-from-runtime-error trigger — a media-services / hardware fault an app cannot summon (Apple docs), so unverifiable by design, not open.

**Source:** 2026-05-30 Android live-device slice; 2026-05-31 iOS device run, stall root-cause + fix, and live-decode verification; 2026-06-01 Android in-use device verification + the non-terminal recoverable contract, and the iOS symmetric non-terminal change + Continuity-Camera device verification.

**Trigger:** Both in-use triggers fired (Android and iOS, 2026-06-01 — iOS staged via Continuity Camera). No remaining proactive test: the iOS `CameraUnavailable` runtime-error path is observable only via an organic media-services / hardware fault or a consumer report, not deliberately reproducible.

## Deferred to a Future Document

### Reading risks documentation

Each reading method (live camera, pre-captured image, manual entry, NFC chip, backend string input) has a different risk profile — what it establishes about the data, what it does not, what classes of attacks or errors are possible, what additional verification consumers might want to layer on top. This documentation lives in its own file (`reading-risks.md`).

**Source:** `scope.md` ("Risk Documentation")

**Resolution:** Resolved — `reading-risks.md` exists.

### Glossary

Terms used throughout the documentation (MRZ, BAC, PACE, eMRTD, TD1, TD2, TD3, MRV-A, MRV-B, SOD, LDS, etc.) would benefit from a single reference. Currently, each term is explained in context where it first appears.

**Source:** Implicit gap; not yet referenced from any doc.

**Resolution:** Resolved — `glossary.md` exists.

### Architecture Decision Records

Several significant decisions made during design (Kotlin Multiplatform choice, native UI per platform, reader-not-oracle as foundational, no verification hooks in initial release, Position A backward compatibility from 0.x, etc.) deserve formal ADR documentation for future contributors.

**Source:** Implicit; conventions document the ADR format but no ADRs exist yet.

**Resolution:** Resolved — the foundational decisions are recorded as ADRs in `docs/decisions/`. See [`docs/decisions/README.md`](decisions/README.md) for the current index. Additional ADRs are added as new significant decisions are made.

### README

A project-front-door document does not yet exist. It will be added when the project moves from internal to public visibility.

**Source:** Implicit; not yet referenced.

**Resolution:** Resolved — `README.md` exists at project root. May be revised before public release as the project's identity finalizes.

### CLAUDE.md (AI handoff document)

A document specifically structured to help AI assistants (Claude Code in particular) work effectively with this project. It would point at the right docs in the right order, capture project-specific context, and codify the working patterns established during design.

**Source:** Implicit; design conversation referenced this as a planned artifact.

**Resolution:** Resolved — `CLAUDE.md` exists at project root, supported by working notes in `.claude/`.

---

## Deferred Pending External Information

### Sex value canonical set per ICAO Doc 9303

`mrz-error-taxonomy.md` lists the valid sex characters as `M`, `F`, `<`, or `X`. The first validator slice uses this set as the allowed characters for `MrzInvalidSexValue`. ICAO Doc 9303 Part 4 §4.1 historically lists `M`, `F`, `<`; later guidance is reported to permit `X` for non-binary documents, and some issuing states use it.

**Source:** First validator implementation slice; aligns with `mrz-error-taxonomy.md` representative-examples list.

**Resolution:** Resolved — Part 4 §4.2.2.2 (with equivalents in Parts 5/6/7) was read during the pre-`0.1.0`-tag conformance audit (2026-05-18, `CONFORMANCE-NOTES-2026-05-18.md` finding F16). The canonical MRZ sex characters per the 2021 Eighth Edition are `M`, `F`, `<` only — `X` is reserved for the VIZ per each part's Note p / Note f. Because real-world practice has adopted `X` in the MRZ for non-binary documents, the validator continues to accept `X` (Principle 1 — reader, not oracle) but now emits a new `MrzSexCharacterX` warning surfacing the spec deviation; `MrzInvalidSexValue` still fires for genuinely invalid characters. The new warning matches the existing `MrzPersonalNumberCheckDigitFiller` pattern for documented real-world deviations. Strict consumers who require literal spec conformance check `warnings.isEmpty()`. See the CHANGELOG `[0.1.0]` section.

### Document type code table completeness

The `DocumentTypeCodeTable` in `mrz-core` originally shipped with a starter set of six codes (`P`, `V`, `I`, `PP`, `PD`, `PS`) — not the complete enumeration committed to in `docs/features/lookup-tables.md` ("Initial Document Type Code Coverage"). The full Part 4 §4.4 harmonized P-prefix set and Part 5 Appendix B `AC` code were absent.

**Source:** First implementation slice for `DocumentType` (2026-05-04 session); aligns with `lookup-tables.md` coverage commitment.

**Resolution:** Resolved — populated during the pre-`0.1.0`-tag conformance audit (2026-05-18, `CONFORMANCE-NOTES-2026-05-18.md` findings F14, F15, F17). The table now contains: legacy single-character codes (`P`, `V`, `I`); the full Part 4 §4.4 harmonized P-prefix set (`PP`, `PE`, `PD`, `PO`, `PR`, `PT`, `PS`, `PL`, `PM`); and the Part 5 Appendix B `AC` Crew Member Certificate code. ~13 entries total. The `PS` displayName was corrected from "Service passport" (the original mislabeling) to "Stateless passport" per Part 4 §4.4. State-specific second-character TD1 / TD2 codes (where Parts 5/6 leave the second character to the issuing state's discretion — only the first character `A`, `C`, or `I` is fixed) are intentionally not enumerated; that open-endedness is documented in `DocumentTypeCodeTable.kt` and `lookup-tables.md`. See the CHANGELOG `[0.1.0]` section.

### Country code table completeness

The `CountryCodeTable` in `mrz-core` originally shipped with a starter set of five ISO 3166-1 alpha-3 state codes (`USA`, `GBR`, `DEU`, `FRA`, `JPN`) — not the complete enumeration committed to in `docs/features/lookup-tables.md` ("Initial Country Code Coverage").

**Source:** First implementation slice for `CountryCode` (2026-05-06 session); aligns with `lookup-tables.md` coverage commitment.

**Resolution:** Resolved — populated during the pre-`0.1.0`-tag conformance audit (2026-05-18, `CONFORMANCE-NOTES-2026-05-18.md` finding F7). The table now contains the full ISO 3166-1 alpha-3 list (~249 entries verified against the published ISO 3166/MA listing) plus the ICAO Doc 9303 Part 3 §5 extensions (Parts A through H — British nationality classes GBD/GBN/GBO/GBP/GBS, Kosovo `RKS`, European Union `EUE`, UN documents UNO/UNA/UNK, other international organizations XPO/XES/XMP/XOM/XDC, stateless and refugee codes XXA/XXB/XXC/XXX, the deprecated `ANT` and `NTZ` retained for documents still in circulation per Part F, the synthetic `UTO` specimen code per Part G, and ICAO's `IAO` code per Part H). ~272 entries total, each categorized per `CountryCodeCategory` (STATE / ORGANIZATION / STATELESS / REFUGEE / HISTORICAL / OTHER). See the CHANGELOG `[0.1.0]` section.

### AZE profile `Ö` / `Ü` empirical verification

ICAO Annex G recommends multiple permitted transliterations for two of the letters in the Roman alphabet of the issuing state coded `AZE`:

- `Ö ö` → `OE` or `O` (state picks)
- `Ü ü` → `UE` or `UXX` or `U` (state picks)

The other AZE-relevant Latin letters (`Ç`, `Ğ`, `İ`, `ı`, `Ş`) had unambiguous Annex G recommendations under no-expansion at conformance time. `AzeTransliterationProfile` originally inherited the `IcaoDefaultTransliterationProfile`'s no-expansion choices (`Ö → O`, `Ü → U`) parsimoniously, given the absence of evidence to the contrary.

**Source:** Pre-`0.1.0`-tag conformance audit (2026-05-18, `CONFORMANCE-NOTES-2026-05-18.md` finding F25) — Phase 4 AZE-profile law research outcome.

**Resolution:** Resolved (2026-05-19). A fluent speaker's confirmation grounded in observed practice and the [ALA-LC romanization table](https://www.loc.gov/catdir/cpso/romanization/azerbaij.pdf) (which gives `Ö → ȯ` and `Ü → u̇`, both single-character with diacritic — stripping to `O` and `U` under MRZ ASCII) converge on the no-expansion form. The AZE profile inherits `Ö → O` and `Ü → U` from `IcaoDefaultTransliterationProfile` unchanged. (The broader empirical pass that resolved this also surfaced 7 other AZE overrides — see the "Transliteration profile coverage completeness" entry above for the full set.)

### LICENSE file at project root

ADR-010 commits the project to the Apache License 2.0 at public release. The `LICENSE` file at project root contains the full Apache 2.0 license text with copyright attribution to "Asker Asadov (Lightine)".

**Source:** ADR-010 (Apache 2.0 license at public release).

**Resolution:** Resolved — `LICENSE` file exists at project root with Apache 2.0 text and proper attribution. A `NOTICE` file is not needed yet because no third-party content currently requires attribution; create one if/when third-party content with NOTICE requirements is incorporated.

### Git platform choice

The project will use Git for version control (decided). The hosting platform is not finalized; GitHub is the leading candidate but other options (GitLab, Codeberg, self-hosted) remain possible.

**Source:** Design conversation about implementation tooling.

**Resolution:** Resolved — GitHub is the chosen hosting platform. CI workflows, issue templates, and any other platform-specific configuration are added in a follow-up before the first public push.

### Project name and brand attribution

The project name is **Tessera** — Latin for an inscribed token used in the Roman world as identification, a pass, or a token of recognition. The semantic fit captures what the SDK does: structured, identifiable data extracted from documents.

The brand attribution is **"Asker Asadov (Lightine)"** — personal name as the legal copyright holder, with Lightine as the project's brand. This balances legal clarity (the rights-holder is a real legal entity, the individual) with brand visibility (Lightine remains visible as the project's umbrella).

**Source:** Design conversation about project identity.

**Resolution:** Resolved — project name is Tessera, attribution is "Asker Asadov (Lightine)" in the LICENSE file.

### iOS target configuration on core modules

Core modules (`mrz-core`, `emrtd-core`, `types`, `telemetry`, `logging`) are scaffolded with the JVM target only. Configuring the iOS targets (`iosX64`, `iosArm64`, `iosSimulatorArm64`) requires Xcode, which is not installed on the development machine where scaffolding was performed. There is no design decision to make — the targets are committed in `architecture.md` and ADR-002. The deferral is purely about toolchain availability.

**Source:** Pre-implementation scaffolding session; depends on Xcode install.

**Resolution:** Resolved (2026-05-29 0.2.0 pre-release review) — **Xcode is now present** (26.5 on the development machine), lifting the toolchain gate noted above ("not installed" is no longer true). The iOS targets are enabled on the core modules per [ADR-017](decisions/0017-mobile-targets-and-build-stack.md), with the Normalization `expect`/`actual` ([ADR-014](decisions/0014-unicode-normalization-strategy.md)) gaining an iOS `actual`; the committed iOS deployment minimum is **18** ([ADR-018](decisions/0018-platform-minimums-and-managed-raise.md)), not the 15.0 this entry's original text referenced. **Executed (2026-05-30)** in the 0.2.0 iOS build-foundation slice: `iosArm64`/`iosSimulatorArm64` declared on the core modules (the `iosX64` Intel-simulator target was enabled at this point then **dropped before the 0.2.0 tag** — ARM-only Mac dev, [ADR-017](decisions/0017-mobile-targets-and-build-stack.md); and the **empty** placeholders `emrtd-core`/`logging` had their iOS targets **removed before the 0.2.0 tag** — an empty module produces no `.klib`, which breaks iOS Maven publication, see CHANGELOG), the iOS `actual` for `normalizeForTransliteration` backed by Foundation's `NSString.precomposedStringWithCanonicalMapping`. Verified on Xcode 26.5 — Konan compiles `commonMain` for both iOS targets and the full 577-test common suite passes on the `iosSimulatorArm64` target (alongside the JVM). iOS *distribution* (SPM, [ADR-019](decisions/0019-ios-distribution-via-spm.md)) is separate and lands later in 0.2.0.
