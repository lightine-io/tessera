package io.lightine.tessera.types.errors

import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.UnmappedCharacter

/**
 * Generation error: a value has characters outside `A`-`Z`, `0`-`9`, and `<` that no profile mapped.
 *
 * Reported when no transliteration profile was given, or when the profile could not map
 * every character. `generate(document)` reports it for text already in the document.
 *
 * @property format The format being generated.
 * @property field The field that holds the value.
 * @property unmappedCharacters Each character that could not be mapped, with its position in the value.
 * @property observedValue The value that failed.
 * @since 0.1.0
 */
public data class MrzGenerationUnsupportedCharacters(
    val format: MrzFormat,
    val field: MrzField,
    val unmappedCharacters: List<UnmappedCharacter>,
    val observedValue: String,
) : MrzGenerationError() {
    /** English text for logs: the format, the field, the characters, and the value. */
    override val description: String
        get() {
            val chars = unmappedCharacters.joinToString(", ") { "'${it.character}' at position ${it.position}" }
            return "Format $format field ${this.field} contains characters outside the MRZ alphabet " +
                "and no transliteration profile resolved them: $chars (observed value: \"$observedValue\")"
        }
}
