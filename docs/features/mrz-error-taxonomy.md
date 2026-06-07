# MRZ Error Taxonomy

This feature document defines the structure of errors, validation failures, and warnings produced by the MRZ-related features of the SDK. It establishes the three-tier model that all MRZ features adhere to, the contracts each type honors, and a representative set of examples for each category. The full list of types emerges through implementation and testing rather than being specified completely up front.

This document focuses on the SDK's design choices: how failures are categorized, what context each type carries, and the rules that govern adding new types. It is a foundational document — most other MRZ feature documents reference it.

**Status:** Living
**Available since:** 0.1.0
**Platform availability:** Target-agnostic. The error taxonomy is pure logic and runs on every target the project supports.

---

## Purpose

A consistent, well-typed error model is the foundation of trustworthy SDK integration. Consumers should always know what went wrong, why, and what context describes it. They should never see generic "something went wrong" messages, never have to parse string descriptions to understand a failure, and never be surprised by a category of failure they did not anticipate.

The error taxonomy is designed around the project's principles:

- Failures are typed and specific, never collapsed into generic categories (Principle 7 — Fail loudly, fail informatively)
- The SDK never refuses to return data because of validation issues; failures and warnings accompany data rather than replacing it (Principle 1 — Reader, not oracle)
- Every typed failure carries enough context to be actionable (Principle 7)
- Adding new types is a non-breaking change when done correctly (Principle 9 — Forward-compatible API)
- The categorization protects consumers from coupling their handling logic to internal implementation details (Principle 5 — Transparency: the categorization itself is part of the public contract)

---

## Three-Tier Model

Failures fall into one of three categories. The category determines where the failure appears in result types and how consumers are expected to react.

### Errors

**Definition:** An operation could not complete. No usable result was produced.

**Where they appear:** As variants of result types (e.g., `ParseResult.Failure`). Errors are part of the result, not part of the metadata.

**Consumer expectation:** Try a different input, retry the operation, or surface the failure to the user. The data is not available.

**Examples:**
- Input is structurally too broken to construct a document at all (wrong line count, characters outside the MRZ alphabet, fundamental field misalignment)
- Generation cannot produce a valid MRZ from the given input (e.g., document number exceeds the field width with no valid fallback)

### Validation Failures

**Definition:** Data was extracted, but it does not conform to the relevant specification.

**Where they appear:** Inside `ResultMetadata.validationFailures`, alongside the extracted data. The result is `ParseResult.Success` or `ParseResult.PartialSuccess` — never `Failure`.

**Consumer expectation:** Read the data, examine the failures, decide whether to accept the data based on the consumer's own policy. Some consumers reject any check digit failure; some accept warnings as long as data is structurally usable. The SDK does not pre-decide.

**Examples:**
- A check digit in the MRZ does not match the value computed from the surrounding field
- A country code in the MRZ does not exist in any recognized lookup table
- A date in the MRZ is structurally well-formed but does not parse to a real calendar date (e.g., February 30)
- A document type code is not recognized

### Warnings

**Definition:** Data is structurally valid and conforms to the specification, but is anomalous in a way the consumer might want to know about.

**Where they appear:** Inside `ResultMetadata.warnings`, alongside the extracted data.

**Consumer expectation:** Optional. The data is usable as-is; the warning is informational. Consumers may surface it for human review, log it for analytics, or ignore it entirely.

**Examples:**
- The document's expiry date is in the past
- The document's expiry date is more than 10 years in the future (unusual for most document types)
- A name field shows truncation (per ICAO Doc 9303 truncation indicator)
- The MRZ as read from a chip and the MRZ as read from camera differ in some fields (only relevant when both reading paths produced data; not applicable when chip and camera reading are not yet available)

---

## Why This Categorization Matters

The three-tier model has direct consequences for how consumers write code:

- An error means *handle this case explicitly because you do not have data to work with*
- A validation failure means *examine and decide based on your policy*
- A warning means *optionally surface this; the data is usable*

A poorly categorized SDK collapses these into one stream, forcing consumers to handle every possibility identically. This either leads to overly cautious consumers (rejecting usable data because of a mild warning) or overly permissive consumers (accepting broken data because they could not distinguish severity).

The model is also stable across SDK evolution: when a new typed warning is added in a future release, consumers built against earlier versions continue to work — they receive a richer warning list, but their handling code is unchanged.

---

## Sealed Hierarchies

Each category is rooted in a sealed type. Adding a new type is an additive change, but the sealed hierarchy ensures consumers can match exhaustively when they want to.

The illustrative shape:

```
sealed class MrzError {
    abstract val description: String
    // ... specific variants below
}

sealed class MrzValidationError {
    abstract val description: String
    // ... specific variants below
}

sealed class MrzWarning {
    abstract val description: String
    // ... specific variants below
}
```

Note that the `description` field is documented as English-only and intended for diagnostic purposes (logs, debugging output, internal dashboards). It is not localized. Consumers building user-facing experiences map error types to their own messages using stable identifiers, not the description string.

The error class itself is the stable identifier. The type name (`MrzCheckDigitMismatch`, etc.) is part of the public API surface and follows the project's naming conventions (see `conventions.md`).

### Error Sub-Categorization by Operation

Within the Errors tier, types are sub-categorized by the operation that produces them. Two intermediate sealed classes refine `MrzError`:

```
sealed class MrzParseError : MrzError()
sealed class MrzGenerationError : MrzError()
```

Concrete error types extend the appropriate sub-root: `MrzInvalidLength`, `MrzCharacterSetViolation`, and `MrzFormatNotDetected` extend `MrzParseError`; `MrzGenerationFieldOverflow`, `MrzGenerationUnsupportedCharacters`, and `MrzGenerationNumericInNameField` extend `MrzGenerationError`.

This sub-categorization makes result types typesafe per operation: `ParseResult.Failure.error: MrzParseError` and `GenerationResult.Failure.error: MrzGenerationError`. A consumer handling parse failures cannot accidentally receive a generation error and vice versa.

Future operations (validation, transliteration, etc.) may introduce their own intermediate sealed roots if they produce errors with operation-specific patterns. Today only the parsing and generation sub-roots exist; validation failures and warnings have their own top-level sealed hierarchies (`MrzValidationError`, `MrzWarning`) and do not currently sub-categorize.

---

## Required Context

Every typed failure carries enough context to be actionable. Specific context fields vary by type, but the rule is: **a consumer reading the failure should be able to answer "what failed, where, what was expected, what was observed."**

For example, a `MrzCheckDigitMismatch` carries:
- Which field's check digit failed (a `MrzField` enum: `DOCUMENT_NUMBER`, `DATE_OF_BIRTH`, `DATE_OF_EXPIRY`, `OPTIONAL_DATA`, `COMPOSITE`)
- The expected check digit (the value computed from the surrounding field)
- The observed check digit (the character that appeared in the MRZ at the check digit position)
- The position in the original MRZ string (for diagnostic purposes)

Generic catch-all errors with only a description string are not used. If a failure cannot be characterized with structured context, it is a sign that the type itself is too broad and should be split.

---

## Naming Conventions

Type names follow the patterns established in `conventions.md`. Specifically:

- Error types describe the specific failure: `MrzInvalidLength`, `MrzCharacterSetViolation`, `MrzGenerationFieldOverflow`
- Validation error types describe the specific spec violation: `MrzCheckDigitMismatch`, `MrzInvalidSexValue`, `MrzDateNotInCalendar`
- Warning types describe the specific anomaly: `MrzExpiryDatePast`, `MrzExpiryDateImplausiblyFar`, `MrzNameTruncated`

Names that would be rejected:

- `MrzGeneralError` — too vague
- `MrzException` — not informative
- `InvalidData` — does not say what is invalid

Each new type is named at the level of specificity that lets a consumer write meaningful handling code without further inspection.

---

## Representative Examples

The following examples illustrate the kinds of types each category contains. This is not the full list — additional types are added as implementation and testing surface real failure modes.

### Errors (operations that could not complete)

- `MrzInvalidLength` — input has the wrong number of lines or wrong line length for any supported format
- `MrzCharacterSetViolation` — input contains characters outside the MRZ alphabet (anything other than `A-Z`, `0-9`, `<`)
- `MrzFormatNotDetected` — input has plausible structure but does not match any of the supported formats
- `MrzGenerationFieldOverflow` — input data does not fit within the field widths of the requested format
- `MrzGenerationUnsupportedCharacters` — input data contains characters outside the MRZ alphabet and no transliteration profile resolved them; carries the format, the offending field, the list of unmapped characters with positions, and the observed value
- `MrzGenerationNumericInNameField` — input data (primary or secondary identifier) contains numeric characters, which ICAO Doc 9303 Part 3 §4.6 forbids in MRZ name fields; carries the format, the observed value, and the list of numeric characters encountered

### Validation Failures (data extracted but does not conform)

- `MrzCheckDigitMismatch` — a check digit does not match the computed value, with field identifier and observed/expected values
- `MrzDateNotInCalendar` — date is structurally well-formed but does not represent a real calendar date
- `MrzInvalidSexValue` — sex field contains a character outside the SDK's accepted set (`M`, `F`, `<`, `X`). The ICAO Doc 9303 2021 Eighth Edition lists the canonical MRZ sex characters as `M`, `F`, `<` only (Part 4 §4.2.2.2 and equivalents); the SDK accepts `X` as a documented real-world deviation and surfaces `MrzSexCharacterX` as a warning rather than emitting this failure for it

### Warnings (data is valid but anomalous)

- `MrzExpiryDatePast` — document's expiry date has passed
- `MrzExpiryDateImplausiblyFar` — document's expiry date is more than 10 years in the future
- `MrzBirthDateImplausiblyOld` — date-of-birth components imply an age greater than 130 years at every candidate century interpretation
- `MrzUnknownCountryCode` — issuing state or nationality code is not in the SDK's recognized lookup tables (warning rather than failure per [ADR-013](../decisions/0013-recognition-failures-are-warnings.md): the SDK's tables are deliberately incomplete, so an unrecognized code is "not in our table," not "not in the spec")
- `MrzUnknownDocumentTypeCode` — document type code is not in the SDK's recognized lookup tables (warning rather than failure per [ADR-013](../decisions/0013-recognition-failures-are-warnings.md))
- `MrzNameTruncated` — name field shows the truncation indicator per ICAO Doc 9303
- `MrzPersonalNumberCheckDigitFiller` — the personal number check digit is the filler character `<`, which some issuing states use even when the personal number is populated
- `MrzSexCharacterX` — sex field contains the character `X`. ICAO Doc 9303 2021 Eighth Edition lists the canonical MRZ sex characters as `M`, `F`, `<` only (Part 4 §4.2.2.2 and equivalents) — `X` is reserved for the VIZ per the spec's Notes p/f. Real-world practice has nonetheless adopted `X` in the MRZ for non-binary or unspecified documents. Surfaced as a warning rather than a validation failure so consumers can decide whether to treat the deviation as disqualifying

---

## Adding New Types

New error, validation failure, or warning types are added through normal feature development. The rules:

- A new type may be added in any MINOR or PATCH release; this is a non-breaking change
- Removing an existing type or changing its semantic meaning is a breaking change requiring a MAJOR version bump and following the deprecation cycle (see `versioning.md`)
- Renaming a type is breaking; in practice, a rename is implemented as adding the new type and deprecating the old one
- Splitting a generic type into more specific subtypes is non-breaking when the original type is preserved as a parent in the sealed hierarchy

This means the taxonomy grows safely over time. A consumer's exhaustive matching logic continues to compile across releases — it may produce compile-time warnings about unhandled cases, but never errors.

---

## Where the Taxonomy Lives in Code

The error types are defined in the `types` module (per `architecture.md`), which is the foundation module depended on by all other MRZ-related modules. This placement allows any module that produces or consumes MRZ data to reference the same error types without circular dependencies.

The `mrz-core` module produces the parsing, generation, and validation errors. Future platform I/O modules (`mrz-camera-*`, `emrtd-nfc-*`) may produce their own platform-specific errors, defined in their own modules but conforming to the same three-tier categorization. The first such set is the **`Camera…` capture-error family** (0.2.0), a sealed `CameraError` defined in the platform-agnostic `mrz-camera-core` module and surfaced on `MrzScanResult.CaptureError` — distinct from the `MrzError` taxonomy, each member carrying a stable English `code` the consumer localizes. See [`mrz-camera-reading.md`](mrz-camera-reading.md) and [ADR-020](../decisions/0020-camera-reading-architecture.md). Its concrete members are enumerated here as they land in code (with tests), per the project's new-error-type rule:

- **`CameraError.OcrFailed`** (`code = "camera.ocr_failed"`) — the platform OCR engine failed to process a frame (it threw or reported an error rather than returning text). Surfaced by the analyse-frame core so the consumer can retry the next frame, never thrown. Landed with the analyse-frame slice; produced in tests by a recognizer that throws.
- **`CameraError.CameraUnavailable`** (`code = "camera.unavailable"`) — the camera could not be opened (no usable camera, or the platform reported the hardware as unavailable). The catch-all capture-availability failure, distinct from the two below.
- **`CameraError.PermissionDenied`** (`code = "camera.permission_denied"`) — the `CAMERA` permission is not granted, so the session cannot start. The consumer owns the permission *request* (`scope.md` "permission boundary"); the scanner only reports this so the consumer can prompt and retry.
- **`CameraError.CameraInUse`** (`code = "camera.in_use"`) — the camera is held by another client and cannot be opened now; the consumer may retry once it is released.

The three capture-availability members landed with the owns-the-camera-session layer (`MrzCameraScanner`). They are surfaced — never thrown — on the scanner's `results` stream as a `MrzScanResult.CaptureError` when the camera cannot start. Host tests produce each member through the scanner's surfacing path and assert its stable code. The Android failure-to-member mapping was shaken out on a device in the live-device slice and is **driven by CameraX's camera state**, not by a bind-time exception: CameraX reports a failed open asynchronously through `CameraState`, so the scanner observes that state and maps the error code — `ERROR_CAMERA_IN_USE` / `ERROR_MAX_CAMERAS_IN_USE` → `CameraInUse`; any other open failure → `CameraUnavailable`, *unless* the `CAMERA` permission is not held, in which case `PermissionDenied`. The permission classification is required because CameraX collapses a permission denial into a generic `ERROR_CAMERA_FATAL_ERROR` with a null cause — its public state API does not distinguish "no permission" from a hardware fault — so the scanner reads the observable permission state (read-only; it never requests permission) to report the consumer's actionable cause (reader-not-oracle: it reports the observable fact, it does not infer a cause it cannot see). Device-verified for the permission/fatal path (`PermissionDenied` on a revoked permission, clean streaming when granted) and for the recoverable `CameraInUse` path (2026-06-01): a second client taking then releasing the camera surfaces a **non-terminal** `CameraInUse` while CameraX stays bound and auto-recovers when the camera is released.

The iOS scanner (`AVCaptureMrzScanner`, AVFoundation) maps the same three members to its own platform signals. Permission is read at startup from `AVCaptureDevice.authorizationStatus(for:)` — read-only, never prompting (the same permission-boundary stance as Android's permission read) — and a non-authorized status is `PermissionDenied`; no camera for the requested position is `CameraUnavailable`. AVFoundation's run-time failures arrive as `AVCaptureSession` notifications rather than a polled state: an interruption whose reason is `videoDeviceInUseByAnotherClient` is `CameraInUse`, and a session runtime error is `CameraUnavailable`. Like Android, these are surfaced — never thrown — on the `results` stream. The mapping is compiled on CI; its live behaviour (the iOS Simulator has no camera) is device-verified separately, including the recoverable `CameraInUse` path (2026-06-01): the `videoDeviceInUseByAnotherClient` interruption surfaces a **non-terminal** `CameraInUse` while the session stays bound and AVFoundation resumes when the interruption ends. The one path that remains device-pending is the `CameraUnavailable`-from-runtime-error trigger — a media-services fault an app cannot summon.

---

## Discovery Through Implementation and Testing

The full list of error, validation failure, and warning types is not specified up front. The taxonomy structure is fixed; the catalog grows as real input surfaces real failure modes.

The discovery process:

- During implementation, edge cases that need typed responses become candidates for new types
- During testing, especially with synthetic test fixtures generated by the project's own MRZ generator, edge cases can be deliberately constructed to probe the parser; failures lead to typed handling
- ICAO Doc 9303 itself serves as a source of edge cases (truncation rules, optional data field semantics, date encoding rules) that inform what kinds of failures can occur

This approach is consistent with Principle 4 (Honest about what we know): we document the structure we are confident about, and we discover the catalog rather than guessing it.

---

## Related Principles

- **Principle 1 (Reader, not oracle)** — validation failures and warnings accompany data rather than replacing it; the SDK does not refuse to return data because of validation issues
- **Principle 4 (Honest about what we know)** — the catalog is discovered, not pre-specified; we document the structure and grow the list as we learn
- **Principle 5 (Transparency)** — the three-tier categorization is part of the public contract, not an implementation detail; consumers can rely on it
- **Principle 7 (Fail loudly, fail informatively)** — every type is specific, every type carries actionable context, no generic catch-alls
- **Principle 9 (Forward-compatible API)** — sealed hierarchies designed to grow through addition; deprecation cycle for any breaking change

---

## Related Documents

- `principles.md` — the foundational principles this document references
- `mrz-data-model.md` — the data model that error and validation results accompany
- `mrz-parsing.md` — the feature that produces parsing errors and validation failures
- `mrz-generation.md` — the feature that produces generation errors
- `mrz-validation.md` — the feature that produces validation failures and warnings
- `conventions.md` — naming conventions for type names
- `versioning.md` — rules for adding, deprecating, and removing types
- `testing.md` — the testing discipline through which the catalog of error types is discovered
