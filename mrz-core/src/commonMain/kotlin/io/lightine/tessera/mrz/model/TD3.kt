package io.lightine.tessera.mrz.model

import io.lightine.tessera.types.vocabulary.MrzFormat

/**
 * A parsed TD3 (passport) MRZ: two lines of 44 characters.
 *
 * @property rawLines The MRZ lines the document was read from.
 * @property commonFields The fields every format carries.
 * @property personalNumber Personal number as printed: line 2, characters 29-42.
 * @property personalNumberCheckDigit Its check digit as printed: line 2, character 43. The
 *   validator reports `<` here, with a personal number present, as a warning and not as a check
 *   digit mismatch. With an all-filler personal number, `<` and `0` are both allowed by ICAO
 *   Doc 9303 Part 4, and the validator reports nothing for either.
 * @since 0.1.0
 */
public data class TD3(
    override val rawLines: List<String>,
    override val commonFields: CommonFields,
    val personalNumber: String,
    val personalNumberCheckDigit: Char,
) : MrzDocument() {
    /** Always [MrzFormat.TD3]. */
    override val format: MrzFormat = MrzFormat.TD3
}
