# Scope

This document defines what the project is and is not. It captures what the SDK supports, what it deliberately excludes, and how its capabilities are sequenced across releases. Where principles describe *what we value* and architecture describes *how we organize the code*, scope describes *what the project actually does*.

This document is living. Scope changes through deliberate decisions, recorded as updates here and supported by decision records when significant. Drift by accident — features sneaking in or out without explicit acknowledgment — is not allowed.

The scope is intentionally bounded. Things that are not listed as in-scope are not in scope, even if they seem related. When something new is proposed, the question is not "should we add this?" but "does this fit our scope as defined, or does it require expanding the scope?" Expanding scope requires the same deliberation as any architectural decision.

---

## Supported Document Types

The SDK reads and validates the following document types. Documents not listed here are out of scope; they may be added in future versions following the same scoping process.

### ICAO 9303 Compliant Documents

The SDK supports all ICAO Doc 9303 Machine Readable Travel Document formats:

- **TD1 format** — three-line MRZ on credit-card-sized documents (national ID cards, residence permits, certain official travel documents)
- **TD2 format** — two-line MRZ on mid-sized documents (some official travel documents, older ID cards)
- **TD3 format** — two-line MRZ on passport booklets
- **MRV-A format** — two-line MRZ on large machine-readable visas
- **MRV-B format** — two-line MRZ on smaller machine-readable visas

These formats are supported regardless of issuing country, provided the document conforms to ICAO Doc 9303.

### Specific Documents in Initial Scope

The following documents are committed targets for the SDK. Documents are listed by category, not by issuing authority, in keeping with the project's vendor-neutral framing.

- **Passports** (current biometric and older non-biometric series; ICAO 9303 TD3 format)
- **National identity cards** (ICAO 9303 TD1 format)
- **Seafarer identity documents** (ICAO 9303 TD1 format)
- **Military identity documents** where they conform to ICAO Doc 9303
- **Residence permits issued to foreigners** (ICAO 9303 TD1 format)
- **Machine-readable visas** (ICAO 9303 MRV-A and MRV-B formats)

### Documents Out of Scope for Initial Releases

The following are not supported in initial scope. They may be added later through explicit scope expansion.

- **Driver's licenses** — typically not MRZ-bearing; relevant ones use NFC-only paths and are deferred to a later release.
- **Non-ICAO compliant documents** — national variants that deviate from ICAO Doc 9303 (for example, certain US permanent resident cards, certain national-only documents). The SDK reads only the ICAO-compliant subset; non-compliant documents either fail format detection or return clearly typed "format not supported" errors.

### Specific Document Implementations

Where a document type is in scope but its specific format details require documentation that is not currently public, the document is treated as in-scope but not yet implemented. The architecture supports it; the implementation is added when documentation becomes available. This applies to certain official identity documents whose formats may not be fully published.

---

## Reading Methods

The SDK supports multiple methods for obtaining document data. Each method is a first-class capability; consumers choose which to enable.

### Live Camera (Machine Readable Zone)

Real-time camera-based reading of the MRZ. The SDK analyzes camera frames, detects the MRZ region, performs OCR, and parses the result. Returns structured data when a valid MRZ is detected.

### Pre-Captured Image (Machine Readable Zone)

OCR-based reading of the MRZ from a saved image (a photo file, a scanned image, etc.). This capability is **opt-in by the consumer**; the default configuration does not allow it. Consumers who want to support saved-image reading must explicitly enable it.

The reason for opt-in: saved images can be manipulated, replayed, or sourced from outside the consumer's controlled environment. The SDK does not judge whether this is acceptable for a given use case (Principle 1 — Reader, Not Oracle); it leaves the policy decision to the consumer by requiring explicit enablement.

### Manual Entry

Direct entry of MRZ data (or chip access keys) by the user, without scanning. Manual entry is a primary, first-class path — not just a fallback for failed scanning. Consumers may offer it as the only path, alongside camera, or in any combination.

### NFC Chip Reading

Reading of electronic data from documents with NFC chips. Includes BAC and PACE access protocols, data group parsing, and structural parsing of the Security Object. NFC chip reading is included in scope but ships in a later release than the initial MRZ-only versions (see Release Roadmap below).

### Backend / Server-Side Parsing

The SDK's core parsing, generation, and validation logic operates on plain string and byte data with no I/O. This means it can be invoked in any environment that supports the project's core technology stack — including backend services, batch processors, and command-line tools — without requiring any of the platform I/O modules.

This is a consequence of the architecture, not a separately implemented feature. It is in scope as a usage pattern; no additional capability is needed.

---

## Capabilities

The SDK provides the following capabilities for documents within its supported types.

### Parsing

Conversion of raw MRZ strings or raw chip data into structured, typed representations. Parsing is faithful: it reproduces what the document contains, including misspellings, anomalies, and unexpected values, exactly as encoded.

### Generation

Conversion of structured data into a valid MRZ string. Generation enables testing, round-trip verification, and document-issuance use cases. Generation produces strings that conform to ICAO Doc 9303; if the input data cannot produce a conformant output, generation fails with a typed error rather than producing invalid output silently.

### Validation

Multi-level validation of MRZ content:

- **Structural validation** — line count, line length, character set, field positioning
- **Check digit validation** — per-field check digits (document number, dates, optional data) and the composite check digit
- **Semantic validation** — dates parse to real calendar dates and fall within reasonable ranges; country codes exist in lookup tables; document type codes are recognized; sex field is a valid value; etc.

The SDK never refuses to return data because validation failed. Validation results accompany the extracted data so consumers can decide what to do (Principle 1).

### Lookup Tables and Reference Data

The SDK includes:

- **Country and nationality codes** following ISO 3166-1 alpha-3 with ICAO-specific extensions (for example, codes used by ICAO that extend beyond the base ISO list)
- **Document type codes** including both single-character legacy codes and the two-character codes adopted in current ICAO Doc 9303 editions
- **Transliteration profiles** including the ICAO default and per-country profiles where local conventions differ; the initial release includes profiles for the issuing country of primary interest and a default fallback

Additional country profiles are added as needed. The architecture supports profile registration without modifying core code.

### Read-Method Metadata

Every result the SDK returns includes metadata identifying which reading method produced the data: live camera, pre-captured image, manual entry, NFC chip, or some combination. Consumers see exactly how the data was obtained.

The risk profile of each method — what it can guarantee and what it cannot — is documented separately (see "Risk Documentation" below). The SDK reports the method used; the consumer interprets the implications.

---

## Supported Platforms

The project is target-agnostic in design. The core logic is target-portable common Kotlin; specific Kotlin Multiplatform targets are enabled per the release that requires them, not all at once. The JVM target is enabled in `0.1.0` because the core MRZ logic is pure (no platform I/O) and JVM is the fastest target for development, testing, and backend consumers. Mobile targets activate in `0.2.0` when camera reading lands and platform APIs become necessary; see the release roadmap below.

### Committed Platform Coverage

The SDK commits to supporting these platforms across the initial release wave. Activation timing per release is described above.

- **Android** — minimum `minSdk` 23 (Android 6.0), committed when the Android target activates in 0.2.0. Kept low (matching the AndroidX libraries' own default) to maximize the consumer apps that can adopt the SDK.
- **iOS** — minimum deployment target 18, committed when the iOS target activates in 0.2.0.
- **JVM** — Java 21 or newer at runtime: the published JVM artifacts target JVM 21 bytecode (the build's `jvmToolchain(21)`, with no lower `jvmTarget` override). Confirmed as a deliberate floor (maintainer decision, 2026-06-12), not a toolchain accident. The managed-raise discipline applies in the same spirit as the mobile minimums — lowering the floor is non-breaking; raising it is a documented breaking change ([ADR-018](decisions/0018-platform-minimums-and-managed-raise.md)).

Each minimum is documented per release. The build compiles against the latest stable SDKs ([ADR-017](decisions/0017-mobile-targets-and-build-stack.md)); the run-time floors above follow the **managed-raise policy** — raising a minimum is a documented breaking release made only when forced (vendor end-of-life, an unpatched security issue, or a rising toolchain floor), while lowering one is non-breaking ([ADR-018](decisions/0018-platform-minimums-and-managed-raise.md)).

### Architecturally Supported Targets

The architecture supports the following additional targets, which can be activated when a use case justifies the work:

- **JVM backend** — server-side parsing, validation, and generation; useful for batch processing, document issuance flows, and admin tools. The JVM target is already enabled today for development; this entry refers to committing to backend use as a first-class product offering.
- **Web (JS/Wasm)** — browser-side validation and generation for web-based forms or online tools.
- **Desktop (JVM and native)** — desktop applications using the SDK for parsing, with optional desktop-appropriate I/O bridges (for example, USB document readers).

These targets are not committed to specific releases. They are noted here so the architecture and scope are not narrowed prematurely.

---

## User Interface Offering

The SDK provides UI capabilities at two levels.

### Headless Cores

Every reading method has a headless implementation. Consumers who want to build their own UI can integrate at this level: pass camera frames, NFC tags, or user-typed strings to the SDK and receive parsed results. No assumptions about presentation are made.

### Optional UI Modules

For consumers who want a complete out-of-the-box experience, the SDK provides optional UI modules. The optional UI is built using each platform's native UI toolkit (Principle 8).

The optional UI for the initial mobile targets:

- Provides a unified scanner experience that can include live camera, pre-captured image selection, and manual entry options based on consumer configuration
- Provides clear NFC tap guidance when chip reading is enabled
- Follows native platform design conventions on each platform
- Supports platform accessibility features (screen readers, dynamic type, high-contrast modes, etc.)
- Externalizes all user-facing strings using the platform's standard localization mechanism; ships with English strings only and accepts consumer-provided overlays for other languages

Consumers who use the optional UI accept that visual styling follows native conventions; consumers who need bespoke designs use the headless cores instead.

### UI Internationalization

The SDK ships English-only user-facing strings. Translations to other languages are added by consumers using the standard localization mechanism of their platform — resource files on Android, string catalogs on iOS, resource bundles on the JVM, internationalization libraries on the web, and so on. The SDK does not hardcode user-facing text in code on any target.

This applies to the optional UI modules. It does not apply to error codes, log messages, or developer-facing diagnostics, which are English-only and not localizable (Principle 7 — errors are typed; codes are stable identifiers, not user-facing text).

---

## Cross-Cutting Commitments

These commitments apply to all SDK functionality regardless of feature, platform, or release.

- **No data persistence by default.** The SDK does not store document data anywhere unless the consumer explicitly directs it to, through capabilities the SDK does not currently provide.
- **No network calls.** The SDK's own code has no hardcoded URLs, no built-in telemetry destination, and no phone-home behavior; network capabilities, if needed, are provided by the consumer through pluggable interfaces. One dependency caveat as of `0.2.0`: the Android OCR engine (ML Kit, bundled model) transitively includes Google's data-transport / Firebase-encoder components. The SDK initializes none of them and the OCR model runs on-device, but a consumer who must attest *zero* network egress should account for them in a dependency audit — see [`reading-risks.md`](reading-risks.md) ("Third-party OCR dependency surface").
- **Pluggable telemetry.** The SDK exposes a telemetry interface that consumers may implement. The default implementation is a no-op. The SDK does not include analytics, crash reporting, or licensing checks.
- **English-only diagnostics.** Error codes, log messages, and developer-facing strings are English. Consumers who need translated user-facing text use the localization mechanism in UI modules.
- **Permission boundary.** The SDK does not request runtime permissions itself. Consumers handle permission flows; the SDK reports clearly typed errors when required permissions are not granted.
- **Accessibility.** UI modules follow each platform's native accessibility guidelines. The headless cores expose data structures that consumers can use to build accessible experiences in their own UIs.
- **Memory hygiene.** Sensitive data (extracted document content, cryptographic keys, raw chip bytes) is held only as long as needed and cleared promptly using techniques appropriate to each target.

---

## Risk Documentation

Each reading method has different properties relevant to trust and integrity. The SDK documents these properties separately so consumers can understand what each method does and does not guarantee.

The risk documentation lives in its own document (`reading-risks.md` or similar), referenced from feature documentation and from this scope document. It describes, for each reading method:

- What the method establishes about the data
- What it does not establish
- What classes of attacks or errors are possible
- What additional verification (server-side, biometric, etc.) the consumer might want to layer on top

The SDK never decides for the consumer whether a given method is "safe enough" for a given use case (Principle 1). The documentation gives consumers the information they need to decide.

---

## Release Roadmap

The project ships in a sequence of releases. The roadmap below is a current working plan; the order and grouping can change as the project evolves. Versioning follows Semantic Versioning. Release plans are not commitments to fixed timelines.

### 0.1.0 — Core MRZ Logic

The foundation. Pure parsing, generation, and validation for all ICAO Doc 9303 MRZ formats. Includes lookup tables, transliteration profiles, error taxonomy, and pluggable telemetry. No camera, no NFC, no UI. The release enables the JVM target; the core logic is target-portable common Kotlin, with additional targets activating in subsequent releases per their reading methods.

**Pre-release tech-stack review (2026-05-17):** Reviewed against the Pre-Release Tech-Stack Review rule in [`CLAUDE.md`](../CLAUDE.md). Foundation work — parsing/validation/generation triad for all five MRZ formats, error taxonomy, lookup-table machinery — ships in this release. Two subsystems remain before tag: transliteration profiles and the pluggable telemetry interface. Transliteration triggers its own focused review (Unicode strategy is the key open question) before its code work begins. Full lookup-table data population and mobile target enablement are tracked separately in [`open-questions.md`](open-questions.md) and are not 0.1.0 blockers.

**Post-review update (2026-05-18):** A subsequent pre-tag spec-conformance verification pass against ICAO Doc 9303 expanded both lookup tables to spec coverage and shipped them in 0.1.0 (per Path A: bundle expansion into 0.1.0 rather than defer to 0.1.1). `CountryCodeTable` now contains the full ISO 3166-1 alpha-3 list plus the ICAO §5 extensions (~272 entries); `DocumentTypeCodeTable` now contains the full Part 4 §4.4 harmonized `P`-prefix set plus the legacy single-character codes and the Part 5 Appendix B `AC` code (~13 entries). The mobile-target deferral above is unchanged — Android target activates in 0.2.0 alongside camera reading; iOS target deferral remains pending Xcode availability. *(Superseded by the 0.2.0 pre-release review below: Xcode 26.5 is now present on the development machine, so both the Android and iOS targets activate in 0.2.0 — see [ADR-017](decisions/0017-mobile-targets-and-build-stack.md).)*

**Post-tag update (2026-05-19):** A pre-tag empirical pass on the `AzeTransliterationProfile` (one of the two transliteration profiles 0.1.0 commits to) expanded it from a single schwa override (`Ə → A`) to a full systematic phonetic Anglicization override set (8 overrides total), backed by sample documents + a fluent speaker's testimony + the ALA-LC romanization table. See [ADR-009](decisions/0009-transliteration-profiles.md) for the reframe; see `CHANGELOG.md` `[0.1.0]` for the full set. This is what ships in 0.1.0.

### 0.2.0 — Live Camera Reading

Adds the camera-based MRZ reading capability for initial mobile targets. Headless: consumers integrate camera frames and receive parsed results. No bundled UI yet.

**Pre-release tech-stack review (2026-05-29):** Reviewed against the Pre-Release Tech-Stack Review rule in [`CLAUDE.md`](../CLAUDE.md). No foundational choice changed — Kotlin Multiplatform, native I/O per platform, and native UI per platform all hold; the drift was in tooling/build assumptions and doc currency, not architecture. This release activates the Android and iOS targets on the core modules and adds the `mrz-camera-{platform}` I/O modules, sequenced Android-first then iOS under a single 0.2.0 tag. Build stack: AGP 9 with Google's `com.android.kotlin.multiplatform.library` plugin (the legacy `androidTarget()` form is incompatible with the current Kotlin/AGP toolchain), Gradle daemon on JDK 17 and compile toolchain on JDK 21, building against the latest stable SDKs — see [ADR-017](decisions/0017-mobile-targets-and-build-stack.md). Platform minimums are set for the first time here (Android `minSdk` 23, iOS deployment target 18) under a managed-raise policy — see [ADR-018](decisions/0018-platform-minimums-and-managed-raise.md) and [`versioning.md`](versioning.md). Reading frameworks: CameraX + ML Kit (Android), AVFoundation + Apple Vision (iOS). iOS artifacts distribute via Swift Package Manager — see [ADR-019](decisions/0019-ios-distribution-via-spm.md). The camera-reading architecture — an analyse-frame core plus an owns-the-session convenience, strict + lenient parsing, and quality surfaced as metadata rather than gated — is recorded in [ADR-020](decisions/0020-camera-reading-architecture.md); tolerant parsing and a richer quality scorer are deferred to 0.3.0. Local-machine tooling and the agent-driven workflow are documented in [`development-setup.md`](development-setup.md).

### 0.3.0 — Pre-Captured Image Reading

Adds image-based MRZ reading from saved images. Opt-in by consumer configuration. Headless.

### 0.4.0 — Manual Entry

Adds the manual entry capability as a first-class reading method. Headless: consumers build their own input UI and pass typed strings to the SDK.

### 0.5.0 — Default UI Module

Adds the optional UI module providing a unified scanner experience for the reading methods available in previous releases. Native UI per platform. Includes accessibility support and platform-standard localization.

### 0.6.0 — NFC Chip Reading

Adds NFC chip reading: BAC and PACE protocols, data group parsing (DG1, DG2, and other relevant data groups), structural parsing of the Security Object. Includes manual entry path for chip access keys. Cryptographic verification of the chip's signature against trust anchors is not included in this release.

### 1.0.0 — Public Release

The first stable, public release. API is committed to backwards compatibility under Semantic Versioning. The project is open-sourced. By this point all 0.x capabilities are present and consolidated; the release primarily marks the stability commitment, not new features.

### Beyond 1.0

The following capabilities are planned for releases after 1.0.0. Their order and grouping are not yet decided.

- **Cryptographic chip signature verification.** Validation of the Security Object signature against trust anchors. Requires a trust anchor source, which is its own design problem.
- **Driver's license NFC reading.** Documentation-dependent; may target standard mDoc-compliant licenses (ISO 18013-5) or proprietary formats depending on what is implemented.
- **Selfie capture.** Capturing a face image in a controlled way for downstream comparison.
- **Liveness detection.** Establishing that a captured selfie is from a live person, not a replay or synthetic image.
- **Face matching.** Comparing a selfie against a face image extracted from a document or chip.
- **Verifiable credential holder.** Wallet-style functionality for holding and presenting credentials, aligned with relevant standards (W3C VC, ISO 18013-5, OpenID4VCI/VP).
- **Access-controlled session.** Method-level access control gated by hardware-backed cryptographic responses, for use cases where the SDK should not operate without explicit user presence.
- **Additional country transliteration profiles.** Adding profiles as use cases require.
- **Non-ICAO national document support.** Where consumer demand justifies the per-document implementation cost.

These are intentions, not commitments. The project may add, reorder, combine, or remove items as understanding evolves. Principle 4 — honest about what we know.

---

## What This Document Is Not

To prevent scope creep, some specific things are explicitly not part of this document.

- **Implementation specifics** (threading, data formats, binary size, error names) — these belong in conventions documentation or feature documentation.
- **Versioning policy** (release process, deprecation rules, version semantics) — these belong in versioning documentation.
- **Architectural details** (module structure, dependency graph, technology choices) — these belong in the architecture document.
- **Decision rationale** (why we chose KMP, why we rejected Compose Multiplatform) — these belong in decision records.
- **API specifications** (function signatures, class hierarchies) — these belong in API reference and feature documentation.

When a question crosses the boundary between "what" and "how," the answer goes in the appropriate document, not here.

---

## How This Document Relates to Principles

Scope is shaped by principles (defined in `principles.md`):

- **Principle 1 (Reader, not oracle)** — appears in the opt-in policy for pre-captured images, in the read-method metadata, in the choice not to include verification in scope.
- **Principle 2 (Logical defensibility)** — appears in the version-by-version sequencing that defers complex features and ships small useful slices.
- **Principle 3 (Modular)** and **Principle 8 (Native fit)** — appear in the headless-core / optional-UI structure.
- **Principle 4 (Honest)** — appears in the explicit framing of the roadmap as intent, not commitment.
- **Principle 5 (Transparency)** — appears in read-method metadata and the commitment to expose every extracted value.
- **Principle 9 (Forward-compatible)** — appears in the cautious initial release sequencing and the explicit "what's not in" lists.
- **Principle 10 (Privacy)** — appears throughout the cross-cutting commitments.

When scope and principles conflict, principles win and scope adjusts.
