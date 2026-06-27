package io.lightine.tessera.mrz.transliteration

/**
 * Per-state mapping of consumer-supplied text into the MRZ alphabet (`A`–`Z`, `0`–`9`,
 * filler `<`).
 *
 * Per [ADR-009](https://lightine.youtrack.cloud/articles/TES-A-42):
 * the SDK does not infer which profile applies — the consumer chooses based on the
 * issuing state of the document being generated. The SDK ships
 * [IcaoDefaultTransliterationProfile] (ICAO Doc 9303 Part 3 Section 6 recommendations)
 * and at least one country-specific profile ([AzeTransliterationProfile]); consumers can
 * register their own via [TransliterationProfileRegistry].
 *
 * To apply a profile end-to-end (including Unicode normalization), use the
 * [`transliterate`][transliterate] extension function rather than calling
 * [toMrzAlphabet] directly.
 */
public interface TransliterationProfile {
    /**
     * A stable identifier for the profile. ICAO defaults use `"ICAO"`; country-specific
     * profiles use the ISO 3166-1 alpha-3 code (`"AZE"`, etc.).
     */
    public val identifier: String

    /** Applies this profile's mapping rules. [normalizedInput] is assumed Unicode NFC (per ADR-014). */
    public fun toMrzAlphabet(normalizedInput: String): TransliterationResult
}
