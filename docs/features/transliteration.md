# Transliteration

This feature document describes the SDK's support for converting names from their original written form into the restricted MRZ alphabet. Transliteration is invoked when generating an MRZ from input that contains characters outside the MRZ's allowed set; it is not invoked during parsing, since the MRZ already contains transliterated content.

This document focuses on the SDK-specific design choices: the profile-based architecture, the default ICAO profile, country-specific overrides, and how new profiles can be added. The actual transliteration rules themselves are defined by ICAO Doc 9303 Part 3 Section 6 and by the conventions of issuing states.

**Status:** Living
**Available since:** 0.1.0
**Platform availability:** Target-agnostic. Transliteration is pure logic and runs on every target the project supports.

---

## Purpose

The MRZ alphabet is restricted: only uppercase A-Z, digits 0-9, and the filler character `<` are permitted. Real-world names often contain characters outside this set — diacritics on Latin letters, characters from non-Latin scripts, ligatures, and other typographic features.

Transliteration converts a name from its original written form into a representation using only the MRZ alphabet. The choice of how to transliterate is not always obvious. ICAO Doc 9303 provides default recommendations, but issuing states often define their own conventions, and the same character may transliterate differently depending on which state issued the document.

The SDK supports this through a profile-based architecture: a profile defines the rules for a specific issuing context, and consumers select the profile that matches their use case.

---

## Unicode Normalization Before Profile Lookup

Before a profile's mapping rules are applied, consumer input is normalized to Unicode NFC (Normalization Form Canonical Composition) using the host platform's native Unicode normalizer (`java.text.Normalizer` on JVM/Android, `precomposedStringWithCanonicalMapping` on iOS, `String.prototype.normalize` on JS/Wasm). This ensures that canonically-equivalent inputs — for example, `é` as a single code point versus `e` plus a combining accent — produce the same transliterated output. The post-normalization, pre-transliteration form is exposed to consumers (per Principle 5) so they can inspect the normalization the SDK applied.

The reasoning is recorded in [ADR-014](../decisions/0014-unicode-normalization-strategy.md). The normalization step is invoked once at the entry to transliteration; profiles operate on normalized strings.

Two surfaces expose the post-normalization form:

- **Standalone**: `TransliterationProfile.transliterate(input)` (an extension function on the profile interface) returns a `TransliterationOutcome` carrying the profile identifier, the original input, the post-normalization form, and the underlying `TransliterationResult` (`Success(output)` or `Failure(unmappedCharacters)`).
- **Generator-attached**: when a primitive-input generator method (e.g., `MrzGenerator.generateTD3(...)`) is called with a `TransliterationProfile`, the generation result's `ResultMetadata.transliterationDetails` carries a `TransliterationDetails` struct listing every field the profile rewrote, each with its original input, post-normalization form, and post-transliteration output.

Both surfaces are stable as of `0.1.0` and locked under [ADR-007](../decisions/0007-strict-backward-compat-from-0x.md).

---

## Profile-Based Architecture

A `TransliterationProfile` is a named, documented set of character-mapping rules. Each profile knows how to transform a string with arbitrary characters into a string using only the MRZ alphabet.

The SDK ships with at least two profiles in the initial release:

- A default profile based on ICAO Doc 9303 Part 3 Section 6 recommendations
- A profile for the issuing country of primary interest, capturing conventions specific to that state

Additional profiles can be added in subsequent releases. The profile system is open: consumers can register their own profiles for issuing states the SDK does not yet support.

The illustrative shape:

```
interface TransliterationProfile {
    val identifier: String
    fun toMrzAlphabet(input: String): String
}

object TransliterationProfileRegistry {
    fun register(profile: TransliterationProfile)
    fun lookup(identifier: String): TransliterationProfile?
    fun all(): List<TransliterationProfile>
}
```

The actual class names, method names, and registration mechanism are decided at implementation time. The shape above is illustrative.

---

## Why Profiles Are Necessary

A single global transliteration table would be simpler to implement but cannot capture real-world practice. The same character is transliterated differently by different issuing states.

The Latin schwa character (`Ə` / `ə`, Unicode U+018F / U+0259) is the clearest example. Schwa is **not in ICAO Doc 9303 Part 3 Annex G at all** — Annex G's coverage ends at U+017D plus U+1E9E, and schwa (U+018F / U+0259) lives in Latin Extended-B beyond that. So ICAO provides no default for schwa, and the SDK's ICAO default profile falls schwa through to the filler character `<`. Issuing states that use schwa regularly in names define their own convention. The state with ISO 3166-1 alpha-3 code `AZE`, for example, encodes schwa as `A` (derived through the BGN/PCGN 1993 Agreement's `Ə → Ä` fallback note and ICAO Annex G's no-expansion `Ä → A` rule — see [ADR-009](../decisions/0009-transliteration-profiles.md) for the full chain).

The same state's profile diverges from Annex G defaults on several other letters too — `Ç → CH`, `Ğ → GH`, `Ş → SH`, plus a set of letters already in the MRZ alphabet that get encoded as English-phonetic-equivalent digraphs (`X → KH`, `C → J`, `J → ZH`, `Q → G`). This systematic phonetic Anglicization pattern is itself derivable from a primary source (the [ALA-LC romanization table for the AZE Latin alphabet](https://www.loc.gov/catdir/cpso/romanization/azerbaij.pdf) with diacritics stripped to ASCII), and matches observed practice in real AZE-issued documents. The point: even when Annex G has a recommendation, the issuing state's convention may diverge from it, and the SDK has to capture that divergence somewhere.

Other examples exist beyond AZE. German conventions transliterate `ä` as `AE`; Scandinavian conventions use `Ä → AE` or `Ö → OE`. Some states drop diacritics entirely (`ä → A`, `ö → O`). The "correct" transliteration depends on which state is issuing the document, not on the character alone.

The profile architecture honors this reality: each profile is documented as the convention used by a specific context. The consumer chooses the profile that applies (Principle 1 — Reader, not oracle: the SDK does not guess which profile to use; the consumer specifies).

---

## When Transliteration Is Invoked

Transliteration is invoked by the generator, never by the parser. The reasoning:

- The parser receives input that is already in the MRZ alphabet (extracted from a real MRZ). There is nothing to transliterate.
- The generator receives input that may contain non-MRZ-alphabet characters. The consumer specifies a profile, and the generator applies it before encoding.

Specifically, the generator's behavior with respect to transliteration:

- If the input contains only MRZ-alphabet characters, transliteration is not invoked; the input is encoded directly
- If the input contains characters outside the MRZ alphabet and a profile is provided, the profile is applied to the affected fields (typically name fields) before encoding
- If the input contains characters outside the MRZ alphabet and no profile is provided, the generator returns a typed error (`MrzGenerationUnsupportedCharacters`)

The generator never silently applies a default profile based on inferred locale, issuing state, or any other implicit context. If the consumer wants ICAO default behavior, they pass the ICAO default profile explicitly.

---

## Usage

The generator applies a profile internally (above); to transliterate outside the generator — or to pre-transliterate a value — use the `transliterate` extension, which composes Unicode normalization with the profile and exposes every step (Principle 5). The example compiles against the shipped API. The consumer chooses the profile by the document's issuing state; the SDK never infers it.

```kotlin
import io.lightine.tessera.mrz.transliteration.IcaoDefaultTransliterationProfile
import io.lightine.tessera.mrz.transliteration.transliterate
import io.lightine.tessera.mrz.transliteration.TransliterationResult

val outcome = IcaoDefaultTransliterationProfile.transliterate("Müller")

println(outcome.originalInput)   // "Müller" — exactly what you passed in
println(outcome.normalizedInput) // the NFC form the SDK actually mapped (transparency)

when (val result = outcome.result) {
    is TransliterationResult.Success -> println(result.output) // MRZ-alphabet output
    is TransliterationResult.Failure -> result.unmappedCharacters.forEach(::println)
}
```

The shipped `IcaoDefaultTransliterationProfile` and `AzeTransliterationProfile` always return `Success` — they map any unrecognized character to the filler `<`. A custom profile with no fallback policy can return `Failure`, naming the characters it could not map.

---

## The ICAO Default Profile

The ICAO default profile implements the recommendations in ICAO Doc 9303 Part 3 Section 6 — specifically §6.A (Annex G, the Latin section). Non-Latin scripts (Cyrillic §6.B, Arabic §6.C) are not in the initial release. It includes:

- Removal of diacritics from Latin letters (e.g., `ç → C`, `ñ → N`, `é → E`, `ü → U` in the no-expansion convention)
- The recommended transliterations for specific characters (e.g., `Æ → AE`, `ß → SS`, `Œ → OE`)
- The §4.6 punctuation rules: apostrophes and other Western punctuation are omitted with no filler character inserted (per the spec's "shall be omitted" wording); hyphens convert to the filler character `<`
- Conversion of any remaining unsupported character to the filler character `<` with appropriate documentation that this is a fallback

This profile is applied when the consumer specifies it explicitly. It is not applied automatically; even when no other profile is more specific, the consumer must request it.

---

## Country-Specific Profiles

A country-specific profile captures the conventions used by a particular issuing state. It is documented as such, with a profile identifier that matches the issuing state's code.

The initial release includes one country-specific profile beyond the ICAO default. Additional profiles are added in subsequent releases as needed by consumers or by the project itself.

Each country-specific profile documents:

- The identifier (typically the three-letter country code)
- The full mapping from non-MRZ-alphabet characters to MRZ-alphabet sequences
- Notes on where this profile differs from the ICAO default
- The source of the convention (typically a published official document or observed practice in real issued documents)

Profiles are not assertions of correctness — they are encodings of observed practice. If an issuing state changes its convention, the profile is updated accordingly in a future release.

---

## Adding New Profiles

A new transliteration profile is added through normal feature development. The procedure (documented in `conventions.md`):

1. Define the mapping rules, with citations to the convention's source where possible
2. Add the profile to the profile registry under a stable identifier (typically the issuing state's three-letter code)
3. Document the profile in this feature document or in a profile-specific reference document
4. Add tests that verify the profile produces expected output for known input cases
5. Update changelog

Because adding a profile is a non-breaking change (consumers must explicitly request a profile by identifier, and no existing identifier becomes ambiguous), new profiles can ship in MINOR releases.

Consumer-defined profiles can be registered at runtime by application code. The SDK's profile registry is open for extension; consumers do not need to fork the SDK to support a state the SDK has not yet shipped a profile for.

---

## Behavioral Commitments

The transliteration system commits to the following behaviors. These are part of the public contract.

### No Inference of Profile

The transliteration system never infers which profile to apply based on inputs other than the explicit profile selection. Issuing state, holder nationality, or any other contextual signal is not used to choose a profile. The consumer specifies, or the system declines to transliterate.

### Deterministic Output

A profile produces the same output for the same input every time. There is no time-dependent or context-dependent behavior in transliteration.

### Documented Source

Every shipped profile cites its source: the ICAO default cites Doc 9303 Part 3 Section 6; country-specific profiles cite the relevant official document, observed practice, or both.

### Safe to Call Concurrently

Profiles are stateless. Multiple invocations across multiple profiles can run concurrently in any threading or async model the target language supports.

### Round-Trip Awareness

Transliteration is generally lossy: applying a profile transforms input, and the original characters cannot be recovered from the output alone. Consumers requiring the original form preserve it themselves; the SDK does not retain pre-transliteration values implicitly.

---

## Relationship to Other Features

- **Generation** (`mrz-generation.md`) — the only feature that invokes transliteration; explicit profile must be provided when input contains non-MRZ-alphabet characters
- **Data model** (`mrz-data-model.md`) — name fields in the data model are post-transliteration when produced by parsing; pre-transliteration when consumer-provided
- **Lookup tables** (`lookup-tables.md`) — country codes used as profile identifiers reference the same code system
- **Conventions** (`conventions.md`) — procedure for adding new profiles

---

## Edge Cases Worth Calling Out

A few cases that deserve explicit mention:

### Characters Not Covered by the Selected Profile

A profile may not cover every possible character. When the generator encounters a character that is neither in the MRZ alphabet nor in the selected profile's mapping, the behavior depends on the profile's policy:

- Profiles that define a fallback (typically converting to the filler character `<`) apply the fallback
- Profiles that do not define a fallback cause the generator to return a typed error indicating which character was unmapped

This is per-profile policy; the SDK does not impose a single fallback strategy across all profiles.

### Profile Inheritance

Profiles do not inherit from each other in the initial release. A country-specific profile is a complete, standalone definition. Adding a "based on ICAO default with overrides" pattern is a possible future enhancement but is not part of the initial release.

### Multiple Profiles for the Same State

A state may have multiple conventions for different document types or different time periods. The SDK does not currently model this; profiles are keyed by identifier (typically a country code), and a state with multiple conventions is represented as a single profile or as multiple profiles with distinct identifiers (such as `XYZ-CURRENT` and `XYZ-LEGACY`). The choice is documented per profile.

### Localization of Display Names

The profile identifier and any human-readable description are English. As with country code names, consumers building user-facing experiences in other languages map identifiers to translated names in their own application code (consistent with the broader localization stance described in `conventions.md`).

---

## Related Principles

- **Principle 1 (Reader, not oracle)** — the system never infers which profile to use; the consumer specifies
- **Principle 5 (Transparency)** — every shipped profile cites its source; the rules are documented and inspectable
- **Principle 7 (Fail loudly, fail informatively)** — characters that cannot be transliterated produce typed errors, not silent omissions
- **Principle 9 (Forward-compatible API)** — new profiles are added through registration; existing profiles are stable within a major version

---

## Related Documents

- `principles.md` — the foundational principles this document references
- `mrz-generation.md` — the feature that invokes transliteration
- `mrz-data-model.md` — name fields and their transliteration state
- `lookup-tables.md` — country codes used as profile identifiers
- `conventions.md` — procedure for adding new profiles
- [ADR-009](../decisions/0009-transliteration-profiles.md) — the architectural decision for per-state profiles
- [ADR-014](../decisions/0014-unicode-normalization-strategy.md) — the implementation strategy for Unicode normalization before profile lookup
