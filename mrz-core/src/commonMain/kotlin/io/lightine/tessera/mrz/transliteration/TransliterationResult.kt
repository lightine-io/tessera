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
     * The profile produced output for the whole input. [replacedCharacters] lists input characters
     * that the profile replaced with `<` because it had no mapping for them. Separators that become
     * `<` by rule are not listed.
     *
     * @property output The mapped text, in the MRZ alphabet.
     * @since 0.1.0
     */
    public class Success(
        public val output: String,
        /**
         * Characters with no mapping in the normalized input.
         *
         * @since 1.1.0
         */
        public val replacedCharacters: List<UnmappedCharacter>,
    ) : TransliterationResult() {
        /**
         * Creates a success without replacement details.
         *
         * @param output The mapped text, in the MRZ alphabet.
         * @since 0.1.0
         */
        public constructor(output: String) : this(output, emptyList())

        /** The mapped text. */
        public operator fun component1(): String = output

        /** The characters replaced for having no mapping. */
        public operator fun component2(): List<UnmappedCharacter> = replacedCharacters

        /** Copies this result while preserving its replacement details. */
        public fun copy(output: String = this.output): Success = Success(output, replacedCharacters)

        /** Copies this result with explicit replacement details. */
        public fun copy(
            output: String = this.output,
            replacedCharacters: List<UnmappedCharacter>,
        ): Success = Success(output, replacedCharacters)

        override fun equals(other: Any?): Boolean =
            other is Success && output == other.output && replacedCharacters == other.replacedCharacters

        override fun hashCode(): Int = 31 * output.hashCode() + replacedCharacters.hashCode()

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
