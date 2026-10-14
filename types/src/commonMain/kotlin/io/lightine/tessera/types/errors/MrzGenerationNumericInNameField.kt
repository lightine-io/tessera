package io.lightine.tessera.types.errors

import io.lightine.tessera.types.vocabulary.MrzFormat

/**
 * Generation error: a primary or secondary identifier contains a digit.
 *
 * Reported by the generator methods that take a primary and secondary identifier,
 * before any transliteration profile runs. The generator never removes the digits.
 *
 * @property format The format being generated.
 * @property observedValue The identifier as supplied, before transliteration.
 * @property numericCharacters Each digit found, in order of appearance.
 * @since 0.1.0
 */
public data class MrzGenerationNumericInNameField(
    val format: MrzFormat,
    val observedValue: String,
    val numericCharacters: List<Char>,
) : MrzGenerationError() {
    /** English text for logs: the format, the distinct digits, and the value. */
    override val description: String
        get() {
            val distinct = numericCharacters.distinct().joinToString(", ") { "'$it'" }
            return "Format $format name field contains numeric character(s) $distinct " +
                "(ICAO Doc 9303 Part 3 §4.6 forbids numeric characters in MRZ name fields). " +
                "Observed value: \"$observedValue\""
        }
}
