# MRZ Generation

This feature document describes the SDK's MRZ generation capability: turning structured input into a valid MRZ string. Generation is the inverse of parsing and serves three primary use cases: producing test fixtures, supporting document issuance flows, and round-trip verification.

This document focuses on the SDK-specific design choices: the public API shape, how transliteration is invoked, how field overflow is handled, and the round-trip guarantees the generator commits to. The byte-level format specifications themselves live in ICAO Doc 9303.

**Status:** Living
**Available since:** 0.1.0
**Platform availability:** Target-agnostic. Generation is pure logic and runs on every target the project supports.

---

## Status of Implementation

The generator ships incrementally, format by format, per the phased addition procedure in `conventions.md`. The design described in the rest of this document is the target shape; the table below records what is currently implemented versus what is documented but deferred.

| Capability | Status |
|---|---|
| `MrzGenerator.generate(document: MrzDocument): GenerationResult` (polymorphic dispatch over the sealed hierarchy) | Implemented |
| `MrzGenerator.generate(document: TD3): GenerationResult` | Implemented |
| `MrzGenerator.generate(document: TD2): GenerationResult` | Implemented |
| `MrzGenerator.generate(document: TD1): GenerationResult` | Implemented |
| `MrzGenerator.generate(document: MrvA): GenerationResult` | Implemented |
| `MrzGenerator.generate(document: MrvB): GenerationResult` | Implemented |
| Per-format primitive-input methods (`generateTD3(documentType, issuingState, ...)` etc.) | Implemented for all five formats; each accepts an optional `transliteration: TransliterationProfile? = null` parameter |
| `MrzGenerationFieldOverflow` error type | Implemented |
| `MrzGenerationUnsupportedCharacters` error type | Implemented; returned by both `generate(document)` (when text fields contain non-MRZ characters and the consumer has not pre-transliterated) and by the primitive-input methods (when a profile is provided but cannot map all characters) |
| `MrzGenerationNumericInNameField` error type | Implemented (primitive-input methods). Per ICAO Doc 9303 Part 3 §4.6 ("Numeric characters shall not be used in the name fields of the MRZ"), the generator rejects `primaryIdentifier` or `secondaryIdentifier` arguments containing digits with a typed error carrying the format, the observed value, and the list of numeric characters encountered. The rejection happens before any transliteration profile is applied so the error references the consumer's original input. Pre-tag conformance-verification finding (F4) |
| Long document number extension (TD3) | Deferred — `>9`-character document numbers currently fail with `MrzGenerationFieldOverflow` rather than spilling into the personal-number field |
| Transliteration via `TransliterationProfile` parameter | Implemented on the primitive-input methods. Profiles are applied to primary and secondary identifiers; the post-normalization and post-transliteration forms are exposed on `ResultMetadata.transliterationDetails` per Principle 5 ([ADR-014](../decisions/0014-unicode-normalization-strategy.md)) |
| Round-trip property tests (`parse ∘ generate = identity` on raw fields) | Implemented for all five formats. TD3 has the most thorough coverage (custom-date variant, two-character document type code, sex character verbatim round-trip); TD1/TD2/MRV-A/MRV-B share the canonical Anna Eriksson specimen pattern with per-format edge cases |

The generator's wiring into the parser is implicit: a `MrzGenerator.generate(document)` followed by the matching `MrzParser.parseXXX(generated.mrz)` round-trips the raw fields verbatim. The polymorphic `generate(MrzDocument)` overload pairs cleanly with the auto-detect parser entry point: `MrzGenerator.generate(MrzParser.parse(input).document)` is a typed end-to-end round-trip. Check digits are recomputed by the generator from the field data on every call — the check-digit values on the input `MrzDocument` are not used (strict ICAO conformance per Principle 7 over faithful round-trip of bad inputs).

---

## Purpose

The generator produces a syntactically valid MRZ string from structured input. It computes check digits, applies field padding, and produces output that conforms to ICAO Doc 9303 for the requested format. If the input cannot produce a conformant output, the generator fails with a typed error rather than producing invalid output silently (Principle 7 — Fail loudly, fail informatively).

Generation enables several capabilities that pure parsing cannot:

- Constructing test fixtures programmatically — synthetic MRZs covering edge cases, used in unit tests and integration tests
- Supporting document issuance flows where structured data must be encoded into a valid MRZ for printing
- Round-trip verification — generating an MRZ from a parsed document and confirming the result matches the original input

The generator is always paired with the parser. The data model defined in `mrz-data-model.md` is the canonical type both operate on.

---

## What Generation Does

Given valid structured input for a specific format, the generator:

1. Validates that the input fits the field widths defined for the format
2. Applies transliteration to name fields if a transliteration profile is provided
3. Encodes each field per ICAO Doc 9303 rules for the format
4. Computes check digits per the algorithm in Doc 9303 Part 3 Appendix A
5. Pads fields with the filler character `<` as required
6. Returns the produced MRZ as a list of strings (one per MRZ line)

What the generator does *not* do:

- Guess what locale or transliteration profile the consumer wants
- Silently truncate input that does not fit the field width (it fails with a typed error, with one exception — see "Long Document Number" below)
- Apply transliteration unless explicitly directed to do so
- Produce non-conformant output ("best effort" generation is not supported)

---

## Public API Shape

The generator exposes per-format methods. Each format has its own signature reflecting the fields specific to that format. There is no single `generate(format, ...)` method because format-specific signatures are clearer and safer.

The illustrative shape:

```
object MrzGenerator {
    // Per-format methods accepting primitive inputs
    fun generateTD1(
        documentType: String,
        issuingState: String,
        documentNumber: String,
        // ... TD1-specific fields
    ): GenerationResult

    fun generateTD3(
        documentType: String,
        issuingState: String,
        documentNumber: String,
        primaryIdentifier: String,
        secondaryIdentifier: String,
        nationality: String,
        dateOfBirth: MrzDateInput,
        sex: Sex,
        dateOfExpiry: MrzDateInput,
        personalNumber: String,
        // ... optional transliteration profile
        transliteration: TransliterationProfile? = null
    ): GenerationResult

    // ... analogous methods for TD2, MRV-A, MRV-B

    // Per-format methods accepting the data model directly
    fun generate(document: TD1): GenerationResult
    fun generate(document: TD2): GenerationResult
    fun generate(document: TD3): GenerationResult
    fun generate(document: MrvA): GenerationResult
    fun generate(document: MrvB): GenerationResult
}
```

Both input forms are supported because both are natural in different consumer contexts:

- **Primitive inputs** are used when constructing an MRZ from scratch — typical of test fixtures, document issuance flows, and consumers who do not have a parsed `MrzDocument` instance
- **Data model inputs** are used in round-trip flows — parsing an MRZ, modifying the result, and generating a new MRZ from the modified document

Per-format methods exist because each format has meaningfully different required fields. A single method with a giant union of all possible fields would be confusing and error-prone. Per-format signatures give each format a clean, type-safe contract.

The actual class names, method names, and parameter shapes are decided at implementation time. The shape above is illustrative.

---

## Usage

The shape above is illustrative; the following example compiles against the shipped API. Generation is the inverse of parsing — `parse(generate(document))` round-trips at the raw-field level — so a common flow is to take a parsed `MrzDocument`, modify it, and re-encode it. There is no `PartialSuccess`: generation either produces a valid MRZ or returns a typed `Failure` (e.g. a field overflowed its fixed width).

```kotlin
import io.lightine.tessera.mrz.generation.MrzGenerator
import io.lightine.tessera.mrz.generation.GenerationResult

// `document` is any MrzDocument — e.g. one obtained from MrzParser (then optionally modified)
// or built directly for an issuance / test-fixture flow.
when (val result = MrzGenerator.generate(document)) {
    is GenerationResult.Success -> {
        val mrz: String = result.mrz.joinToString("\n") // the encoded MRZ lines
        println(mrz)
    }
    is GenerationResult.Failure -> {
        // A field overflowed its fixed width, or a value could not be encoded — never thrown.
        println(result.error)
    }
}
```

---

## Source of Truth for Format Definitions

Format specifications — field positions, field widths, check digit positions, padding rules — live in a single shared definition within `mrz-core`. The parser, generator, and validator all reference this source rather than each maintaining their own copy. Changing a definition in one place updates all three subsystems consistently.

This is an internal architectural commitment that supports Principle 3 (Modular) and Principle 9 (Forward-compatible API): if ICAO updates a format specification, the change happens in one place. Adding a new format follows a documented procedure (see `conventions.md`).

---

## Input Validation

Before producing any output, the generator validates that the input can produce a conformant MRZ. This includes:

- Required fields are present and non-empty (where required)
- Field values fit within the defined field widths
- Country codes and document type codes are within the allowed character set (the recognition against lookup tables is informational, not a generation gate; an unrecognized but well-formed code is accepted)
- Date fields parse to real calendar dates
- Sex values are within the allowed set (`MALE`, `FEMALE`, `UNSPECIFIED`)
- Names contain only characters in the MRZ alphabet (A-Z and the filler), unless a transliteration profile is provided
- Names do not contain numeric characters — per ICAO Doc 9303 Part 3 §4.6, "numeric characters shall not be used in the name fields of the MRZ". The primitive-input methods enforce this on the `primaryIdentifier` and `secondaryIdentifier` arguments before any transliteration profile is applied, and emit `MrzGenerationNumericInNameField` if violated. The SDK does not silently strip digits (Principle 1 — reader, not oracle)

Validation failures at this stage produce typed errors and the generator does not produce output. The consumer receives `GenerationResult.Failure` with a specific error type indicating what was wrong.

---

## Transliteration Behavior

Names in the MRZ must use only the restricted MRZ alphabet (uppercase A-Z and the filler character). Real-world names often contain characters outside this set: diacritics, characters from non-Latin scripts, ligatures, and Latin Extended-B characters (such as the schwa, which is outside Annex G's table — see [`transliteration.md`](transliteration.md) and [ADR-009](../decisions/0009-transliteration-profiles.md) for the full story).

The generator handles this through explicit consumer choice. Two paths are supported:

### Path 1 — Pre-Transliterated Input

The consumer transliterates names themselves before calling the generator. The input strings already contain only MRZ-alphabet characters. The generator accepts them directly and produces output. No transliteration profile is needed.

This path is used when the consumer has its own transliteration logic, or when the names are already in MRZ form (for example, in round-trip flows where the names came from a parsed MRZ).

### Path 2 — Generator-Applied Transliteration

The consumer passes original names (which may contain non-MRZ-alphabet characters) along with a `TransliterationProfile`. The generator applies the profile to produce MRZ-compatible names, then encodes them.

This path is used when the consumer wants the SDK to handle transliteration. The profile must be specified explicitly — the generator never guesses which profile applies based on issuing state or any other input. This is consistent with Principle 1 (Reader, not oracle): the SDK does not infer locale.

If the consumer provides a name with non-MRZ-alphabet characters and no profile, the generator returns a typed error (`MrzGenerationUnsupportedCharacters`). The consumer must either pre-transliterate the name or provide a profile.

---

## Long Document Number Extension

ICAO Doc 9303 Part 4 (TD3 / passports) defines an extension for document numbers that exceed 9 characters: excess characters spill into the personal number field, with a specific marker indicating the overflow. This is the "long document number" extension, and it appears in real-world passports.

The TD3 generator implements this extension correctly. When the consumer provides a document number longer than 9 characters, the generator:

1. Places the first 9 characters in the document number field
2. Places the remaining characters at the start of the personal number field
3. Inserts the appropriate marker character to indicate the overflow
4. Adjusts the personal number length accordingly
5. Computes check digits over the resulting layout

The extension is supported only for TD3, because that is the only format where ICAO defines it. For TD1, TD2, MRV-A, and MRV-B, document numbers exceeding the field width produce a typed error (`MrzGenerationFieldOverflow`).

---

## Round-Trip Guarantees

The generator commits to round-trip equality at the raw-field level: parsing a generator-produced MRZ yields a `MrzDocument` whose raw fields match the input data passed to the generator.

Specifically:

- A generator call with a `TD3` input produces an MRZ that, when parsed, yields a `TD3` with raw fields equal to the original
- A generator call with primitive inputs produces an MRZ that, when parsed, yields a `MrzDocument` with the same raw fields as the inputs

The "raw fields" qualifier matters. Computed fields (like `MrzDate.computedYear`) are derived from raw fields and the current time; they are deterministic given the same time, but not part of the round-trip contract because they may change with passing time. The contract is on raw values: if you put `25` as the year, the round-tripped MRZ produces `25` as the raw year.

This guarantee is what makes the generator usable as a testing oracle. A property-based test can generate random valid inputs, run them through generation and back through parsing, and assert equality of raw fields. Any divergence indicates a bug in either the generator or the parser.

---

## Behavioral Commitments

The generator commits to the following behaviors. These are part of the public contract.

### Strict Conformance

Output is strictly conformant to ICAO Doc 9303 for the requested format. The generator does not produce best-effort or "almost valid" MRZs. Any input that cannot produce conformant output results in a typed error.

### Deterministic Output

Generation is deterministic. The same inputs always produce the same MRZ string, regardless of when or where the generator is invoked. There is no time-dependent behavior in generation (unlike parsing's date inference); the generator works entirely with the raw values it is given.

### Safe to Call Concurrently

The generator is stateless. Multiple invocations can run concurrently in any threading or async model the target language supports.

### No Refusal Based on Recognition

The generator validates that field values fit and that codes are well-formed. It does not refuse based on whether codes are recognized in the lookup tables. A consumer generating an MRZ with an unusual but well-formed country code (such as a future code not yet in our tables) is supported.

---

## Relationship to Other Features

- **Data model** (`mrz-data-model.md`) — the generator accepts and produces values of types defined there
- **Error taxonomy** (`mrz-error-taxonomy.md`) — the errors the generator produces are defined there
- **Parsing** (`mrz-parsing.md`) — the inverse operation; round-trip equality is the joint contract
- **Validation** (`mrz-validation.md`) — the generator performs structural validation as part of its work; deeper validation can be invoked separately on already-generated output
- **Lookup tables** (`lookup-tables.md`) — used informationally by the generator for code recognition
- **Transliteration** (`transliteration.md`) — invoked by the generator when a profile is provided

---

## Edge Cases Worth Calling Out

A few cases that deserve explicit mention:

### Empty Optional Fields

When a format defines an optional field (such as TD3's personal number or TD1's optional data fields) and the consumer does not provide a value, the generator fills the field with the appropriate filler characters and computes the check digit accordingly. This is correct ICAO conformance, not an error.

### Sex Field Encoding

The `Sex.UNSPECIFIED` value can be encoded as either the filler character `<` or as `X` per ICAO Doc 9303. The generator uses `<` by default, consistent with most issuing states. Future configuration may allow choosing `X` explicitly; for now, `<` is used.

### Date Encoding

Generation accepts dates in their full four-digit-year form (a `LocalDate` or platform-equivalent). The generator extracts the last two digits of the year for MRZ encoding. This avoids the century inference ambiguity that affects parsing — generation always knows the full year because the consumer provides it.

### Composite Check Digit

The composite check digit (where defined for the format) is computed over multiple fields. The generator computes it correctly per the format's specification. This is a structural commitment; consumers do not need to think about composite check digits.

---

## Related Principles

- **Principle 1 (Reader, not oracle)** — applies inversely: the generator does not invent. It encodes what the consumer provides, fails when it cannot, and never guesses transliteration profiles
- **Principle 5 (Transparency)** — the generator's behavior is fully predictable from documented inputs; output structure exactly reflects what the consumer asked for
- **Principle 7 (Fail loudly, fail informatively)** — typed errors for any condition where conformant output cannot be produced
- **Principle 9 (Forward-compatible API)** — per-format methods are designed to extend through addition; new formats add new methods without changing existing ones

---

## Related Documents

- `principles.md` — the foundational principles this document references
- `mrz-data-model.md` — the types the generator accepts and produces
- `mrz-error-taxonomy.md` — the errors the generator surfaces
- `mrz-parsing.md` — the inverse operation
- `mrz-validation.md` — additional validation invokable on already-generated output
- `lookup-tables.md` — codes referenced by the generator
- `transliteration.md` — profiles the generator can apply
- `conventions.md` — the procedure for adding new formats
