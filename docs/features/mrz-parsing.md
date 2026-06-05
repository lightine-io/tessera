# MRZ Parsing

This feature document describes the SDK's MRZ parsing capability: turning a raw MRZ string into the structured representation defined by the data model. Parsing is the most-used operation the SDK exposes; it is where most consumer integrations begin.

This document focuses on the SDK-specific design choices: the public API shape, behavioral commitments, accepted input forms, and how parsing relates to validation and error reporting. The byte-level format specifications themselves live in ICAO Doc 9303.

**Status:** Living
**Available since:** 0.1.0
**Platform availability:** Target-agnostic. Parsing is pure logic and runs on every target the project supports — including backend services, where the parser is invoked directly on strings without any I/O layer.

---

## Purpose

The parser converts a raw MRZ string into a typed, structured representation. It does not judge whether the data is correct; it extracts what is there, faithfully, with all anomalies preserved (Principle 1 — Reader, not oracle).

Parsing is the entry point that downstream features build on. A consumer who reads an MRZ from a camera, an NFC chip, manual entry, or a backend pipeline ultimately invokes the parser. The parser produces a `ParseResult`, which the consumer then uses according to their own logic.

---

## What Parsing Does

Given a syntactically plausible MRZ input, the parser:

1. Identifies the MRZ format (TD1, TD2, TD3, MRV-A, MRV-B) — either automatically or as instructed by the caller
2. Splits the input into the fields defined by ICAO Doc 9303 for that format
3. Extracts each field verbatim into the appropriate type within the data model
4. Computes derived values where the model defines them (for example, the `MrzDate` computed-year inference)
5. Records any structural validation failures and warnings observed during extraction
6. Returns a `ParseResult` carrying the extracted data and metadata

What the parser does *not* do:

- Decide whether the data is "good enough" for the consumer's purpose
- Modify, correct, or normalize values that look wrong
- Perform deep semantic validation that requires external lookups (this is the validation feature's responsibility)
- Strip fields or hide data based on assumptions about consumer needs

---

## Status of Implementation

The parser ships incrementally. The design described in the rest of this document is the target shape; the table below records what is currently implemented versus what is documented but deferred. Each deferred item has a corresponding entry in `docs/open-questions.md` or in the Deferred section of `CHANGELOG.md`.

| Capability | Status |
|---|---|
| `MrzParser.parseTD1(input: String, referenceTime: Instant)` and `parseTD1(input: List<String>, referenceTime: Instant)` overloads | Implemented |
| `MrzParser.parseTD2(input: String, referenceTime: Instant)` and `parseTD2(input: List<String>, referenceTime: Instant)` overloads | Implemented |
| `MrzParser.parseTD3(input: String, referenceTime: Instant)` and `parseTD3(input: List<String>, referenceTime: Instant)` overloads | Implemented |
| `MrzParser.parseMRVA(input: String, referenceTime: Instant)` and `parseMRVA(input: List<String>, referenceTime: Instant)` overloads | Implemented |
| `MrzParser.parseMRVB(input: String, referenceTime: Instant)` and `parseMRVB(input: List<String>, referenceTime: Instant)` overloads | Implemented |
| `MrzParser.parse(input: String, referenceTime: Instant)` and `parse(input: List<String>, referenceTime: Instant)` overloads (auto-detect) | Implemented |
| `MrzFormatNotDetected` error type (surfaced by auto-detect when input shape matches no supported format) | Implemented |

The format-specific methods accept an explicit `referenceTime: Instant` parameter (defaulting to `Clock.System.now()`) so consumers can pass a deterministic reference time for testing, replay, or audit scenarios. The illustrative API shape below omits the `referenceTime` parameter for brevity; the shipped signatures all include it.

---

## Public API Shape

The parser exposes two patterns for invocation: auto-detect and format-specific. Auto-detect is the friendly default; format-specific is for consumers who know the document type and want strict behavior.

The illustrative shape (Kotlin-flavored, marked illustrative not authoritative):

```
object MrzParser {
    // Auto-detect: parser identifies format from input
    fun parse(input: String): ParseResult
    fun parse(input: List<String>): ParseResult

    // Format-specific: caller declares the expected format
    fun parseTD1(input: String): ParseResult
    fun parseTD1(input: List<String>): ParseResult
    fun parseTD2(input: String): ParseResult
    fun parseTD2(input: List<String>): ParseResult
    fun parseTD3(input: String): ParseResult
    fun parseTD3(input: List<String>): ParseResult
    fun parseMRVA(input: String): ParseResult
    fun parseMRVA(input: List<String>): ParseResult
    fun parseMRVB(input: String): ParseResult
    fun parseMRVB(input: List<String>): ParseResult
}
```

Both forms exist so consumers can use the one that matches their situation. A consumer reading an unknown document calls `parse(input)`. A consumer building a passport-only flow calls `parseTD3(input)` and gets a typed error if the input is something else.

The actual class name, method names, and visibility are decided at implementation time. The shape above is illustrative.

---

## Usage

The shape above is illustrative; the following example compiles against the shipped API. `parse` never throws on malformed input — structural problems come back as `ParseResult.Failure` and validation problems as `ParseResult.PartialSuccess`, so the consumer always decides what to do with them.

```kotlin
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.mrz.model.TD3

val result = MrzParser.parse(
    """
    P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<
    L898902C36UTO7408122F1204159ZE184226B<<<<<10
    """.trimIndent(),
)

when (result) {
    is ParseResult.Success -> {
        // Clean parse; the validator surfaced no failures (warnings may still be in metadata).
        val passport = result.document as TD3
        println(passport.commonFields.documentNumber) // "L898902C3" — verbatim, never corrected
    }
    is ParseResult.PartialSuccess -> {
        // Parsed structurally, but validation failed on one or more fields. The document is
        // still populated — you decide whether to trust it given the failures.
        result.metadata.validationFailures.forEach(::println)
    }
    is ParseResult.Failure -> {
        // Wrong shape, characters outside the MRZ alphabet, or no recognized format.
        println(result.error)
    }
}
```

To parse a known document type strictly — a typed error if the input is something else — call the format-specific entry point instead, e.g. `MrzParser.parseTD3(input)`.

---

## Auto-Detect Behavior

When `parse(input)` is called without a specified format, the parser identifies the format using structural cues:

- Line count and per-line length narrow the candidates (three lines of 30 characters → TD1; two lines of 36 → TD2 or MRV-B; two lines of 44 → TD3 or MRV-A)
- The document type code in the first character(s) of line 1 disambiguates between formats with the same line dimensions (e.g., TD3 vs MRV-A both have two lines of 44 characters; visa codes start with `V` while passport codes start with `P` or two-letter codes such as `PP`, `PD`, `PS`, etc.)

If no format matches the structural cues, the parser returns a `ParseResult.Failure` with an `MrzFormatNotDetected` error (see `mrz-error-taxonomy.md`).

Auto-detect is conservative: it does not guess across ambiguous structures. If structure plausibly fits multiple formats and the document type code does not disambiguate, the parser fails rather than picking arbitrarily. This is consistent with Principle 1 (Reader, not oracle): when in doubt, do not invent.

---

## Format-Specific Behavior

When `parseTD3(input)` (or analogous methods) are called, the parser does not perform format detection. It assumes the input is the declared format and proceeds. If the input is structurally inconsistent with the declared format, the parser returns `ParseResult.Failure` with an appropriate error (`MrzInvalidLength`, `MrzCharacterSetViolation`, etc.).

This stricter mode is useful when the consumer has already established the document type through other means (a UI flow that only accepts passports, a backend pipeline processing only one document type, etc.) and wants to reject mismatches.

---

## Input Forms

The parser accepts two input forms:

1. **Single string** — the MRZ as a single string with line breaks separating MRZ lines. Accepted line endings include LF (`\n`), CRLF (`\r\n`), and CR (`\r`); these are normalized internally before parsing.

2. **List of strings** — the MRZ as a list where each element is one line of the MRZ. This form is convenient when the consumer has already split the input or received it pre-split (for example, from an OCR pipeline that produces line-by-line output).

Both forms are equivalent in result. They exist as overloads because both are natural in different consumer contexts.

---

## Input Normalization

Parsing performs minimal normalization:

- **Line endings** — LF, CRLF, and CR are all treated as line separators
- **Leading and trailing empty lines** of the overall input are ignored
- **Trailing whitespace at the end of the entire input** is permitted

The parser does *not* perform other normalizations. Specifically:

- It does not strip whitespace within lines (a line with embedded spaces is treated as malformed)
- It does not change case (the MRZ alphabet is uppercase A-Z; lowercase characters are treated as a character set violation)
- It does not substitute visually similar characters (`O` for `0`, `I` for `1`, etc.); these are character set violations or, when in the MRZ alphabet, are treated as the literal character that was provided

The reasoning: the parser's job is to handle clean string input. Real-world OCR outputs that may contain whitespace or visual confusions are the concern of the camera reading feature, which preprocesses input before passing it to the parser. Keeping the parser strict makes its behavior predictable; lenient and tolerant modes can be added later as additive capabilities.

---

## Strictness

The parser operates in **strict mode**: any deviation from ICAO Doc 9303 structural conformance produces a parse error or a typed validation failure.

Strict mode means:

- Wrong line count or wrong line length is a parse error
- Characters outside the MRZ alphabet are a parse error
- Format mismatches are parse errors
- Within-format anomalies (check digit failures, unknown country codes, etc.) are validation failures, not errors — the data is still returned

Lenient mode (tolerating real-world deviations such as extra whitespace within lines, off-by-one line lengths) and tolerant mode (recovering from OCR confusions using check-digit-guided disambiguation) are not currently supported. They are additive capabilities that may be added in future releases; their absence does not constrain their addition later (Principle 9 — Forward-compatible API).

This decision keeps the parser small and predictable. A consumer who wants lenient behavior preprocesses input themselves before calling the parser.

---

## Behavioral Commitments

The parser commits to the following behaviors. These are part of the public contract.

### Verbatim Field Extraction

Every field is extracted exactly as it appears in the MRZ. A misspelled name remains misspelled. An unexpected document type code is exposed as-is. An empty optional field is exposed as empty. The parser never decides what a field "should" contain.

### No Refusal on Validation Failure

The parser distinguishes between *structural* problems (which prevent constructing a document at all) and *validation* problems (which prevent calling the document "compliant" but do not prevent extraction). Structural problems are errors; validation problems are validation failures attached to a still-returned document.

A parse with one or more validation failures returns `ParseResult.PartialSuccess`, not `Failure`. The consumer reads the document and the failures and decides how to proceed.

### Deterministic Output

Parsing is deterministic: the same input always produces the same output, regardless of when or where it is invoked. The parser does not depend on system time, network state, or any external resource.

The exception is the `MrzDate.computedYear` field, which depends on the current date when the sliding-window heuristic is applied. This is documented as time-dependent behavior, and the raw two-digit year is always exposed alongside the computed value (Principle 5 — Transparency).

### Safe to Call Concurrently

The parser is stateless. Multiple invocations can run concurrently in any threading or async model the target language supports. The parser does not maintain mutable state across invocations.

---

## Relationship to Other Features

- **Data model** (`mrz-data-model.md`) — the parser produces values of types defined there
- **Error taxonomy** (`mrz-error-taxonomy.md`) — the errors and validation failures the parser produces are defined there
- **Generation** (`mrz-generation.md`) — the inverse operation; generation produces strings that the parser can read back
- **Validation** (`mrz-validation.md`) — the parser performs structural and check-digit validation as part of its work; deeper semantic validation (against lookup tables) can be invoked separately
- **Lookup tables** (`lookup-tables.md`) — the parser uses these for country code and document type code recognition
- **Transliteration** (`transliteration.md`) — the parser does not invoke transliteration during input parsing (input is already in MRZ form); transliteration is invoked by generation

---

## Edge Cases Worth Calling Out

A few cases that are easy to miss when reasoning about the parser:

### Empty Optional Data

The MRZ allows optional data fields that may be filled with the filler character `<` when unused. The parser exposes these as empty strings (or strings consisting entirely of fillers, depending on the field's role). This is faithful extraction; it does not become an error.

### Personal Number Check Digit as Filler

In TD3, some issuing states leave the personal number check digit as the filler character `<` even when the personal number itself is populated. This is a real-world deviation from strict ICAO conformance. The parser exposes the personal number check digit as-is (a literal `<` character) and surfaces this as a typed warning rather than refusing the parse. The data is still returned.

### Truncated Names

The MRZ has rules for truncating names that exceed the available width, with a specific truncation indicator. The parser detects truncation, exposes the raw name field, the parsed primary and secondary identifiers, and a `nameTruncated` boolean. This is informational; a truncated name is not an error.

### Two-Character Document Type Codes

Recent ICAO Doc 9303 editions introduce two-character document type codes (`PP`, `PD`, `PS`, etc.) alongside the older single-character codes (`P` for passport). The parser accepts both. The parser strips trailing MRZ filler `<` from the two-character document-type slot before wrapping in `DocumentType` — a single-character code `P` paired with filler in the second position becomes `DocumentType("P")`, not `DocumentType("P<")`. See the `DocumentType` section of [`mrz-data-model.md`](mrz-data-model.md) for the full rule including edge cases (empty slot, malformed leading filler). The `DocumentType` value class exposes this `rawCode`, with recognition flagged separately.

---

## Related Principles

- **Principle 1 (Reader, not oracle)** — verbatim extraction; no correction, no inference of "intended" values
- **Principle 5 (Transparency)** — every extracted value is exposed; raw forms are always available
- **Principle 7 (Fail loudly, fail informatively)** — typed result variants distinguish success, partial success, and failure
- **Principle 9 (Forward-compatible API)** — strict-only behavior leaves room for additive lenient and tolerant modes later

---

## Related Documents

- `principles.md` — the foundational principles this document references
- `mrz-data-model.md` — the types the parser produces
- `mrz-error-taxonomy.md` — the errors and validation failures the parser surfaces
- `mrz-generation.md` — the inverse operation
- `mrz-validation.md` — additional validation invokable on already-parsed data
- `lookup-tables.md` — country and document type code recognition
- `conventions.md` — naming and API conventions referenced in this document
