package io.lightine.tessera.types.errors

import io.lightine.tessera.types.vocabulary.MrzFormat

/**
 * Generation error: a document's sex character is not `M`, `F`, or `<`.
 *
 * Reported by `generate(document)` for the document's `rawSex`. ICAO Doc 9303 allows only these
 * three characters in the MRZ; `X` is allowed only in the visual zone, so it is rejected too. The
 * generator never replaces the character. Reading is unchanged: the parser keeps any character,
 * and the validator reports `X` as [MrzSexCharacterX].
 *
 * @property format The format being generated.
 * @property observed The sex character as supplied.
 * @since 0.6.0
 */
public data class MrzGenerationInvalidSexValue(
    val format: MrzFormat,
    val observed: Char,
) : MrzGenerationError() {
    /** English text for logs: the format and the accepted set. It never contains the character; that is in [observed]. */
    override val description: String
        get() = "Format $format sex field contains an unaccepted value; accepted values are 'M', 'F', or '<'"

    /** `MrzGenerationInvalidSexValue: ` followed by [description], so logs carry no document values. */
    override fun toString(): String = "MrzGenerationInvalidSexValue: $description"
}
