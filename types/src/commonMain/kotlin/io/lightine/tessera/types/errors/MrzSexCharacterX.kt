package io.lightine.tessera.types.errors

/**
 * Warning: the sex character is `X`.
 *
 * Reported by the validator. ICAO lists `X` for the visual zone only, but some issuers
 * print it in the MRZ. The parsed `Sex` is `UNSPECIFIED`, and `CommonFields.rawSex` keeps the `X`.
 *
 * @property observed The sex character as printed. Always `'X'`.
 * @property position Zero-based index of the character across the lines joined with no separator.
 * @since 0.1.0
 */
public data class MrzSexCharacterX(
    val observed: Char,
    val position: Int,
) : MrzWarning() {
    /** English text for logs: the position and the `X` character. */
    override val description: String
        get() =
            "Sex field at position $position contains 'X'. " +
                "ICAO Doc 9303 lists 'M', 'F' and '<' as the MRZ sex characters and 'X' for the visual zone only."
}
