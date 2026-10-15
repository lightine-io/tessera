package io.lightine.tessera.mrz.transliteration

/**
 * Normalizes text to Unicode NFC, then applies this profile.
 *
 * @param input The text to transliterate.
 * @return The profile identifier, the original and normalized text, and the profile's result.
 */
public fun TransliterationProfile.transliterate(input: String): TransliterationOutcome {
    val normalized = normalizeForTransliteration(input)
    val result = this.toMrzAlphabet(normalized)
    return TransliterationOutcome(
        profileIdentifier = this.identifier,
        originalInput = input,
        normalizedInput = normalized,
        result = result,
    )
}

/**
 * The outcome of [transliterate].
 *
 * @property profileIdentifier Identifier of the profile that ran.
 * @property originalInput The text as passed in.
 * @property normalizedInput The text after Unicode NFC normalization, as the profile saw it.
 * @property result The profile's result.
 * @since 0.1.0
 */
public data class TransliterationOutcome(
    val profileIdentifier: String,
    val originalInput: String,
    val normalizedInput: String,
    val result: TransliterationResult,
)
