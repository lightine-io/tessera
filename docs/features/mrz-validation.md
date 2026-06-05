# MRZ Validation

This feature document describes the SDK's MRZ validation capability: checking whether MRZ data — either as a raw string or as an already-parsed `MrzDocument` — conforms to ICAO Doc 9303. Validation is invoked implicitly by the parser and the generator, and is also exposed as a standalone operation for consumers who need to validate previously-extracted data without re-parsing.

This document focuses on the SDK-specific design choices: the layered validation model, the public API, and the relationship between standalone validation and the validation that happens inside parsing and generation. The byte-level format specifications and the check digit algorithm itself live in ICAO Doc 9303.

**Status:** Living
**Available since:** 0.1.0
**Platform availability:** Target-agnostic. Validation is pure logic and runs on every target the project supports.

---

## Purpose

Validation answers the question "does this MRZ conform to the specification?" — separated into specific, layered checks so that the answer is precise rather than a single boolean.

Validation matters because consumers make trust decisions based on extracted data. Knowing that a check digit failed is different from knowing that an expiry date is in the past is different from knowing that the input has the wrong number of lines. The SDK preserves these distinctions so consumers can decide what each kind of failure means for their use case (Principle 1 — Reader, not oracle; Principle 5 — Transparency).

---

## Status of Implementation

The validator ships incrementally. The design described in the rest of this document is the target shape; the table below records what is currently implemented versus what is documented but deferred. Each deferred item has a corresponding entry in `docs/open-questions.md`.

| Capability | Status |
|---|---|
| `MrzValidator.validate(document: MrzDocument): ValidationResult` | Implemented |
| `MrzValidator.validate(input: String / List<String>)` overloads | Deferred |
| `MrzValidator.validate(input, format: MrzFormat)` overloads | Deferred |
| Layer 1 — Structural (line count, line length, alphabet) | Implemented inside `MrzParser` (returns `ParseResult.Failure` for these). The standalone `MrzValidator` does not currently re-run structural checks because today it only accepts `MrzDocument`, which has already cleared structural validation. |
| Layer 2 — Per-field check digits (document number, dob, expiry, optional data) for TD3, TD2, TD1, MRV-A, MRV-B | Implemented (TD2 omits the optional-data per-field digit per ICAO Doc 9303 Part 6; TD1 omits per-field check digits on both optional data slots per Part 5; MRV-A and MRV-B omit both the optional-data per-field digit and the composite digit per Part 7) |
| Layer 2 — Composite check digit for TD3, TD2, TD1 | Implemented (TD3, TD2, TD1 only — MRV-A and MRV-B have no composite check digit per ICAO Doc 9303 Part 7) |
| Layer 3 — Sex value range check (`MrzInvalidSexValue`) | Implemented (TD3, TD2, TD1, MRV-A, MRV-B). Accepted set is `{M, F, <, X}` — `M/F/<` are canonical per ICAO Doc 9303 Part 4 §4.2.2.2, while `X` is a documented real-world deviation surfaced as `MrzSexCharacterX` warning rather than as this failure (see warnings row below) |
| Sex character `X` deviation warning (`MrzSexCharacterX`) | Implemented (TD3, TD2, TD1, MRV-A, MRV-B). Emitted when the sex field contains `X` (a real-world deviation from the 2021 Eighth Edition's canonical `M/F/<` set; the spec reserves `X` for the VIZ per Note p/f). Accept-plus-warn rather than reject-as-invalid; matches the `MrzPersonalNumberCheckDigitFiller` pattern for documented real-world deviations |
| Layer 3 — Country code recognition (`MrzUnknownCountryCode`) | Implemented (TD3, TD2, TD1, MRV-A, MRV-B; surfaces as a warning per [ADR-013](../decisions/0013-recognition-failures-are-warnings.md)). The table now contains ~272 entries (full ISO 3166-1 alpha-3 + ICAO Doc 9303 Part 3 §5 extensions, populated during the 2026-05-18 conformance pass — see `docs/open-questions.md` "Country code table completeness") |
| Layer 3 — Document type code recognition (`MrzUnknownDocumentTypeCode`) | Implemented (TD3, TD2, TD1, MRV-A, MRV-B; surfaces as a warning per [ADR-013](../decisions/0013-recognition-failures-are-warnings.md)). The table contains the full Part 4 §4.4 P-prefix harmonized set plus the legacy single-character codes and the Part 5 Appendix B `AC` code, populated during the 2026-05-18 conformance pass — see `docs/open-questions.md` "Document type code table completeness") |
| Layer 3 — Date in calendar (`MrzDateNotInCalendar`) | Implemented (TD3, TD2, TD1, MRV-A, MRV-B, both birth and expiry; relies on `MrzDate.componentsFormCalendarDate` to distinguish "no real calendar date" from "components didn't parse as ints" or "calendar-valid but outside the parser's inference window") |
| Expiry-date warnings (`MrzExpiryDatePast`, `MrzExpiryDateImplausiblyFar`) | Implemented (TD3, TD2, TD1, MRV-A, MRV-B; `MrzExpiryDateImplausiblyFar` threshold defaults to 10 years and is non-configurable for now — see `docs/open-questions.md` "Validator options (configurable thresholds)") |
| Birth-date warning (`MrzBirthDateImplausiblyOld`) | Implemented (TD3, TD2, TD1, MRV-A, MRV-B; threshold matches the parser's `MrzDate.MAX_PLAUSIBLE_AGE_YEARS = 130` cap and is non-configurable for now — see `docs/open-questions.md` "Validator options (configurable thresholds)") |
| Name truncation warning (`MrzNameTruncated`) | Implemented (TD3, TD2, TD1, MRV-A, MRV-B; signal-driven from `commonFields.nameTruncated`, which the parser populates per ICAO Doc 9303 convention — see `mrz-parsing.md` "Truncated Names") |
| Personal-number check-digit filler deviation warning (`MrzPersonalNumberCheckDigitFiller`) | Implemented (TD3 only — TD3 is the only format with a `personalNumberCheckDigit` field per ICAO Doc 9303 Part 4. Surfaces when an issuing state leaves the check digit as `<` while the personal number itself contains non-filler content; supersedes the per-field `MrzCheckDigitMismatch` for `OPTIONAL_DATA` in that case so a documented real-world deviation is a warning rather than a failure) |
| `ValidationResult.passedChecks` (transparency surface for what was verified) | Deferred (current `ValidationResult` exposes `validationFailures` and `warnings` only; the shape of `passedChecks` is itself an open question) |

The validator's wiring into the parser is in place: every format-specific parser (`parseTD3`, `parseTD2`, `parseTD1`, `parseMRVA`, `parseMRVB`) invokes `MrzValidator.validate(...)` on the constructed document, threading the same `referenceTime` through, and returns `ParseResult.PartialSuccess` with the failures populated in `ResultMetadata.validationFailures` whenever any failure surfaces, otherwise `ParseResult.Success`. Warnings are populated in `ResultMetadata.warnings` independently of the Success/PartialSuccess decision — a result with warnings but no failures is `Success`.

The accepted set of sex characters used by Layer 3 is `{M, F, <, X}`. Per ICAO Doc 9303 Part 4 §4.2.2.2 (verified in the 2026-05-18 pre-tag conformance pass — see `CONFORMANCE-NOTES-2026-05-18.md`, finding F16), the canonical MRZ sex characters are `M`, `F`, `<` only — `X` is reserved for the VIZ per the spec's Note p/f clauses. The SDK accepts `X` in the MRZ as a documented real-world deviation (non-binary and unspecified-sex documents in some jurisdictions) and surfaces it as a `MrzSexCharacterX` warning so consumers can identify the deviation. Strict consumers who want to treat the deviation as disqualifying check `warnings.isEmpty()` together with `validationFailures.isEmpty()`.

Expiry warnings depend on the validator's reference time and are emitted only when `MrzDate.computedDate` is non-null (i.e., the parser was able to resolve a real calendar date for the expiry). Because `MrzDate.parseExpiry` currently rejects expiries more than 50 years past the reference time as `RAW_ONLY`, `MrzExpiryDateImplausiblyFar` can fire in practice only within the (reference + 10 years, reference + 50 years] window; expiries beyond that are not computed and therefore cannot produce the warning. This is a layered limitation, not a design choice, and is noted here so it does not surprise consumers.

The date-in-calendar check (`MrzDateNotInCalendar`) is signal-driven from the model rather than re-derived in the validator. `MrzDate` carries `componentsFormCalendarDate: Boolean?`, populated by `parseBirth` and `parseExpiry`: `null` when the raw components did not parse as 2-digit numerics (Layer-1 territory, not surfaced by this check), `true` when the components form a real `LocalDate` in at least one candidate century, and `false` when they do not. The validator emits `MrzDateNotInCalendar` only when the signal is `false`, which means a date that is calendar-valid but rejected by the parser's inference window (e.g., an expiry more than 50 years out) does not produce this failure — the date IS in the calendar, just outside the heuristic.

The document type code recognition check (`MrzUnknownDocumentTypeCode`) emits a warning when `DocumentType.isRecognized` is `false` for the document's parsed `documentType`. The warning carries the verbatim `rawCode` (including the empty string when the field was all filler characters) and the field's start position in the MRZ string (position 0 for every format, since the document type slot is always at line 1 character 1). The check runs unconditionally for every format-specific parse path (TD3, TD2, TD1, MRV-A, MRV-B); recognition is consulted via `DocumentTypeCodeTable.lookup` per ADR-012, with the table currently shipping a deliberate starter set documented in [`docs/open-questions.md`](../open-questions.md) "Document type code table completeness." [ADR-013](../decisions/0013-recognition-failures-are-warnings.md) records why this is a warning rather than a validation failure: the SDK's tables are by design incomplete, so an unrecognized code is "not in our table," not "not in ICAO Doc 9303." Strict consumers who treat unrecognized codes as disqualifying read `result.warnings.isEmpty()` together with `result.validationFailures.isEmpty()`.

The name truncation check (`MrzNameTruncated`) is signal-driven from the model: each format-specific parser populates `commonFields.nameTruncated` from the raw name field per ICAO Doc 9303 convention (a complete name always leaves at least one trailing filler `<`; a field that fills exactly to its boundary is indistinguishable from a truncated one and is treated as truncated). The validator emits the warning when the signal is `true`, carrying the verbatim `rawNameField` and the field's start position. The position differs per format: position 5 for TD3, TD2, MRV-A, and MRV-B (name field on line 1, after the 5-char document-type + issuing-state prefix), and position 60 for TD1 (name field on line 3). No `field: MrzField` discriminator is needed — only one name field exists per format, and position uniquely identifies it.

The country code recognition check (`MrzUnknownCountryCode`) follows the same shape, applied independently to each country-code position in the document. The validator emits one warning per `CountryCode` field whose `isRecognized` is `false` — up to two warnings per document (one for `issuingState`, one for `nationality`). Each warning carries the verbatim `rawCode`, the field's global start position, and a `field: MrzField` discriminator (`MrzField.ISSUING_STATE` or `MrzField.NATIONALITY`) so consumers can react to the two positions independently. Positions differ per format: TD3 / MRV-A / TD2 / MRV-B place issuing state at position 2 on line 1; TD1 also has issuing state at position 2 (line 1). Nationality on line 2 lands at position 54 (TD3, MRV-A), 46 (TD2, MRV-B), or 45 (TD1). Recognition is consulted via `CountryCodeTable.lookup` per ADR-012; the table now contains ~272 entries (the full ISO 3166-1 alpha-3 list plus ICAO Doc 9303 Part 3 §5 extensions, populated during the 2026-05-18 conformance pass — see [`docs/open-questions.md`](../open-questions.md) "Country code table completeness"). The categorical placement is the same as `MrzUnknownDocumentTypeCode` per [ADR-013](../decisions/0013-recognition-failures-are-warnings.md).

The birth-age warning (`MrzBirthDateImplausiblyOld`) is signal-driven from the model in the same way. `MrzDate` carries `componentsExceedBirthAgeLimit: Boolean?`, populated only by `parseBirth`: `true` when the parser fell to `RAW_ONLY` because every calendar-valid past candidate exceeds the parser's age cap (`MrzDate.MAX_PLAUSIBLE_AGE_YEARS = 130`), `false` when the parser succeeded or when no past calendar-valid candidate exists, `null` when the question does not apply (non-numeric components, no candidate forms a calendar date, or the date came from `parseExpiry` / direct construction). The validator emits `MrzBirthDateImplausiblyOld` only when the signal is `true`. Because the warning's threshold is the parser's own inference cap, the warning fires only when the parser would otherwise silently fail to compute a date for age reasons — under current-era reference times (year ≤ ~2130) the cap is unreachable in practice; the warning matters for replay scenarios, audit pipelines, and far-future reference times that consumers may pass explicitly.

---

## Single Source of Truth

The validation logic lives in one place: the validation package within `mrz-core`. The parser invokes it during parsing. The generator invokes the parts relevant to input validation. The standalone validation feature exposes the full set as a public API. All three call paths reference the same validators.

This means a check digit failure detected during parsing and a check digit failure detected by standalone validation use the same code, produce the same typed error, and report the same context. Adding or modifying a validator updates all three subsystems consistently. This is consistent with Principle 3 (Modular) and Principle 9 (Forward-compatible API).

---

## Layered Validation Model

Validation is organized in three layers. Each layer depends on the layer beneath it; failures in lower layers may prevent higher layers from running meaningfully.

### Layer 1 — Structural

The most basic checks. They establish that the input could plausibly be an MRZ at all.

- Line count is correct for the format
- Line length is correct for the format
- All characters are in the MRZ alphabet (A-Z, 0-9, `<`)
- Field positions align with the format's specification

If structural validation fails, the input cannot reliably be split into fields, so further validation does not run. The structural failures are returned; deeper layers report no results because they could not execute.

### Layer 2 — Check Digit

Once structure is valid, the parser can extract field values. Check digit validation verifies that the digits encoded in the MRZ match the values computed from the surrounding fields per the algorithm defined in ICAO Doc 9303 Part 3 Appendix A.

- Per-field check digits — for the document number, date of birth, date of expiry, and optional data fields where defined
- Composite check digit — for formats that define one (computed across multiple fields)

Check digit failures are returned as typed validation failures with full context (which field failed, the expected value, the observed value).

### Layer 3 — Semantic

Once fields are extracted, semantic validation examines their contents.

- Dates parse to real calendar dates (e.g., not February 30)
- Dates fall within plausible ranges (with rules per field — see below)
- Country codes are recognized in the lookup tables
- Document type codes are recognized in the lookup tables
- Sex values are within the allowed set
- Other format-specific constraints

Semantic checks may produce either validation failures or warnings, depending on the check. A date that is structurally well-formed but in the past for an expiry date is a warning, not a failure — the data is still valid; the consumer may or may not care.

---

## Public API Shape

The validation API accepts either raw MRZ strings or already-parsed `MrzDocument` instances. Both forms are supported because consumers come from different starting points.

The illustrative shape:

```
object MrzValidator {
    // Validate a raw MRZ string
    fun validate(input: String): ValidationResult
    fun validate(input: List<String>): ValidationResult

    // Validate a raw MRZ string with an explicit format
    fun validate(input: String, format: MrzFormat): ValidationResult
    fun validate(input: List<String>, format: MrzFormat): ValidationResult

    // Validate an already-parsed document
    fun validate(document: MrzDocument): ValidationResult
}
```

When validating raw strings, the validator runs all three layers (structural, check digit, semantic). When validating an already-parsed `MrzDocument`, the structural layer is implicitly already passed (the document exists), so only check digit and semantic layers run.

The actual class names, method names, and parameter shapes are decided at implementation time. The shape above is illustrative.

---

## Result Type

Validation returns a `ValidationResult` (defined in `mrz-data-model.md`) containing:

- `validationFailures` — typed validation errors that indicate non-conformance
- `warnings` — typed warnings that indicate anomalies but not non-conformance
- `passedChecks` — **deferred (target shape, not on the shipped result)**: the validators that ran and passed, exposed for transparency so consumers can confirm what was actually verified, not just what failed. The current shipped `ValidationResult` carries `validationFailures` and `warnings` only; the shape of `passedChecks` is itself an open question, tracked in [`open-questions.md`](../open-questions.md) under "`ValidationResult.passedChecks` shape." (The Status of Implementation table above and [`mrz-data-model.md`](mrz-data-model.md) say the same.)

Validation always returns the complete result. Consumers filter according to their own needs. The validator does not omit warnings to "save effort," does not skip checks based on what previous checks found (except where logically required by layering), and does not collapse multiple findings into a single summary value (Principle 5 — Transparency).

There is no `isValid` boolean on the result. Validity depends on what the consumer cares about. A consumer who treats only failures as disqualifying derives that from `validationFailures.isEmpty()`. A consumer who treats both failures and warnings as disqualifying derives that from `validationFailures.isEmpty() && warnings.isEmpty()`. The SDK does not pre-decide.

---

## Usage

The following example compiles against the shipped API. The parser already validates and folds the result into `ParseResult.metadata`, so call the validator directly for the standalone case — e.g. re-checking a document after modifying a field. There is no `isValid` boolean by design; you derive the verdict your use case needs from the returned lists.

```kotlin
import io.lightine.tessera.mrz.validation.MrzValidator

val report = MrzValidator.validate(document) // document: any MrzDocument

report.validationFailures.forEach(::println) // typed non-conformance, e.g. MrzCheckDigitMismatch(...)
report.warnings.forEach(::println)           // typed anomalies that are not hard failures

// Derive the verdict yourself — the SDK does not pre-decide:
val conformant = report.validationFailures.isEmpty()
```

---

## Behavioral Commitments

The validator commits to the following behaviors. These are part of the public contract.

### Always Returns Complete Results

Every validator that can be run is run, regardless of what previous validators found. The only exceptions are layering dependencies: if structural validation fails, check digit validation cannot run because field positions are not known. In such cases, the structural failures are returned and the unrun layers report no results (with explicit indication that they did not execute).

### Deterministic Within a Time Frame

Validation is deterministic given the same input and same execution time. Some semantic checks depend on the current date (for example, "is this expiry date in the past" — a warning). These are explicitly time-dependent; the validator documents which checks are time-dependent and the consumer can elect to pass an explicit reference time for fully deterministic behavior.

### Safe to Call Concurrently

The validator is stateless. Multiple invocations can run concurrently in any threading or async model the target language supports.

### Same Validators, Same Results

A check digit failure detected by the parser and a check digit failure detected by standalone validation produce the same typed error with the same context. This consistency is a structural commitment of the single-source-of-truth design.

### No Refusal Based on Validation Result

Validation never refuses to return a result. There is always a `ValidationResult` to inspect. If validation could not run at all (an internal error, not a validation failure), that is signaled through a generic SDK-level error, not the validation result.

---

## Date Range Conventions

Several validators check whether dates fall within plausible ranges. The conventions used:

- **Date of birth** — components that imply an age greater than 130 years at every candidate century interpretation produce a warning (`MrzBirthDateImplausiblyOld`). The 130-year threshold matches `MrzDate.MAX_PLAUSIBLE_AGE_YEARS` (the parser's age-rejection cap) so the warning fires only when the parser falls to `RAW_ONLY` for age. The implicit "must be in the past" expectation is enforced earlier in the parser's century-pick step rather than as a validator finding.
- **Date of expiry** — checked against the reference time; an expiry in the past produces a warning (`MrzExpiryDatePast`); an expiry far in the future produces a different warning (`MrzExpiryDateImplausiblyFar`, with the threshold documented in the warning)
- **Issuance dates** (where applicable) — must be in the past, and the gap to expiry must be plausible

These thresholds are intended to be configurable through the validator's options, with the documented defaults applied when no configuration is provided. The configuration surface itself is deferred (see `docs/open-questions.md` "Validator options (configurable thresholds)"); the current slice ships the thresholds as constants matching the documented defaults.

### Century Inference Is the SDK's Heuristic

ICAO Doc 9303 Part 3 §4.8 mandates the YYMMDD format for MRZ date fields but does not mandate any inference rule for which century a two-digit year belongs to. The sliding-window approach the parser uses — `SLIDING_WINDOW_BIRTH` (candidate must be in the past relative to the reference time and within 130 years), `SLIDING_WINDOW_EXPIRY` (10 years past to 50 years future relative to the reference time), and the resulting `RAW_ONLY` fallback when no candidate century fits — is entirely the SDK's design decision per [ADR-008](../decisions/0008-date-inference-hybrid.md). The thresholds above (`MAX_PLAUSIBLE_AGE_YEARS = 130`, `EXPIRY_PAST_WINDOW_YEARS = 10`, `EXPIRY_FUTURE_WINDOW_YEARS = 50`) are project choices, not specification numbers. Consumers who need different windowing semantics inspect the raw two-digit components on `MrzDate` and apply their own rule (Principle 1 — reader, not oracle: the SDK exposes both raw and computed values so consumers can decide which to use).

---

## What Validation Does Not Do

Validation is bounded. The validator does not:

- Verify that an issuing state actually issues documents in the format presented
- Verify that a document number exists in any external registry
- Verify that the holder's name matches some external record
- Make trust decisions on behalf of the consumer
- Apply business rules specific to a particular use case

These concerns are outside the scope of MRZ validation. The validator answers "does this MRZ conform to the specification?" — not "is this a real, valid, trustworthy document?" The latter requires external verification (backend lookups, cryptographic chip verification, etc.) that is the consumer's responsibility (Principle 1 — Reader, not oracle).

---

## Relationship to Other Features

- **Data model** (`mrz-data-model.md`) — the validator accepts and produces values defined there
- **Error taxonomy** (`mrz-error-taxonomy.md`) — the validation failures and warnings the validator produces are defined there
- **Parsing** (`mrz-parsing.md`) — the parser invokes validation internally; results appear in the parser's metadata
- **Generation** (`mrz-generation.md`) — the generator invokes input validation before producing output
- **Lookup tables** (`lookup-tables.md`) — the validator references these for code recognition checks
- **Conventions** (`conventions.md`) — naming and API patterns referenced in this document

---

## Edge Cases Worth Calling Out

A few cases that deserve explicit mention:

### Date Comparison Without an Explicit Reference Time

When the consumer does not specify a reference time, the validator uses the current system time. This is convenient but introduces non-determinism for time-dependent checks. For consumers requiring deterministic results (test environments, replay scenarios, audit pipelines), passing an explicit reference time produces fully deterministic output.

### Recognition vs Conformance

A country code or document type code that is not in the SDK's lookup tables is not necessarily non-conformant — ICAO updates these lists periodically, and a code may be valid but newer than the SDK's tables. The validator distinguishes between "well-formed but unrecognized" (a warning) and "structurally invalid" (a failure). Consumers who require strict recognition can treat the warning as disqualifying; consumers who are more lenient can ignore it.

### Cross-Field Consistency

ICAO Doc 9303 includes a composite check digit that covers multiple fields jointly. A composite check digit failure may indicate corruption in one of several places. The validator reports the failure but does not attempt to identify which specific field is the cause; that diagnosis is left to per-field check digit results.

### Validating Already-Parsed Documents

When validating an `MrzDocument` (rather than a string), the structural layer has implicitly been passed — the document exists, so its structure was acceptable when it was parsed. Standalone validation of a document focuses on check digit verification (against the raw string preserved in the document) and semantic checks. This is the natural pattern for round-trip flows or stored-and-revalidated scenarios.

---

## Related Principles

- **Principle 1 (Reader, not oracle)** — validation does not make trust decisions; it reports findings, the consumer decides
- **Principle 5 (Transparency)** — every validator that runs is reported, with full context; no summarization, no hidden filtering
- **Principle 7 (Fail loudly, fail informatively)** — typed failures and warnings, never generic; full context for each finding
- **Principle 9 (Forward-compatible API)** — the validation result type is designed to extend through addition of new error and warning types

---

## Related Documents

- `principles.md` — the foundational principles this document references
- `mrz-data-model.md` — `ValidationResult` and the types it contains
- `mrz-error-taxonomy.md` — validation errors and warnings
- `mrz-parsing.md` — implicit validation during parsing
- `mrz-generation.md` — implicit validation during generation
- `lookup-tables.md` — code recognition checks
- `conventions.md` — naming and API patterns
