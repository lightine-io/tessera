package io.lightine.tessera.mrz.transliteration

/**
 * A mapping from text to the MRZ alphabet, chosen by the caller.
 *
 * @since 0.1.0
 */
public interface TransliterationProfile {
    /**
     * Stable identifier of the profile: `ICAO` for the default, an ISO 3166-1 alpha-3 code such as
     * `AZE` for a country.
     */
    public val identifier: String

    /**
     * Maps text to the MRZ alphabet.
     *
     * @param normalizedInput The text, already in Unicode NFC.
     * @return [TransliterationResult.Success] with the mapped text, or
     *   [TransliterationResult.Failure] with the characters it could not map.
     */
    public fun toMrzAlphabet(normalizedInput: String): TransliterationResult
}
