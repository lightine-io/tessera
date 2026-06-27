package io.lightine.tessera.types.errors

import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.UnmappedCharacter

/**
 * Generation error: a [field] value passed to
 * [`MrzGenerator`][io.lightine.tessera.mrz.generation.MrzGenerator] contains one or more
 * characters outside the MRZ alphabet (`A`–`Z`, `0`–`9`, filler `<`), and either no
 * transliteration profile was supplied or the supplied profile could not resolve them.
 * Carries the offending [unmappedCharacters] and the original [observedValue].
 *
 * Emitted in two situations:
 * 1. The `generate(document)` paths reject documents whose text fields already contain
 *    non-MRZ characters (closes a silent-failure gap; pre-transliterate or pass the
 *    primitive-input methods a profile instead).
 * 2. The primitive-input methods (e.g., `generateTD3(..., transliteration = profile)`)
 *    return this when the supplied profile's result is
 *    [`TransliterationResult.Failure`][io.lightine.tessera.mrz.transliteration.TransliterationResult.Failure].
 *
 * See [`docs/features/transliteration.md`](https://lightine.youtrack.cloud/articles/TES-A-24)
 * for the profile interface and ADR-009 for the rationale.
 */
public data class MrzGenerationUnsupportedCharacters(
    val format: MrzFormat,
    val field: MrzField,
    val unmappedCharacters: List<UnmappedCharacter>,
    val observedValue: String,
) : MrzGenerationError() {
    override val description: String
        get() {
            val chars = unmappedCharacters.joinToString(", ") { "'${it.character}' at position ${it.position}" }
            return "Format $format field ${this.field} contains characters outside the MRZ alphabet " +
                "and no transliteration profile resolved them: $chars (observed value: \"$observedValue\")"
        }
}
