# Open Questions

This document tracks decisions that have been deliberately deferred — to implementation time, to a future release, or to a moment when more information is available. The purpose is to ensure no deferred item is forgotten between design and implementation.

This document is living. Items are added when a decision is deferred during design. Items are removed when the decision is made and recorded in the appropriate document (a feature doc, an ADR, or scope.md). An entry that lingers without progress is itself a signal that the question may need attention.

Each entry includes a short description of the question, where it was deferred from, and what kind of resolution it requires.

---

## Deferred to Implementation Time

These questions are not answerable from design alone. They will be settled when implementation begins, often after experimentation or measurement.

### Public API exact names and signatures

The illustrative Kotlin-flavored shapes in feature documents (e.g., `MrzParser.parse(input)`, `MrzGenerator.generateTD3(...)`) describe the intended contracts but do not lock the exact class names, method names, parameter ordering, or visibility modifiers. The final shapes are decided at implementation time, recorded as feature documentation is updated.

**Source:** `mrz-parsing.md`, `mrz-generation.md`, `mrz-validation.md`, `lookup-tables.md`, `transliteration.md`

**Resolution:** Update each affected feature document with the final API shape once implementation lands.

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

**Resolution:** Confirm or adjust thresholds during implementation; document the chosen values in `mrz-validation.md`.

### Validator string-input and explicit-format overloads

`mrz-validation.md` documents `MrzValidator.validate(input: String)`, `validate(input: List<String>)`, and the corresponding overloads with an explicit `MrzFormat`. The first validator slice ships only `validate(document: MrzDocument)`. The string-input path is the standalone validation surface for consumers who want to validate previously-extracted data without re-parsing; it is not blocking the parser-internal validation path.

**Source:** First validator implementation slice; aligns with `mrz-validation.md` "Status of Implementation".

**Resolution:** Add string-input overloads in a follow-up slice. They should reuse the same per-format validators that `validate(document)` dispatches to, so a check digit failure detected by the standalone string path produces the same typed error as one detected by the parser-internal path.

### `ValidationResult.passedChecks` shape

`mrz-validation.md` describes `passedChecks` as a transparency surface — "the validators that ran and passed (exposed for transparency; consumers can confirm what was actually verified, not just what failed)." The first validator slice ships `ValidationResult` with `validationFailures` and `warnings` only. Committing to a shape for `passedChecks` (typed enum/sealed list, plain string list, or richer record) before the validator catalog is broader would be a guess about consumer needs (Principle 2).

**Source:** First validator implementation slice; aligns with `mrz-validation.md` "Status of Implementation".

**Resolution:** Decide the shape when more semantic checks land and the catalog is broader. Add `passedChecks` to `ValidationResult` with a default value to keep the addition non-breaking (Principle 9).

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

### Validator options (configurable thresholds)

`mrz-validation.md` "Date Range Conventions" commits to thresholds being "configurable through the validator's options, with the documented defaults applied when no configuration is provided." The first warning slice ships the implausibly-far threshold as a private constant in `MrzValidator` (10 years, matching `mrz-error-taxonomy.md`). Building a `ValidationOptions`-style surface now would be a guess about which other thresholds eventually need configuring (Principle 11 — internal first, promote when justified).

**Source:** First warning implementation slice; aligns with `mrz-validation.md` "Date Range Conventions".

**Resolution:** When a second configurable threshold lands (likely the date-of-birth `MAX_PLAUSIBLE_AGE_YEARS` cap, or expiry-window thresholds revisited under real-world data), introduce a `ValidationOptions` value class with named, defaulted properties and a single `MrzValidator.validate(document, referenceTime, options)` overload. Keep the defaults exactly matching the current private constants so the addition is non-breaking (Principle 9).

### TD1 validator path

The first validator slice handles only TD3. For TD1 inputs, `MrzValidator.validate(...)` returns an empty `ValidationResult` (no failures, no warnings) because TD1 has no parser yet, so there is no integration test path that would exercise a TD1 validator end-to-end. Implementing TD1's composite check digit formula without a TD1 parser to drive it would produce code that compiles and runs but is not meaningfully tested against real parsed input.

**Source:** First validator implementation slice; aligns with the TD1 data-class-only state (PR #1 slice 8).

**Resolution:** Resolved — the TD1 parser and validator landed together in the TD1 parser slice. `MrzValidator.validate(...)` dispatches `is TD1` to a real `validateTD1` (replacing the previous empty-result stub) covering per-field check digits, composite check digit, sex range, calendar-date validation, expiry warnings, birth-age warning, recognition warnings, and name truncation — all driven by integration tests through `MrzParser.parseTD1`. See `docs/features/mrz-validation.md` "Status of Implementation" for the table.

---

## Deferred to a Future Release

These questions concern functionality that is intentionally not in the current scope. They are tracked here so they are not forgotten when their release approaches.

### Lenient and tolerant parsing modes

The parser currently operates in strict mode only. Lenient mode (tolerating real-world deviations such as extra whitespace) and tolerant mode (recovering from OCR confusions using check-digit-guided disambiguation) are intentionally deferred. They are additive capabilities; the strict-only API does not constrain their later addition.

**Source:** `mrz-parsing.md` ("Strictness")

**Resolution:** Partially resolved (2026-05-29 0.2.0 pre-release review, [ADR-020](decisions/0020-camera-reading-architecture.md)). **Lenient mode ships in 0.2.0** alongside live camera — consumer-chosen, with strict remaining the default and raw values always preserved; lenient forgives benign format noise without changing any value. **Tolerant mode** (check-digit-guided OCR disambiguation) is deferred to **0.3.0** (pre-captured still-image reading, where there is no next frame to retry) and, when built, must *surface* candidate corrections rather than silently overwrite (reader-not-oracle). Live camera handles OCR noise via strict-parse-and-retry across frames.

### Sex field encoding choice (`<` vs `X`)

The generator currently encodes `Sex.UNSPECIFIED` as the filler character `<` by default. Future configuration may allow choosing `X` explicitly. The current decision is made; the configurability is deferred.

**Source:** `mrz-generation.md` ("Edge Cases Worth Calling Out")

**Resolution:** Add configuration option in a future release if consumer demand justifies it.

### Profile inheritance for transliteration

The initial transliteration system does not support profiles inheriting from each other. A "based on ICAO default with overrides" pattern is a possible future enhancement.

**Source:** `transliteration.md` ("Edge Cases Worth Calling Out")

**Resolution:** Add inheritance mechanism in a future release if multiple profiles share substantial common content.

### Multiple profiles per state

A state may have multiple transliteration conventions (different document types, different time periods). The current model represents this as multiple profiles with distinct identifiers (e.g., `XYZ-CURRENT`, `XYZ-LEGACY`). A more structured approach may be added later if needed.

**Source:** `transliteration.md` ("Edge Cases Worth Calling Out")

**Resolution:** Revisit if real-world use cases require structured per-context profile selection.

### Per-language conditionals in non-Latin transliteration

ICAO Doc 9303 Part 3 Section 6 (Annex G) defines transliteration tables for Cyrillic (§6.B) and Arabic (§6.C) scripts in addition to Latin (§6.A). Several entries in those tables carry per-language conditionals — recommendations that differ depending on which language the name is in. As examples in §6.B: `Г` transliterates to `G` except for some languages where it transliterates to `H`; the first character of certain names follows a different rule in Ukrainian than in other Cyrillic-using languages; certain Serbian-language conventions diverge from the general Cyrillic rules. Arabic §6.C has similar per-language structure.

The SDK's `TransliterationProfile` interface in `0.1.0` does not model a "primary language of the name being transliterated" parameter. The interface assumes one identifier per profile (typically a country code), which is sufficient for the Latin-only `0.1.0` coverage where Annex G's recommendations are uniform per codepoint. Extending to Cyrillic / Arabic raises the design question of how to surface language-conditional rules: an additional profile parameter, sub-profiles selected by language, a `LanguageHint` enum, or some other mechanism.

**Source:** Pre-`0.1.0`-tag conformance audit (2026-05-18, `CONFORMANCE-NOTES-2026-05-18.md` finding F12) — surfaced during Phase 2 review of Annex G when comparing the Latin-only `buildIcaoLatinMappings` against the full Annex G scope.

**Resolution:** Resolve when non-Latin script profiles ship (post-`0.1.0`, no scheduled release yet). The resolution must (a) name the mechanism the SDK uses to express language-conditional rules, (b) update the `TransliterationProfile` interface and `TransliterationProfileRegistry` if a new selection axis is required, (c) ensure the chosen mechanism is non-breaking to consumers who already use Latin-only profiles. Cross-reference from `transliteration.md` when the first non-Latin profile design begins.

### MIXED read method semantics

The `ReadMethod.MIXED` enum value represents results that combine MRZ from camera with chip data from NFC. This becomes meaningful only when both reading paths are implemented (release 0.6.0 and later).

**Source:** `mrz-data-model.md` (`ReadMethod` enum), `mrz-error-taxonomy.md` (chip/camera mismatch warning example)

**Resolution:** Define exact semantics when NFC reading is implemented and combined-result use cases are concrete.

### Image and capture metadata exposure

When pre-captured image reading and live camera reading are implemented, the SDK has access to metadata about the image or capture: timestamps (EXIF `DateTimeOriginal`, capture time), device identifiers (camera make and model), GPS coordinates if present in EXIF, indicators of editing or screenshot origin, and similar. Exposing this metadata to consumers would help them assess risks the SDK currently surfaces only as "this came from a pre-captured image, here's the risk profile."

This aligns with Principle 5 (Transparency — if we have the data, we expose it) and Principle 1 (Reader, not oracle — consumer interprets the metadata). It also has real complications: metadata can be stripped, fabricated, or simply absent; some fields are PII (notably GPS coordinates) and warrant careful handling; reliability documentation is essential or consumers may over-trust untrustworthy data.

**Source:** Design conversation about how consumers can better assess pre-captured image risk; aligns with `reading-risks.md`.

**Resolution:** Design when pre-captured image reading work begins (release 0.3.0 target). Settle which fields are exposed, how reliability is documented, how PII is handled, and whether the same approach extends to live camera capture metadata.

### Security review pass before public release

Before the 1.0.0 public release, perform a systematic security review pass. The pass should reference the "Areas for Further Analysis" section of `reading-risks.md` and confirm which theoretical concerns are real, which are moot, and which require mitigation. Items confirmed as real either get fixed or get documented in `reading-risks.md` so consumers can account for them. Items confirmed as moot get marked as such so future contributors do not re-litigate them.

The pass is most usefully scheduled after release 0.6.0 (NFC chip reading lands — significant cryptographic surface) but before 1.0.0 (the moment public stability commitments take effect).

**Source:** `reading-risks.md` ("Areas for Further Analysis"); design conversation about theoretical risk handling.

**Resolution:** Schedule and perform the review pass before tagging 1.0.0. Update `reading-risks.md` and other affected documents with the findings.

### GitHub repository topics for discoverability

The repository on GitHub has no topics set, which limits discoverability through GitHub's topic search and the homepage's topic-based recommendations. Candidate topics include `kotlin`, `kotlin-multiplatform`, `mrz`, `icao-9303`, `passport`, `identity-document`; per-release additions follow as new capabilities land (e.g., `nfc` and `emrtd` when 0.6.0 ships, `android` and `ios` when platform-I/O modules activate). The question deferred is *which* topics and *when*, not *whether* to have them.

**Source:** `SESSION-HANDOFF-2026-05-21-1348-v-0-1-0-shipped-and-protected.md` "Things to Watch For" carry-forward; reaffirmed in the 2026-05-22 session close-out conversation.

**Resolution:** Pick an initial set and apply via `gh repo edit --add-topic ...`. Establish a maintenance rhythm of reviewing topics at each release milestone — add topics as capabilities land, remove ones that no longer describe scope. Either a small follow-up PR or fold into the next release-prep pass.

### Code-review subagent (vs. the existing review skills)

A code-*review* subagent — a read-only reviewer in the `doc-consistency-reviewer` / `security-reviewer` / `qa-coverage-reviewer` family, focused on the correctness and quality of a diff — was raised as a possible fourth project agent. It is **deferred pending discussion**, not declined. The real question is whether a dedicated standing subagent earns its keep *over the code-review capability that already exists as skills* (`/code-review`, `/review`, `/simplify`, and `/security-review` for the security slice): a subagent buys an isolated context window and a fixed, project-tuned remit (the value the existing three reviewers provide), but general code review is broad and judgment-heavy — closer to `security-reviewer`'s advise-don't-dictate shape than to the mechanical doc-sync / coverage checks — so the risks are (a) overlap with the skills and (b) the review-**Q9** caution to "defer other agents (conformance, API-ergonomics, …) until a real recurring need shows — avoid the over-build trap."

**Source:** raised by the maintainer 2026-06-06 alongside the `qa-coverage-reviewer` work (action-plan C5); source-of-truth framing for "add one agent, defer the rest" is review **Q9** (`.reviews/REVIEW-2026-06-01-decisions.md`).

**Resolution:** Discuss before building. Decide (a) whether the need is real and recurring enough to justify a standing agent over invoking the existing review skills, (b) if yes, its exact remit and how it avoids duplicating `/code-review` and the other three agents, and (c) whether it stays manually-invoked, like the existing three. Until then, use the review skills for diff-level code review.

### Android target configuration on core modules

Core modules are scaffolded with the JVM target only. The Android target is intentionally deferred until 0.2.0 work begins, when the first Android-touching module (`mrz-camera-android`) is introduced and AGP needs to be added to the build anyway. Adding `androidTarget()` to the pure-logic core modules earlier would buy only theoretical insurance against Android-incompatible APIs sneaking into `commonMain`, at the cost of pulling AGP and its version-coupling constraints into the build before they earn their keep (Principle 2: the option that assumes less wins; Principle 11: don't promote infrastructure before it's justified). The Android SDK is already installed on the development machine; the deferral is by intent, not by tooling gap.

**Source:** Pre-implementation scaffolding session; aligns with Principles 2 and 11.

**Resolution:** Resolved (2026-05-29 0.2.0 pre-release review) — the Android target is enabled on the core modules per [ADR-017](decisions/0017-mobile-targets-and-build-stack.md), implemented in the 0.2.0 build-foundation slice. **The `androidTarget()` approach in this entry's original resolution is superseded:** on Kotlin 2.3.21 + AGP 9 the `androidTarget` block errors, so the target is added via Google's `com.android.kotlin.multiplatform.library` plugin instead.

### Platform I/O and UI module scaffolding

The pre-implementation checklist names `mrz-camera-{platform}`, `emrtd-nfc-{platform}`, and `mrz-camera-ui-{platform}` modules as scaffold targets. They are not scaffolded in 0.1.0 because each requires its corresponding platform toolchain (AGP for Android variants, Xcode for iOS variants) and there is no implementation in 0.1.0 that would exercise an empty-shell module. Empty platform modules add build configuration that has to be maintained without delivering any value until the corresponding feature work begins.

**Source:** Pre-implementation scaffolding session; aligns with `architecture.md` ("as appropriate" wording in the checklist) and Principle 11.

**Resolution:** Partially resolved (2026-05-29) — the **camera I/O modules (`mrz-camera-android`, `mrz-camera-ios`) are scaffolded in 0.2.0** with their first implementation, per [ADR-017](decisions/0017-mobile-targets-and-build-stack.md) and [ADR-020](decisions/0020-camera-reading-architecture.md). The remaining named modules stay on their roadmap schedule (NFC I/O `emrtd-nfc-{platform}` at 0.6.0; UI `mrz-camera-ui-{platform}` at 0.5.0). Keep this entry until those land.

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

### iOS OCR analysis-rate throttle (`analysisInterval`) as a public setting

`AVCaptureMrzScanner` analyses at most one camera frame per a fixed internal interval (`analysisInterval`, 200 ms ≈ 5 frames/sec — the "don't OCR every frame" practice). Apple Vision is far heavier than is useful for a document held still in front of the lens, so capping the analysis rate bounds the memory sawtooth and saves battery without perceptibly delaying detection. The interval is currently a **private** constant, not a constructor parameter — consumers cannot tune it. Whether to expose it — and at what name / type / default, and whether symmetrically on Android's `CameraXMrzScanner` (which has no throttle because ML Kit keeps up at frame rate) — is deferred.

The decision **not** to expose it now is deliberate (made with the user, 2026-05-31): per Principle 2 (the option that assumes less wins) and Principle 11 (don't promote before justified), and because [ADR-007](decisions/0007-strict-backward-compat-from-0x.md) locks any public API from `0.x`, exposing a knob nobody has asked for would be a speculative, permanent commitment. The throttle itself is kept — only its *public exposure* is deferred. Note the throttle does **not** affect a live camera preview a consumer draws: the preview is fed by the camera at full frame rate, independent of the (lower) analysis rate — so the on-screen video is smooth regardless.

**Status:** Open — deferred; keep `analysisInterval` private for now. Headless device verification (2026-05-31) showed smooth analysis at ~5 fps with bounded memory and live decodes; no consumer need has surfaced.

**Source:** 2026-05-31 iOS camera-stall fix session; decision made with the user.

**Trigger:** A real camera **UI / preview** is built (a sample app or a consumer integration) and the analysis rate is observed to actually matter — detection feels too slow, or a consumer asks to tune the battery / responsiveness trade-off. Revisit the name, type, default, and Android symmetry then.

### Camera MRZ-candidate detection vs real OCR output

The analyse-frame core locates an MRZ by finding a run of consecutive recognized lines whose count and length exactly match a known ICAO shape (TD3/MRV-A 2×44, TD1 3×30, etc.). The Android live-device slice showed this exact-shape match is brittle against the bundled ML Kit general-Latin recognizer reading a screen-rendered MRZ: line 1's long `<` filler run is **collapsed** (ML Kit does not emit long runs of identical `<`, and reads `<<` as `«`), so the line never reaches its nominal length and no candidate is found — while *any* two consecutive equal-length lines of surrounding prose can spuriously match. Line 2 (few fillers) read at exact length reliably. No clean `Decoded` → `Success` was achieved on the screen/monospace target; the parser correctly *rejected* the garbage reads (`MrzCharacterSetViolation` on the `«`), which is reader-not-oracle working as intended. Caveat: a screen-rendered generic-monospace MRZ is a weak proxy — real OCR-B on paper, or a region-cropped frame, may behave differently. The finding suggests the deferred refinements — an image-level ROI crop to the MRZ band before OCR, and/or a length-tolerant / sliding-window candidate matcher — are likely *necessary* for the Android convenience layer to actually decode, not merely nice-to-have. Relates to the tolerant-mode work deferred to 0.3.0 (see "Lenient and tolerant parsing modes" above).

**Status:** Open — exact-shape matching is insufficient against real ML Kit output; an ROI crop and/or a length-tolerant matcher is the likely resolution, designed with the tolerant-mode work.

**Source:** 2026-05-30 Android live-device slice.

**Trigger:** When the Android convenience layer needs to reliably decode — the 0.3.0 still-image / tolerant-mode work, or earlier if device testing against a printed OCR-B sample or a real document is pursued.

---

## Deferred to a Future Document

These items are referenced from existing documents but their full content lives in documents not yet written.

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

**Resolution:** Resolved — fifteen ADRs exist in `docs/decisions/` as of the `0.1.0` tag. See [`docs/decisions/README.md`](decisions/README.md) for the current index. Additional ADRs may be added in the future as new significant decisions are made.

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

These questions cannot be settled by us alone — they depend on documentation, decisions, or developments outside the project.

### External spec data licensing strategy

ICAO Doc 9303 (Machine Readable Travel Documents) is freely downloadable as PDFs from `icao.int` in six languages — see the [Doc 9303 page](https://www.icao.int/publications/doc-series/doc-9303). All 13 parts are accessible without payment or registration. The technical content the SDK needs is in:

- **Part 3** — common specs: document type codes (Section 4), country codes (Section 5), transliteration tables (Section 6 / Annex G), MRZ alphabet, check digit algorithm
- **Part 4** — TD3 (passports), including the canonical sex character set (§4.1)
- **Parts 5–7** — TD1, TD2, MRV-A, MRV-B specifics

**Reading the spec is unambiguously fine.** The SDK's algorithms (check digit, alphabet, format layouts) were implemented from the spec's technical descriptions — algorithms are not copyrightable.

**Embedding the spec's data tables verbatim is the open question.** ICAO's stated copyright position (per the site copyright notice) is restrictive: *"None of the materials provided on this web site may be used, reproduced or transmitted, in whole or in part, in any form or by any means... without permission in writing from ICAO."* Whether technical tables qualify as facts (uncopyrightable in many jurisdictions) versus creative compilations (copyrightable) is a legal question the project has not yet resolved. The conservative path for an Apache-2.0 open-source project is to avoid verbatim ICAO content until the question is settled.

This was the umbrella entry for four related downstream questions on which it bore directly:

- **Sex value canonical set per ICAO Doc 9303** — resolved by reading Part 4 §4.2.2.2 during the pre-tag audit; see the entry below.
- **Document type code table completeness** — populated from Part 4 §4.4 (the harmonized P-prefix set) and Part 5 Appendix B (the `AC` Crew Member Certificate code); see the entry below.
- **Country code table completeness** — populated from the published ISO 3166-1 alpha-3 list and ICAO Doc 9303 Part 3 §5 extensions; see the entry below.
- **Transliteration profile coverage completeness** — Latin section (Annex G §6.A) populated to full coverage; non-Latin scripts (Cyrillic, Greek, Arabic) remain deferred to a future release; see the entry below.

**Source:** Pre-`0.1.0`-tag recap (2026-05-18) — the audit established that the spec is accessible and corrected a prior framing in this document ("no authoritative copy on hand") that was incorrect.

**Resolution:** The project's operative posture from the pre-tag conformance pass is **cite-and-implement**: read Doc 9303, implement the technical content based on our understanding, cite section numbers in code KDoc and feature docs. This is path (4) above — facts-not-copyrightable, applied to the specific technical tables shipped in `0.1.0` (ISO 3166-1 alpha-3 list, the §5 extensions, the harmonized document codes, and the Annex G Latin transliteration table). The umbrella's original "stay deferred" framing was over-cautious; it served its purpose by forcing the conversation that produced the cite-and-implement posture.

What remains for `1.0.0`:

1. **Legal review of the cite-and-implement posture before public release.** The specific scope: confirm that the technical data shipped in `0.1.0` (and any further bulk additions from Doc 9303 before `1.0.0`) is defensible under the project's Apache-2.0 license terms. Path (1) — requesting ICAO permission — remains available if review surfaces a concern.
2. **Non-Latin transliteration coverage.** Cyrillic, Greek, and Arabic tables (Annex G §6.B, §6.C, §6.D) are not in `0.1.0`. When added, the same cite-and-implement posture applies. See the "Transliteration profile coverage completeness" entry below for the related design question on per-language conditionals.

Paths (2) — alternative sources — and (3) — defer to consumer-provided data — are no longer in active consideration for the spec-derived data the SDK ships, though they remain options for future tables where licensing concerns become acute.

Each downstream entry below records its own resolution against this posture.

### Specific document type implementations

Some document types are in scope but their specific format details require documentation that may not be currently public. The architecture supports them; implementation is added when documentation becomes available.

**Source:** `scope.md` ("Specific Document Implementations")

**Resolution:** Implement each as documentation becomes available.

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

### Transliteration profile coverage completeness

The transliteration profiles that ship in `mrz-core` (`IcaoDefaultTransliterationProfile` and `AzeTransliterationProfile`) draw their Latin-script mappings from a shared internal helper (`buildIcaoLatinMappings()`). The Latin portion of ICAO Doc 9303 Part 3 §6.A (Annex G) is now covered in full as of the pre-`0.1.0`-tag conformance audit (2026-05-18, `CONFORMANCE-NOTES-2026-05-18.md` finding F5, with the F2 schwa correction and F13 codepoint-disambiguation cross-check). Non-Latin scripts (Cyrillic §6.B, Arabic §6.C, and the Greek table) are not yet implemented.

Per-profile overrides on top of the shared table evolved in two passes. **At 2026-05-18 (PR #43):** `AzeTransliterationProfile` overrode only the schwa pair (the load-bearing divergence ADR-009 originally called out), inheriting Annex G no-expansion for everything else. **At 2026-05-19 (pre-tag empirical pass):** AZE practice was verified against sample documents + fluent-speaker testimony + the [ALA-LC romanization table](https://www.loc.gov/catdir/cpso/romanization/azerbaij.pdf), which revealed a systematic phonetic Anglicization pattern. `AzeTransliterationProfile` now ships 8 overrides (`Ə/ə → A`, `Ç/ç → CH`, `Ğ/ğ → GH`, `Ş/ş → SH`, `X/x → KH`, `C/c → J`, `J/j → ZH`, `Q/q → G`). The four overrides on letters already in the MRZ alphabet (`C`, `J`, `Q`, `X`) required the profile to consult its override map before the MRZ-alphabet passthrough check — see ADR-009 "Implementation Note: Override Lookup Order".

Both profiles' fallback policy is to map any unmapped character to the filler `<`, so partial coverage of non-Latin scripts is safe: a consumer transliterating a Cyrillic or Arabic name through the current profiles gets filler output rather than a runtime failure. Adding entries to the underlying tables (or new per-script profiles) is a non-breaking change provided the existing mappings stay stable.

**Source:** First implementation slice for `TransliterationProfile` (2026-05-17 session); aligns with `docs/features/transliteration.md` ("The ICAO Default Profile", "Country-Specific Profiles") and ADR-009.

**Resolution:** Latin section resolved (PR-4 of the conformance pass). Non-Latin scripts remain deferred to a post-`0.1.0` release. When they are added, three resolution points come up:

1. **Per-language conditionals.** §6.B and §6.C use per-language exceptions that the `0.1.0` profile interface does not model — see the new "Per-language conditionals in non-Latin transliteration" entry under "Deferred to a Future Release" above.
2. **Profile structure.** Decide whether non-Latin tables belong inside the existing default profile (treating Cyrillic / Arabic as universal) or in separate per-script profiles selected explicitly by the consumer (parallel to country-specific overrides).
3. **AZE `Ö` / `Ü` empirical verification.** The current no-expansion inheritance is recorded as a working choice pending real-document evidence; see the entry below.

Country-specific profile expansions for additional issuing states ship per consumer demand.

### No publicly-findable regulation on MRZ transliteration for the issuing state coded AZE

Per the 2026-05-18 conformance audit's Phase 4 research, no specific regulation defining MRZ name transliteration for the issuing state coded `AZE` was located in publicly-searchable sources (searches against the state's publicly-available legal information system, cabinet-level resolutions, migration-service references, BGN/PCGN, and ECHR case-law sources). The original `AzeTransliterationProfile` (single schwa override, `Ə/ə → A`) was justified via the BGN/PCGN + ICAO chain plus observed practice.

The 2026-05-19 pre-tag pass extended the profile to 8 systematic overrides covering AZE's phonetic Anglicization pattern (see [ADR-009](decisions/0009-transliteration-profiles.md) for the full reframe). The citable basis is now stronger than at conformance time:

- **Primary source: ALA-LC romanization table for the AZE Latin alphabet** (US Library of Congress / British Library standard). ALA-LC produces `ch`, `gh`, `kh`, `sh`, `ġ` (for Q), `ă` (for Ə), `ı̐` (for I), `i` (for İ), `ȯ` (for Ö), `u̇` (for Ü). When the MRZ alphabet strips ALA-LC's diacritics to ASCII, the result matches every observed AZE encoding (for the letters ALA-LC covers).
- **Secondary source: empirical sample documents** (passport + 2 ID cards) verified the rules for `Ç`, `Ğ`, `İ`, `I`, `Ə` directly.
- **Tertiary source: fluent speaker's testimony with worked examples** verified `X → KH`, `Ş → SH`, `Q → G`, `J → ZH`, `C → J`.

A specific government regulation is still not in publicly-searchable form. Two possibilities remain equally consistent with the search outcome: (a) no specific regulation exists and the issuing authority follows ICAO + a local convention captured by ALA-LC; (b) a regulation exists but is not publicly accessible. The project's posture no longer depends on resolving this — the profile rests on ALA-LC plus the corroborating evidence.

**Source:** Pre-`0.1.0`-tag conformance audit (2026-05-18, finding F23); 2026-05-19 empirical update.

**Resolution:** Partially resolved. The substantive question (what does AZE encode in the MRZ?) is answered by the ALA-LC chain + corroborating evidence. The narrower question (is there a citable national regulation?) is open; revisit if such a regulation surfaces and update `AzeTransliterationProfile` KDoc + [ADR-009](decisions/0009-transliteration-profiles.md) accordingly. No `0.1.0` action required.

### AZE profile `Ö` / `Ü` empirical verification

ICAO Annex G recommends multiple permitted transliterations for two of the letters in the Roman alphabet of the issuing state coded `AZE`:

- `Ö ö` → `OE` or `O` (state picks)
- `Ü ü` → `UE` or `UXX` or `U` (state picks)

The other AZE-relevant Latin letters (`Ç`, `Ğ`, `İ`, `ı`, `Ş`) had unambiguous Annex G recommendations under no-expansion at conformance time. `AzeTransliterationProfile` originally inherited the `IcaoDefaultTransliterationProfile`'s no-expansion choices (`Ö → O`, `Ü → U`) parsimoniously, given the absence of evidence to the contrary.

**Source:** Pre-`0.1.0`-tag conformance audit (2026-05-18, `CONFORMANCE-NOTES-2026-05-18.md` finding F25) — Phase 4 AZE-profile law research outcome.

**Resolution:** Resolved (2026-05-19). A fluent speaker's confirmation grounded in observed practice and the [ALA-LC romanization table](https://www.loc.gov/catdir/cpso/romanization/azerbaij.pdf) (which gives `Ö → ȯ` and `Ü → u̇`, both single-character with diacritic — stripping to `O` and `U` under MRZ ASCII) converge on the no-expansion form. The AZE profile inherits `Ö → O` and `Ü → U` from `IcaoDefaultTransliterationProfile` unchanged. (The broader empirical pass that resolved this also surfaced 7 other AZE overrides — see the "Transliteration profile coverage completeness" entry above for the full set.)

### AZE profile `J → ZH` and `C → J` empirical basis

Of the 8 overrides in `AzeTransliterationProfile`, five (`Ç → CH`, `Ğ → GH`, `Ş → SH`, `X → KH`, `Q → G`) are derivable from the ALA-LC romanization table (Library of Congress standard) and corroborated by either sample documents or worked examples. Two — `J → ZH` and `C → J` — are not in ALA-LC's explicit table (ALA-LC treats both as plain Latin letters that pass through). They were added based on:

- A fluent speaker's testimony with worked examples (`Jalə → ZHALA`, `Cəlal → JALAL`)
- The phonetic Anglicization principle that explains the other 6 overrides (AZE J is /ʒ/ = English "zh"; AZE C is /dʒ/ = English "j")
- Internal consistency: in the AZE profile, every letter whose source phonetic value diverges from the corresponding English letter is overridden, so leaving J and C alone would be inconsistent with the systematic pattern

The two overrides remain the empirically weakest links in the profile. If a future sample passport contains either letter in the name field and shows passthrough (`J → J`, `C → C`) instead, the overrides should be removed.

**Source:** 2026-05-19 pre-tag empirical pass.

**Resolution:** Confirm against a real sample document containing `J` or `C` in the name field when available. Either confirm current behavior (no change) or remove the override and update `AzeTransliterationProfile` KDoc + [ADR-009](decisions/0009-transliteration-profiles.md). Tracking so the gap is not forgotten.

### Driver's license format choice (mDoc vs proprietary)

When driver's license NFC reading is added in a future release, the choice between standard mDoc-compliant licenses (ISO 18013-5) and proprietary national formats depends on which markets the project prioritizes.

**Source:** `scope.md` ("Beyond 1.0")

**Resolution:** Decide when driver's license NFC work begins, based on consumer needs at that time.

### Trust anchor source for chip signature verification

Cryptographic verification of NFC chip signatures requires trust anchors (typically Country Signing Certificate Authority certificates, distributed via the ICAO Public Key Directory or similar). The choice of trust anchor source is its own design problem, deferred until chip signature verification is on the active roadmap.

**Source:** `scope.md` ("Beyond 1.0")

**Resolution:** Design when chip signature verification is added.

### Distribution channels (Maven Central, CocoaPods, SPM)

**JVM coordinate shape, lockstep versioning, BOM, first-publish version, and first-publish scope are resolved by [ADR-016](decisions/0016-maven-coordinates-and-first-publish.md).** The published groupId is `io.lightine.tessera` (backed by the verified Sonatype namespace at `io.lightine`); artifactIds follow the `tessera-<module>` convention; modules version in lockstep with a `tessera-bom` artifact for version alignment; the first Maven Central publication shipped at 0.1.1 (published 2026-05-29) with all five current modules plus the BOM; no snapshot builds at 0.x.

What remained open under this entry — the iOS distribution channel (CocoaPods vs Swift Package Manager) — is now resolved (see Resolution). The only distribution question still future is the **web (JS/Wasm) channel (npm)**, decided when/if the web target activates.

**Source:** Implicit; not yet referenced.

**Resolution:** JVM distribution resolved by [ADR-016](decisions/0016-maven-coordinates-and-first-publish.md) and **executed** — `io.lightine.tessera:*:0.1.1` was published to Maven Central on 2026-05-29 (publishing slices in PRs [#88](https://github.com/lightine-io/tessera/pull/88)–[#90](https://github.com/lightine-io/tessera/pull/90)). iOS distribution is resolved (2026-05-29) — **Swift Package Manager** (Kotlin/Native XCFramework wrapped as a Swift package; CocoaPods rejected as legacy) per [ADR-019](decisions/0019-ios-distribution-via-spm.md), within a **one-channel-per-ecosystem** model (Maven Central for JVM/Android/desktop, SPM for iOS, npm for web when that target activates); execution lands in the 0.2.0 iOS slice. The iOS **packaging mechanics are now built** (the `mrz-camera-ios` SPM slice): the `Tessera` XCFramework assembly + the `packTesseraXCFramework` zip task are wired and CI-verified, and the open `Package.swift`-location sub-decision is resolved to a **dedicated distribution repo** (see [ADR-019 execution notes](decisions/0019-ios-distribution-via-spm.md#execution-notes-020-ios-slice)). The remaining iOS publication step — create the distribution repo, attach the zip to the GitHub release, finalize `Package.swift` — lands with the 0.2.0 release cut.

### Swift `Flow` / coroutines ergonomics for the SPM consumer

The `Tessera` XCFramework (ADR-019) exposes `MrzCameraScanner.results` as a Kotlin `Flow<MrzScanResult>` and the `suspend` analyse/recognize functions through Kotlin/Native's default Objective-C/Swift export. That export is functional but not idiomatic Swift: a `Flow` surfaces as a Kotlin handle a Swift caller collects through the generated coroutines bridge, not as a native `AsyncSequence`/`async` API. A nicer Swift experience — e.g. adopting [SKIE](https://skie.touchlab.co/) (which maps `Flow` to `AsyncSequence` and `suspend` to Swift `async`), or hand-writing a thin callback/`AsyncStream` adapter — is a candidate refinement.

**Source:** Surfaced in the `mrz-camera-ios` SPM packaging slice (the XCFramework header review).

**Status:** Partially resolved — adapter still deferred. The **freeze question this entry raised is decided (2026-06-04, pre-0.2.0 review)**: the Objective-C/Swift *projection* of the camera API (the `Flow`/`suspend` export) is **explicitly marked provisional through `0.x`** — **not** locked at the `0.2.0` tag — recorded in the `MrzCameraScanner` KDoc. So a later idiomatic-Swift surface (a hand-written `AsyncStream`/callback adapter, or [SKIE](https://skie.touchlab.co/)) remains a **legal, non-breaking change** under [ADR-007](decisions/0007-strict-backward-compat-from-0x.md) for the whole `0.x` line, rather than a 1.0-only change. **Still deferred:** building that adapter — not a blocker for first iOS distribution (the API is usable from Swift as-is); revisit when there is a real Swift consumer, or as a 0.3.0+ polish item, weighing SKIE's value against adding a Gradle-plugin dependency to the build (ADR-020's "Swift-friendly headless API" consideration).

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

### Local regulatory considerations for the project's author

The project's author is in a jurisdiction whose regulatory frameworks for open source software, contributor patent grants, government data handling, and conflict-of-interest rules differ from US/EU contexts and are evolving. Specific legal review may be warranted before public release, particularly if the SDK is later deployed in any government context.

**Source:** Design conversation about author location and local context.

**Resolution:** Consult applicable local legal guidance before public release. Apache 2.0 with explicit patent grant (ADR-010) is a defensive choice that helps where local frameworks are less codified, but does not substitute for legal review.

### iOS target configuration on core modules

Core modules (`mrz-core`, `emrtd-core`, `types`, `telemetry`, `logging`) are scaffolded with the JVM target only. Configuring the iOS targets (`iosX64`, `iosArm64`, `iosSimulatorArm64`) requires Xcode, which is not installed on the development machine where scaffolding was performed. There is no design decision to make — the targets are committed in `architecture.md` and ADR-002. The deferral is purely about toolchain availability.

**Source:** Pre-implementation scaffolding session; depends on Xcode install.

**Resolution:** Resolved (2026-05-29 0.2.0 pre-release review) — **Xcode is now present** (26.5 on the development machine), lifting the toolchain gate noted above ("not installed" is no longer true). The three iOS targets are enabled on the core modules per [ADR-017](decisions/0017-mobile-targets-and-build-stack.md), with the Normalization `expect`/`actual` ([ADR-014](decisions/0014-unicode-normalization-strategy.md)) gaining an iOS `actual`; the committed iOS deployment minimum is **18** ([ADR-018](decisions/0018-platform-minimums-and-managed-raise.md)), not the 15.0 this entry's original text referenced. **Executed (2026-05-30)** in the 0.2.0 iOS build-foundation slice: `iosArm64`/`iosSimulatorArm64`/`iosX64` declared on all five modules, the iOS `actual` for `normalizeForTransliteration` backed by Foundation's `NSString.precomposedStringWithCanonicalMapping`. Verified on Xcode 26.5 — Konan compiles `commonMain` for all three iOS targets and the full 577-test common suite passes on the `iosSimulatorArm64` target (alongside the JVM). iOS *distribution* (SPM, [ADR-019](decisions/0019-ios-distribution-via-spm.md)) is separate and lands later in 0.2.0.

---

## Future Project Toolkit

This is not a project-specific item but is recorded here so it is not lost. It can be moved to a separate document later if it grows.

### Reusable patterns and document templates for future projects

The patterns established during this project's design — dispute-driven discussion, principles-first design, the specific document templates (ADR format, feature doc structure, etc.) — are not specific to this SDK. They could be useful for future projects undertaken by the same author. Extracting them into a reusable toolkit (outside this project's repository, in a personal `~/code/.shared-context/` or similar) would let future projects start with the same disciplines without re-deriving them.

**Source:** Design conversation about future-project considerations.

**Resolution:** Extract patterns to a separate personal toolkit at a future point. Not blocking this project's progress; tracked here so it does not get lost.

---

## Future Improvements to Consider

These are not deferred decisions and not blocking. They are improvements to the project's documentation system or process that may be worth making *if certain conditions arise*. Each entry includes the trigger that would justify revisiting.

The list is intentionally short. Premature improvements are noise; trigger-based items get acted on when relevant rather than gathering dust.

### Document Evolution section in conventions.md

Add explicit guidance on how documents evolve: when feature docs get rewritten vs. extended, when sections move to their own docs, when ADRs get superseded vs. updated, how to handle obsolete content.

**Trigger:** When documentation patterns start drifting noticeably between docs, or when a contributor asks "how should this document change?"

### Lessons-learned log

Add a LESSONS.md or RETROSPECTIVE.md capturing what went well and what didn't, after each release or milestone. Useful for long-term project health.

**Trigger:** After the first internal release (0.1.0) ships, when there is actually a milestone to retrospect on.

### Code precedent examples

Once implementation has produced idiomatic code in the project, consider whether to extract small example snippets into the documentation as "this is what a parser implementation in this project looks like." Not pre-written — emerges from real first implementations.

**Trigger:** After 0.1.0 lands, if Claude Code consistently produces non-idiomatic code that requires correction.

### Runnable camera sample app

A small runnable sample app (Android first, iOS later) that integrates the headless camera reader end-to-end — points the camera at a synthetic MRZ and prints the parsed result. It would double as the on-device test harness for the 0.2.0 camera work *and* as living integration documentation (ties to "Code precedent examples" above). 0.2.0 ships written integration docs (snippets + a standalone guide) instead; a runnable sample is deferred to keep 0.2.0 scoped to the headless SDK plus docs.

**Source:** 2026-05-29 0.2.0 pre-release review ([ADR-020](decisions/0020-camera-reading-architecture.md)); deferred from PR-F (consumer integration docs).

**Trigger:** When the headless camera reader is stable on a platform and a runnable demo would add more than the written snippets + integration guide already provide — likely late in 0.2.0 or alongside the 0.5.0 UI.

### CI and repository hardening for the mobile build

Non-blocking hardening items surfaced by the security reviews of the 0.2.0 build-foundation slices (which enabled the Android then iOS targets on the core modules, [ADR-017](decisions/0017-mobile-targets-and-build-stack.md)):

1. **CI does not compile the mobile targets.** The `check` workflow runs `./gradlew check` on an `ubuntu-latest` runner, which compiles and tests only the JVM target. Neither mobile target is covered. The **Android** target compiles via `assemble`/`build` (not `check`) and the runner has no Android SDK provisioned — Linux *can* compile it once the SDK is present. The **iOS** targets compile via Kotlin/Native, which requires a **macOS** runner with Xcode; a Linux runner cannot build Apple targets at all. So a `commonMain` change that breaks Android or iOS compilation — or a future `androidMain`/`iosMain` source file that does not compile — would pass CI and surface only on a developer machine. At the build-foundation slices the exposure is minimal (the only platform-specific sources are the two one-line Normalization `actual`s, both verified locally — Android on the JVM-identical `java.text.Normalizer`, iOS on the simulator across the full 577-test suite), but it widens as the camera slices add real `androidMain`/`iosMain` code. Closing the Android side means a CI job that provisions the Android SDK and runs `./gradlew compileAndroidMain` (compile-only, no emulator); closing the iOS side means a `macos-latest` job with Xcode that runs the Konan compile (and ideally the `iosSimulatorArm64` tests) — both kept separate from the fast JVM `check`.
2. **`google()` repository has no content filter.** `settings.gradle.kts` adds Google's Maven repository without a `content { includeGroupByRegex(...) }` filter, so Gradle may consult it for any group, not just the Google-owned ones (`com.android.*` / `com.google.*` / `androidx.*`). With a locked version catalog and `FAIL_ON_PROJECT_REPOS` already set, the risk is low; the best-practice hardening is to scope each `google()` declaration to those groups.

**Source:** 2026-05-30 build-foundation-slice security reviews (`security-reviewer` subagent), Android then iOS; all items rated low / info.

**Update (2026-05-30, `mrz-camera-android` analyse-frame slice):** the **Android side of item 1 and item 2 are done.** `settings.gradle.kts` now content-filters both `google()` declarations to `com.android.*` / `com.google.*` / `androidx.*` (item 2 closed). A new `android-compile` CI job (`.github/workflows/check.yml`, `ubuntu-latest` + the runner's Android SDK) runs `./gradlew compileAndroidMain` across every module — so the camera module's ML Kit `androidMain`, and the core modules' Android targets, are now type-checked on CI (item 1, Android side, closed). **Still open:** the **macOS iOS-compile job** (item 1, iOS side), which lands with the first real `iosMain` code (`mrz-camera-ios`).

**Update (2026-05-30, `mrz-camera-ios` slice):** **item 1 is now fully closed.** A new `ios-compile` CI job (`.github/workflows/check.yml`, `macos-latest` + the runner's Xcode, with `~/.konan` cached) runs `./gradlew iosSimulatorArm64Test compileKotlinIosArm64 compileKotlinIosX64` — so the core modules' iOS targets and the new `mrz-camera-ios` `iosMain` (AVFoundation/Vision) are compiled on CI, and the common host tests run on the Simulator. With both the Android and iOS sides covered, a `commonMain`/`androidMain`/`iosMain` compile break can no longer pass CI. **Item 2 was closed earlier; only item 3 (SHA-pinning GitHub Actions) remains** as a deferred, non-mobile chore.

3. **GitHub Actions pinned by floating tag, not commit SHA** (repo-wide, all workflows — surfaced in the camera slice's CI review). `actions/checkout`, `actions/setup-java`, `gradle/actions/setup-gradle`, and `actions/cache` (the last added by the `ios-compile` job) are referenced by major-version tag (`@v6` etc.), which is mutable. All are first-party (GitHub / Gradle Foundation) so the risk is low, but pinning to a commit SHA with a version comment is best-practice supply-chain hardening. Deferred as a separate, non-mobile chore (would touch every workflow at once; Dependabot `actions` updates can automate the SHA bumps if enabled).

**Trigger:** Items 1 and 2 are done (Android `android-compile` + iOS `ios-compile` CI jobs; both `google()` declarations content-filtered). The remaining **item 3** (SHA-pin GitHub Actions) is a deferred repo-wide chore — act on it before the first public push, or let Dependabot `actions` updates automate the SHA bumps once enabled.

**Update (2026-05-30, 0.2.0 pre-tag cross-platform security audit):** the audit (`security-reviewer`, two passes — camera code + supply-chain/publishing/repo) found **no high-severity or code-vulnerability issues** across the 0.2.0 camera surface; the actionable findings were cheap doc/contract hardening, applied directly (PII "do-not-log-verbatim" KDoc on `MrzScanResult`/`ParseResult`; a single-thread lifecycle contract on `MrzCameraScanner`; a RENDEZVOUS-invariant comment on `CameraXMrzScanner`). It also surfaced three further **deferred, non-blocking** mechanical-hardening items, tracked here alongside item 3:

4. **Gradle wrapper has no `distributionSha256Sum`.** `gradle-wrapper.properties` sets `validateDistributionUrl=true` (URL-pattern check) but does not pin the distribution zip's SHA-256, so a CDN/DNS-compromise at download time would not be caught. Add `distributionSha256Sum=<sha>` (from `services.gradle.org/distributions/gradle-<v>-bin.zip.sha256`) at the next wrapper bump (cadence: 2026-10-01).
5. **No dependency CVE / license scan in CI.** Dependabot handles declared-dependency bumps, but no CI step enumerates the transitive graph for CVEs or license conflicts (e.g. the ML Kit `transport-backend-cct` transitive stack noted in [`reading-risks.md`](reading-risks.md)). Candidates: GitHub's `dependency-review-action` (PR-scoped, low-noise) or OWASP Dependency-Check. Most valuable before the 1.0.0 public release; useful to baseline at 0.2.0.
6. **No mechanical guard that ML Kit stays the bundled model.** A future `mlkit-text-recognition` version could switch to a Play-Services delivery model; a Gradle/CI assertion that `text-recognition` (bundled) — not `text-recognition-default` (Play Services) — is on the graph would catch that. Belt-and-suspenders; the pinned catalog version already constrains it.

On **item 3**: the repository is already public, so the "before first public push" trigger has effectively passed — item 3 (SHA-pin Actions) is now *due* rather than future, though it remains a separate repo-wide chore (best done in one pass across all workflows, or via Dependabot SHA bumps). Items 4–6 are not 0.2.0-tag blockers. The SPM artifact-authenticity item (checksum binds integrity not authenticity) is tracked separately in [ADR-019's execution notes](decisions/0019-ios-distribution-via-spm.md#execution-notes-020-ios-slice) as a release-runbook decision.

**Update (2026-06-05, action-plan A4 / Q6): item 5 CVE-scan resolved (first-party pattern).** Added two CI workflows, both first-party (matching `check.yml`'s no-third-party-on-the-supply-chain stance): `dependency-submission.yml` (`gradle/actions/dependency-submission`) submits the **full resolved transitive Gradle graph** to GitHub on push-to-main and on PRs — closing the gap that GitHub cannot resolve a Gradle graph natively, so Dependabot alerts (already enabled) now see the whole tree; and `dependency-review.yml` (`actions/dependency-review-action`) is a **blocking PR gate** that fails a PR introducing a `moderate`-or-higher CVE. The chosen pattern is the GitHub/Gradle-native one over OWASP Dependency-Check / OSV-Scanner specifically to avoid adding a third-party scanner to the build. **License *gating* is deliberately deferred** (not part of "resolved"): an incomplete `allow-licenses` list would fail legitimate Apache-2.0/MIT deps, so licenses are *surfaced* in the PR summary now and a curated allow-list can tighten the gate once the transitive license set is enumerated. **Items 3 and 6 follow in their own changes** (SHA-pinning Actions; the ML-Kit bundled-model assertion); item 4 stays on the 2026-10-01 wrapper-bump cadence.

**Update (2026-06-05, action-plan A4): item 6 resolved — ML-Kit bundled-model guard, with a corrected discriminator.** A `verifyMlKitBundledModel` Gradle task in `mrz-camera-android` (run by the `android-compile` CI job and locally) fails the build if ML Kit is no longer the bundled model. **The discriminator in this item's original text was wrong:** it is *not* the absence of a `text-recognition-default` / Play-Services artifact. The bundled artifact `com.google.mlkit:text-recognition` **transitively depends on** `com.google.android.gms:play-services-mlkit-text-recognition` (the recognizer API), so that coordinate is on the graph in **both** the bundled and unbundled flavors — a guard keyed on its absence would always fail. The true marker is the bundled *model* component **`com.google.mlkit:text-recognition-bundled-common`**, which is on `androidRuntimeClasspath` only when the model is linked into the app; the task asserts its presence. Verified both ways before committing: passes as-is, and fails (with the intended message) when the dependency is temporarily swapped to the unbundled `play-services-mlkit-text-recognition`. (Caught by resolving the actual dependency graph rather than trusting the item's framing — the consult-vendor-docs / verify-against-reality habit.)

**Update (2026-06-05, action-plan A4): item 3 resolved — GitHub Actions SHA-pinned.** Every action across all three workflows (`check.yml`, `dependency-submission.yml`, `dependency-review.yml`) is now pinned to a full commit SHA with a trailing `# vX.Y.Z` comment — `actions/checkout` (v6.0.3), `actions/setup-java` (v5.2.0), `actions/cache` (v5.0.5), `gradle/actions/setup-gradle` + `gradle/actions/dependency-submission` (v6.1.0), `actions/dependency-review-action` (v5.0.0) — closing the mutable-tag exposure (a `@v6` tag can be repointed). Dependabot's already-enabled `github-actions` updates bump both the SHA and the version comment, so this stays current without manual churn. A header note in `check.yml` records the convention so new actions are pinned the same way. **With this, items 1–3 and 5–6 of the entry are resolved; only item 4 (wrapper `distributionSha256Sum`) remains, parked on the 2026-10-01 wrapper-bump cadence.** Separately, repository **secret scanning + push protection were enabled** (2026-06-05) — the mechanical secret-detection half the security-reviewer resolution paired with the CVE scan.

### CONTRIBUTING.md at project root

GitHub recognizes a top-level `CONTRIBUTING.md` and surfaces it on PR creation. The current `docs/conventions.md` covers what a CONTRIBUTING.md would cover. A small top-level file pointing to conventions.md may be useful when the project goes public on GitHub.

**Trigger:** Before the first public push to GitHub or equivalent.

**Resolution:** Resolved (2026-05-20). Added a short [`CONTRIBUTING.md`](../CONTRIBUTING.md) at the project root pointing to `docs/conventions.md`, `.claude/git-workflow.md`, `docs/versioning.md`, `docs/testing.md`, `docs/principles.md`, `docs/open-questions.md`, the PR template, and `SECURITY.md`. The file is intentionally short — it does not duplicate the full conventions, just makes them discoverable from GitHub's contributor flow. Landed alongside `SECURITY.md`, `.github/CODEOWNERS`, `.github/dependabot.yml`, and `.github/workflows/check.yml` in the pre-public-readiness pass.

### CHANGELOG.md initial entry

The project commits to Keep a Changelog format (see `docs/versioning.md`). The actual `CHANGELOG.md` file does not yet exist. It will be created with the first internal release entry.

**Trigger:** Before tagging 0.1.0.

**Resolution:** Resolved — `CHANGELOG.md` exists at project root in Keep a Changelog format. The initial `[0.1.0]` entry is populated by the tag commit; the `[Unreleased]` section above it accumulates entries for the next release per the conventions in `docs/versioning.md`.

### Tessera-specific security-reviewer subagent

The Claude Code optimization audit in PR [#66](https://github.com/lightine-io/tessera/pull/66) identified a security-reviewer subagent as a candidate AI-tooling addition. Deferred because at `0.1.0` (pure parsing/validation/generation) the security surface is narrow — limited to PII handling in logs/error messages, input validation on MRZ parsers, dependency hygiene, and avoiding hardcoded real-looking document data in tests. The built-in `security-review` skill that ships with Claude Code is generic-but-sufficient for this surface. Designing a Tessera-specific subagent now would produce a thin prompt-only artifact without enough code to ground its guidance against. The real security surface arrives in `0.2.0` (camera + image processing), `0.5.0` (BAC), `0.6.0` (PACE/NFC crypto), at which point a domain-aware subagent has actual patterns to enforce.

**Trigger:** During the Pre-Release Tech-Stack Review for `0.2.0` (per the [`pre-release-tech-stack-review`](../.claude/skills/pre-release-tech-stack-review/SKILL.md) skill). Decide: ship a domain-aware subagent in `.claude/agents/security-reviewer.md`, OR confirm the built-in skill remains sufficient. The decision becomes load-bearing once sensitive code starts landing.

**Resolution:** Resolved (2026-05-29 0.2.0 pre-release review) — **ship it.** A Tessera-specific `.claude/agents/security-reviewer.md` is added (read-only; *advise-don't-dictate*) with a broad mandate: PII in logs/errors, input validation, camera-buffer memory hygiene, supply-chain (dependency vulnerabilities, licenses, plugin provenance), publishing (signing, POM, no committed secrets), and repo/GitHub settings. It is paired with mechanical CI checks (dependency CVEs, secret scanning) so coverage does not depend on a session remembering to invoke it. The 0.2.0 camera/image surface is where a domain-aware reviewer earns its keep.

### Dokka multi-module aggregation and hosted docs site

The Dokka 2 wiring shipped with publishing infrastructure slice 2 generates per-module HTML javadoc jars (`tessera-types-<v>-javadoc.jar`, `tessera-mrz-core-<v>-javadoc.jar`, etc.) — one self-contained docs set per artifact, matching how Maven Central distributes attached files. The trade-off: KDoc cross-references that span modules (e.g., a `[MrzParser]` reference in `types/vocabulary/ReadMethod.kt` pointing at a class in `mrz-core`) cannot be resolved by Dokka when documenting `types` in isolation, so they render as plain text instead of clickable links in the published HTML. ~5-7 such references exist at 0.1.1; Dokka emits non-fatal warnings during `publishToMavenLocal` for each. IDE navigation (IntelliJ project model) is unaffected — only the published HTML loses click-to-navigate for these references.

The proper fix is **Dokka multi-module aggregation** + a **hosted docs site**: configure a root-level `dokka { }` block that treats all modules as one project, generate a unified HTML site with full cross-linking, and host it (GitHub Pages, Netlify, or a project-owned subdomain like `docs.lightine.io`). Per-module javadoc jars then become either minimal (own pages only) or `JavadocJar.Empty()` since the real docs live at the hosted URL referenced from each module's POM `url`. This is what major Kotlin ecosystem libraries do (kotlinx.coroutines, kotlinx.serialization at `kotlinlang.org/api/...`).

Deferred because at the project's current scale (single maintainer, narrow 0.1.x public API surface) the few unlinked cross-references in published HTML are a mild UX paper-cut, not a broken experience — and the proper fix requires standing up docs-hosting infrastructure that is its own multi-decision conversation (where the site lives, how versioning works in URLs, what CI publishes it, what versions stay alive at the hosted endpoint). That conversation deserves its own slice rather than getting wedged into a publishing-infrastructure PR.

**Trigger:** When public-API browsing UX matters enough to justify the infrastructure work — typically around the `1.0.0` polish pass (public stability commitment lands; the API is wide enough to benefit from rich cross-module navigation; the project is mature enough to deserve a real docs site). Could also trigger earlier if external integrators provide feedback that the per-module HTML is hard to navigate. Decision form: an ADR locking the docs-hosting target + a publishing-infrastructure slice wiring up aggregation + CI deployment.

### Cross-project planning tool (YouTrack vs GitHub Projects vs current setup)

When future projects under `io.lightine` start (potentially with shared contributors), unified visibility across projects may justify a dedicated project-management tool. The current setup (GitHub Issues + `docs/open-questions.md`, `.handoffs/`, ADRs, `CHANGELOG.md`) is sufficient for a single active project; adding tooling now would create stale data and split the source of truth across more places (Principle 11 — internal/simple first, promote when justified).

Three realistic options when this is revisited:

- **Stay with current setup** — GitHub Issues per repo + the existing markdown infrastructure. Lowest overhead; works fine if cross-project coordination stays informal.
- **GitHub Projects** — free, integrated, supports cross-repo boards under a GitHub organization. Provides backlog and roadmap views without adopting a new tool or new login.
- **YouTrack** (JetBrains) — free for up to 10 users on cloud. Strongest for customizable workflows and serious project management. Highest overhead.

**Trigger:** When **both** conditions are true: (a) a second active project exists under `io.lightine` (actual code, actual work — not just an idea), and (b) cross-project visibility or coordination cost becomes a real felt pain. Until both hold, the existing infrastructure is sufficient.

### Mechanical guard against reading credential-bearing files into AI context

On 2026-05-29 an assistant Read the whole of `~/.gradle/gradle.properties` (to check one non-secret line), pulling the maintainer's PGP signing key, its passphrase, and the Sonatype token into the session transcript — an unintended, persistent exposure. This is a general risk for *any* contributor's AI tooling, not one machine: credential-bearing files (`~/.gradle/gradle.properties`, `.env`, `*.pem`, SSH keys) can be read whole by mistake.

Current mitigations are **advisory only** — the "Reading a Whole Credential-Bearing File" pitfall in `.claude/known-pitfalls.md` and a grep-only flag in the maintainer's `reference_local_jdk_setup` auto-memory. By the project's own "Prescribe the path, prohibit the border" pattern, an advisory rule relies on the assistant remembering; the durable fix is **mechanical enforcement**, like the existing `Bash(git push *)` private-content-scan hook and the `block-screenshot.sh` PreToolUse hook.

Candidate design (for a dedicated session): a PreToolUse hook that blocks or warns when a tool call targets a known secret-bearing path (a denylist of globs — `~/.gradle/gradle.properties`, `**/.env*`, `**/*.pem`, `~/.ssh/id_*`, etc.), steering the assistant to a grep-the-one-line alternative; fail-open and human-terminal-unaffected, mirroring the screenshot hook. Honest open design points: whether a PreToolUse hook can intercept the **Read tool** at all (the screenshot and private-content hooks guard **Bash** — so `cat` / `grep -r` are covered there, but a Read-tool guard may need a different mechanism), the denylist scope, and block-vs-warn.

**Source:** 2026-05-29 0.2.0-review session — the exposure described above, plus the user's request to address it generally (for all contributors), not per-machine.

**Trigger:** A dedicated session, separate from feature work, when there's appetite to design the guard. Security-relevant but low-urgency; until then the advisory pitfall + memory flag are the interim mitigation.

---

## How to Use This Document

When making a deferred decision:

1. Find the entry here
2. Make and record the decision in the appropriate document
3. Remove the entry from this list (or mark it resolved with a reference to the resolution)

When deferring a new decision during design or implementation:

1. Add an entry here under the appropriate section
2. Cross-reference from the document where the deferral was made
3. Note what kind of resolution is needed and roughly when

The goal is that no deferred item is forgotten and no implementation work begins while critical questions are unresolved without explicit acknowledgment.
