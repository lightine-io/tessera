package io.lightine.tessera.mrz.transliteration

import io.lightine.tessera.types.vocabulary.UnmappedCharacter

/**
 * The result of applying a transliteration profile: [Success] or [Failure].
 *
 * The `ICAO` and `AZE` profiles map unmapped characters to `<` and always return [Success].
 *
 * @since 0.1.0
 */
public sealed class TransliterationResult {
    /**
     * Every character was handled.
     *
     * @property output The mapped text, in the MRZ alphabet.
     * @since 0.1.0
     */
    public data class Success(
        val output: String,
    ) : TransliterationResult()

    /**
     * At least one character could not be mapped and the profile has no fallback.
     *
     * @property unmappedCharacters The characters that could not be mapped.
     * @since 0.1.0
     */
    public data class Failure(
        val unmappedCharacters: List<UnmappedCharacter>,
    ) : TransliterationResult()
}
