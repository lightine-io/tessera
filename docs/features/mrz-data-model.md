# MRZ Data Model

This feature document describes the data model used to represent Machine Readable Zone (MRZ) content within the SDK. It defines the shape of types that the parser produces, the generator consumes, and validation reports against. It is a foundational document — most other feature documents in this release reference it.

This document describes *how the SDK models* MRZ data, not *what MRZ data is*. For the byte-level format specifications, refer to ICAO Doc 9303 (the canonical standard). This document focuses on the SDK-specific design choices: the type hierarchy, field representations, and the contracts that other features rely on.

**Status:** Living
**Available since:** 0.1.0
**Platform availability:** Target-agnostic. The data model is pure logic and runs on every target the project supports.

---

## Purpose

A consistent, well-typed representation of MRZ content is the foundation of everything the SDK does. Parsing produces values of these types. Generation accepts them as input. Validation operates on them. Higher-level features (camera reading, NFC reading, manual entry) all converge on this data model.

The data model is designed around the project's principles:

- Every field that exists in the MRZ is exposed (Principle 5 — Transparency)
- Field values are represented verbatim, including anomalies (Principle 1 — Reader, not oracle)
- The model evolves through additive change, never breaking changes within a major version (Principle 9 — Forward-compatible)
- Types are specific and exhaustive (Principle 7 — Fail loudly, fail informatively)

---

## High-Level Shape

The model is built around a sealed type hierarchy: a top-level `MrzDocument` type with one variant per ICAO Doc 9303 format. Each variant exposes the fields specific to its format, plus a set of common fields shared across all formats.

The illustrative shape in Kotlin-like syntax (the actual implementation may differ in detail):

```
sealed class MrzDocument {
    abstract val rawLines: List<String>
    abstract val format: MrzFormat
    abstract val commonFields: CommonFields
}

data class TD1(...) : MrzDocument()
data class TD2(...) : MrzDocument()
data class TD3(...) : MrzDocument()
data class MrvA(...) : MrzDocument()
data class MrvB(...) : MrzDocument()
```

The sealed hierarchy ensures that consumers using exhaustive matching (`when` in Kotlin, `switch` in Swift) cover every variant the SDK supports. Adding a new variant is a non-breaking change when done correctly (per Principle 9 — exhaustive matching produces compile-time warnings, not errors, with proper API design).

The `rawLines` property always exposes the original MRZ as it was read. This is a transparency commitment: regardless of how data was extracted or interpreted, the consumer can always reach the original.

---

## Format Enumeration

The `MrzFormat` enum names the formats the SDK supports:

- `TD1` — three-line, 30-character MRZ on credit-card-sized documents
- `TD2` — two-line, 36-character MRZ on mid-sized documents
- `TD3` — two-line, 44-character MRZ on passport booklets
- `MRV_A` — two-line, 44-character MRZ on large machine-readable visas
- `MRV_B` — two-line, 36-character MRZ on smaller machine-readable visas

Each variant of `MrzDocument` corresponds to one format. The `format` property is convenient for code that handles all formats uniformly without sealed-class matching.

---

## Common Fields

Most fields appear across all ICAO Doc 9303 formats, though their position within the MRZ differs. The `CommonFields` aggregate exposes these shared fields:

- **`documentType`** — `DocumentType` value class wrapping the document type code (one or two characters from ICAO Doc 9303)
- **`issuingState`** — `CountryCode` value class wrapping the three-letter issuing state or organization code
- **`primaryIdentifier`** — `String` representing the holder's surname (or analogous primary identifier)
- **`secondaryIdentifier`** — `String` representing the holder's given names (or analogous secondary identifier)
- **`nameTruncated`** — `Boolean` indicating whether the name field was truncated to fit the MRZ (per ICAO Doc 9303 truncation rules)
- **`rawNameField`** — `String` containing the unparsed name field exactly as it appeared in the MRZ, including filler characters
- **`documentNumber`** — `String` containing the document number, exactly as encoded (may contain letters and digits)
- **`nationality`** — `CountryCode` value class wrapping the holder's nationality code
- **`dateOfBirth`** — `MrzDate` (see "Dates" below)
- **`sex`** — `Sex` enum (`MALE`, `FEMALE`, `UNSPECIFIED`)
- **`dateOfExpiry`** — `MrzDate`
- **`checkDigits`** — `MrzCheckDigits` aggregate exposing the check digit values present in the MRZ (see "Check Digits" below)

Each field is exposed verbatim: a misspelled name remains misspelled, an unexpected document type code remains unexpected, an empty optional field is exposed as empty.

---

## Format-Specific Fields

Each format has fields that do not appear in the common set, or that appear in different positions:

### TD1

- **`optionalData1`** — `String` from line 1, optional data field (typically used by issuing state for personal identification numbers, internal reference numbers, etc.)
- **`optionalData2`** — `String` from line 2, secondary optional data field

### TD2

- **`optionalData`** — `String` from line 2, optional data field

### TD3

- **`personalNumber`** — `String` from line 2, often used for national identification numbers; is the optional data field in TD3 terminology
- **`personalNumberCheckDigit`** — `Char` exposed separately because some issuing states leave it as `<` (filler) when the personal number is unused, and consumers may want to see this directly

### MRV-A and MRV-B

- **`optionalData`** — `String`, typically smaller than TD3's personal number field; visa-specific contents

The split between common and format-specific fields is documented per format, with reference to the relevant Part of ICAO Doc 9303 (Part 4 for TD3, Part 5 for TD1, Part 6 for TD2, Part 7 for MRV-A and MRV-B).

---

## Sub-Types Used by the Model

### `CountryCode`

A value class wrapping a three-letter code. It does not validate against a list of known codes at construction time — the parser exposes whatever code was in the MRZ, even if it is unrecognized. Validation against the country code lookup table is a separate step (see `mrz-validation.md`).

The class exposes:

- The raw three-letter string
- A boolean indicating whether the code is recognized in the SDK's lookup tables
- The full country name (when recognized; null otherwise)

This honors Principle 5: the raw value is always exposed, recognition is additive metadata.

### `DocumentType`

A value class wrapping a one or two-character document type code. Both legacy single-character codes (e.g., `P` for passport) and current two-character codes (e.g., `PP`, `PD`, `PS`) are supported.

The class exposes:

- **`rawCode`** — the document type code with trailing MRZ filler character `<` stripped. A single-character code `P` followed by filler in the second position appears as `rawCode = "P"`; a two-character code `PP` appears verbatim as `rawCode = "PP"`; a fully empty slot (`<<`) appears as `rawCode = ""`. Trailing filler is treated as structural (it marks the end of the code, not part of the code itself), parallel to how dates expose `rawYear` / `rawMonth` / `rawDay` without the surrounding two-character framing. Leading filler — which the ICAO Doc 9303 specification does not contemplate for the document type slot — is preserved as-is (e.g., a malformed slot `<P` becomes `rawCode = "<P"`); the parser does not auto-correct (Principle 1). Recognition lookup is keyed by `rawCode` exactly, so the lookup table contains the un-filler forms (`P`, `PP`, etc.) and an empty `rawCode` is unrecognized. Consumers who need the verbatim two-character slot including filler can read it from `commonFields.rawLines` at the format's documented document-type position.
- A boolean indicating whether the code is recognized
- A categorical interpretation (`PASSPORT`, `IDENTITY_CARD`, `RESIDENCE_PERMIT`, `VISA`, `OTHER`) when recognized

### `Sex`

An enum: `MALE` (M), `FEMALE` (F), `UNSPECIFIED` (`<` or `X`). The MRZ filler character `<` and the explicit `X` are both treated as unspecified per ICAO Doc 9303 convention.

If the MRZ contains a value outside this enum (an OCR error, a non-conforming document), parsing surfaces this as a validation failure rather than crashing or guessing. The raw character is preserved on `CommonFields.rawSex` so consumers always have access to the verbatim input (per Principle 5 — Transparency), independent of how the `sex` enum field interpreted it.

### `MrzDate`

Dates in the MRZ are encoded as `YYMMDD` — two-digit year, two-digit month, two-digit day. The two-digit year requires century inference, which is technically ambiguous (year `25` could mean 1925 or 2025).

Per the SDK's design: the `MrzDate` type exposes both the raw value and a computed value, with explicit metadata about how the inference was performed.

The class exposes:

- **`rawYear`** — `String` containing the two-digit year exactly as in the MRZ
- **`rawMonth`** — `String` containing the two-digit month exactly as in the MRZ
- **`rawDay`** — `String` containing the two-digit day exactly as in the MRZ
- **`computedYear`** — `Int` representing the four-digit year as computed by the SDK's heuristic
- **`computedDate`** — a platform-appropriate date type (e.g., `LocalDate` on JVM/KMP) representing the full computed date
- **`inferenceMethod`** — an enum value describing which heuristic produced the computed year
- **`componentsFormCalendarDate`** — a `Boolean?` signal that disambiguates the `RAW_ONLY` cases. `null` when the raw components did not parse as 2-digit numerics (so the calendar question does not apply). `true` when the components form a real calendar date for at least one candidate year (covers both successful inference and dates that are calendar-valid but outside the SDK's inference window). `false` when the components parsed as numerics but no candidate year forms a real calendar date (e.g., February 30, month 13). The validator uses this signal to emit `MrzDateNotInCalendar` only for the `false` case.
- **`componentsExceedBirthAgeLimit`** — a `Boolean?` signal that is birth-specific. `true` only when `parseBirth` falls to `RAW_ONLY` because every calendar-valid past candidate exceeds the parser's age cap (`MAX_PLAUSIBLE_AGE_YEARS = 130`); `false` when `parseBirth` succeeded or when no past calendar-valid candidate exists; `null` for `parseExpiry`-produced dates, for direct-construction defaults, for non-numeric components, or when no candidate forms a calendar date. The validator uses this signal to emit `MrzBirthDateImplausiblyOld` only for the `true` case.

The supported inference methods include:

- `SLIDING_WINDOW_BIRTH` — for birth dates: assumes the most recent past century such that the date is not in the future and the implied age is plausible
- `SLIDING_WINDOW_EXPIRY` — for expiry dates: assumes the century that places the date in the future or recent past relative to the current time
- `RAW_ONLY` — when computation could not be performed (e.g., the raw values are not parseable as a date); `computedYear` and `computedDate` are null

Consumers who require deterministic century handling — for example, for backend systems where the SDK's heuristics are not appropriate — use the raw fields and apply their own logic.

This design honors Principle 1 (the raw value is always available, the computed value is explicitly labeled as inferred), Principle 5 (nothing is hidden), and Principle 4 (we are honest that the computed year is a best-effort interpretation, not a fact).

### `MrzCheckDigits`

The MRZ contains check digits at multiple positions, computed using the algorithm in ICAO Doc 9303 Part 3 (weights 7-3-1, modulus 10). The `MrzCheckDigits` aggregate exposes the values present in the MRZ:

- **`documentNumber`** — `Char` for the document number's check digit
- **`dateOfBirth`** — `Char` for the birth date's check digit
- **`dateOfExpiry`** — `Char` for the expiry date's check digit
- **`optionalData`** — `Char?` for the optional data field's check digit, present in formats that have one
- **`composite`** — `Char?` for the composite (overall) check digit. Non-null for formats that define a composite digit (TD1, TD2, TD3). Null for formats that do not (MRV-A and MRV-B per ICAO Doc 9303 Part 7), distinguishing "no slot in this format" from any literal character value

The check digits are exposed verbatim (the character that appears in the MRZ). Validation of whether they are correct — that is, whether the digit equals the computed value — is a separate concern handled by the validation feature, which produces typed validation failures when mismatches occur.

This separation matters: the data model never refuses to construct because of a check digit failure. The data model exposes what is there. Validation exposes whether what is there is consistent.

---

## Result Types

Parsing, generation, and validation return result types that combine the data model with metadata.

### `ParseResult`

```
sealed class ParseResult {
    abstract val metadata: ResultMetadata

    data class Success(val document: MrzDocument, val metadata: ResultMetadata) : ParseResult()
    data class PartialSuccess(val document: MrzDocument, val metadata: ResultMetadata) : ParseResult()
    data class Failure(val error: MrzParseError, val rawInput: String?, val metadata: ResultMetadata) : ParseResult()
}
```

- **`Success`** — the MRZ parsed cleanly and all validations of structural integrity passed
- **`PartialSuccess`** — the MRZ parsed and a document was constructed, but one or more validation failures or warnings exist (the consumer can read both the document and the metadata; per Principle 1, partial parses are still returned)
- **`Failure`** — parsing could not produce a usable document (the input was structurally too broken to construct a document at all)

The distinction between `PartialSuccess` and `Failure` is important: validation issues do not prevent parsing. Only structural impossibilities (wrong line count, characters outside the MRZ alphabet, fundamental field misalignment) lead to `Failure`.

### `GenerationResult`

Symmetric to parsing:

```
sealed class GenerationResult {
    data class Success(val mrz: List<String>, val metadata: ResultMetadata) : GenerationResult()
    data class Failure(val error: MrzGenerationError, val metadata: ResultMetadata) : GenerationResult()
}
```

Generation has no `PartialSuccess` because producing an invalid MRZ string is not an acceptable output. If the input is insufficient or contradictory, generation fails with a typed error rather than producing something invalid.

### `ValidationResult`

Validation, when called explicitly on already-parsed data, returns:

```
data class ValidationResult(
    val validationFailures: List<MrzValidationError>,
    val warnings: List<MrzWarning>,
    val passedChecks: List<ValidationCheckId>  // target shape; not yet shipped
)
```

The list of passed checks is exposed (Principle 5) so consumers can confirm what was actually verified, not just what failed. Note: the current shipped `ValidationResult` carries `validationFailures` and `warnings` only; `passedChecks` is documented as the target shape and tracked in [`docs/open-questions.md`](../open-questions.md) "`ValidationResult.passedChecks` shape." The shape of `passedChecks` (typed identifier set vs. plain string list vs. richer record) is itself an open question and will be settled when the validator catalog is broader.

---

## ResultMetadata

Every result type carries `ResultMetadata`, which captures information about how the result was produced and what observations the SDK made along the way.

```
data class ResultMetadata(
    val readMethod: ReadMethod,
    val warnings: List<MrzWarning>,
    val validationFailures: List<MrzValidationError>,
    val timing: TimingInfo?  // target shape; not yet shipped
)
```

- **`readMethod`** — `ReadMethod` enum: `LIVE_CAMERA`, `PRE_CAPTURED_IMAGE`, `MANUAL_ENTRY`, `NFC_CHIP`, `BACKEND_STRING_INPUT`, `MIXED` — see related feature docs for the implications of each
- **`warnings`** — list of typed warnings (data is valid but anomalous; e.g., `MrzExpiryDatePast`, `MrzNameTruncated`, `MrzAndChipDataMismatch`)
- **`validationFailures`** — list of typed validation failures (data extracted but does not conform to spec; e.g., `MrzCheckDigitMismatch`, `MrzUnknownCountryCode`)
- **`timing`** — optional timing breakdown for diagnostic purposes (off by default; opt-in via configuration). Not yet shipped: the current `ResultMetadata` does not include this field. It will be added in a future slice when the SDK has timing instrumentation to populate it. Tracked in the Deferred section of `CHANGELOG.md`.

The three-tier model (errors as result variants, validation failures as data, warnings as metadata) is described further in the error taxonomy feature document.

---

## Design Decisions Worth Naming

A few choices in this model are non-obvious and worth recording.

**1. Sealed class hierarchy over a single flat type with optional fields.**
A flat type (one `MrzDocument` data class with all possible fields, many optional) was considered. It was rejected because exhaustive handling per format is a real consumer need (different formats have meaningfully different fields), and forcing every consumer to check `if (format == TD3) ...` everywhere is worse than sealed-class matching.

**2. Raw fields exposed alongside parsed/computed fields.**
For dates, names, and country codes, both raw and processed forms are exposed. This is more API surface but is consistent with Principle 5 (transparency) and Principle 1 (verbatim extraction).

**3. Validation results are not embedded inside the document.**
The `MrzDocument` itself does not contain validation results — those live on `ResultMetadata`. The reasoning: a `MrzDocument` is a representation of *what was extracted*, not *whether the extraction passed validation*. These are different concerns and deserve different types.

**4. No `isValid` boolean.**
A common convenience would be a single `isValid` property on the result. This is deliberately not provided. Validity depends on what the consumer cares about — some consumers reject any check digit failure, some accept warnings as long as data is structurally usable. The SDK does not pre-decide what validity means (Principle 1).

**5. Country codes and document type codes are value classes, not enums.**
Enums would limit consumers to known codes and force breaking changes when ICAO updates the lists. Value classes accept any three-letter or one-to-two-character string and expose recognition as a separate property. New codes are non-breaking additions to the lookup tables.

---

## Relationship to ICAO Doc 9303

This data model represents what ICAO Doc 9303 specifies, restructured for the SDK's purposes. Specifically:

- Field positions, lengths, and check digit algorithms are defined by ICAO Doc 9303 Parts 3-7 (Part 3: common; Part 4: passports/TD3; Part 5: TD1; Part 6: TD2; Part 7: visas)
- Country codes follow ISO 3166-1 alpha-3 with extensions defined in Doc 9303 Part 3 Section 5
- Document type codes are defined in Doc 9303 Part 3 §4 (general framing) and Part 4 §4.4 (the harmonized two-character `P`-prefix set, mandated for new MRPs from 1 January 2026 and for all MRPs from 1 January 2028). Part 5 Appendix B adds the `AC` Crew Member Certificate code
- Check digit algorithm is defined in Doc 9303 Part 3 Appendix A
- Truncation rules for names are defined per Part (TD3 in Part 4, TD1 in Part 5, etc.)

The data model exposes what these specifications describe, in a form usable from code. It does not reproduce the specifications.

---

## Related Principles

- **Principle 1 (Reader, not oracle)** — verbatim extraction of every field; raw values exposed alongside any computed interpretation
- **Principle 5 (Transparency over magic)** — every extracted value is reachable; nothing computed-and-thrown-away
- **Principle 7 (Fail loudly, fail informatively)** — typed result variants distinguish success from partial success from failure
- **Principle 9 (Forward-compatible API)** — sealed hierarchy and value classes designed to evolve through addition

---

## Related Documents

- `principles.md` — the foundational principles this document references
- `mrz-error-taxonomy.md` — the typed errors, validation failures, and warnings referenced by this model
- `mrz-parsing.md` — how the parser produces values of these types
- `mrz-generation.md` — how the generator consumes values of these types
- `mrz-validation.md` — how validation operates on values of these types
- `lookup-tables.md` — country codes and document type codes referenced by `CountryCode` and `DocumentType`
- `transliteration.md` — name transliteration referenced by name fields in this model
