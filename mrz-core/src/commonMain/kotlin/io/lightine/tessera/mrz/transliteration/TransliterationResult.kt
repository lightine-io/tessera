package io.lightine.tessera.mrz.transliteration

import io.lightine.tessera.types.vocabulary.UnmappedCharacter

/**
 * The result of applying a transliteration profile: [Success] or [Failure].
 *
 * The profiles the SDK ships map unmapped characters to `<` and always return [Success].
 *
 * `toString()` on each of them shows no document values; they are in the properties.
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
    ) : TransliterationResult() {
        /** How long the mapped text is; never the text itself. */
        override fun toString(): String = "Success(outputLength=${output.length})"
    }

    /**
     * At least one character could not be mapped and the profile has no fallback.
     *
     * @property unmappedCharacters The characters that could not be mapped.
     * @since 0.1.0
     */
    public data class Failure(
        val unmappedCharacters: List<UnmappedCharacter>,
    ) : TransliterationResult() {
        /** How many characters could not be mapped; never a character itself. */
        override fun toString(): String = "Failure(unmappedCharacterCount=${unmappedCharacters.size})"
    }
}
