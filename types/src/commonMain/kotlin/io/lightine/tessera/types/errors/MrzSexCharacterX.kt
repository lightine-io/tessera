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
    /** English text for logs: the position and the rule. It never contains the character; that is in [observed]. */
    override val description: String
        get() =
            "Sex field at position $position contains the character X. " +
                "ICAO Doc 9303 lists 'M', 'F' and '<' as the MRZ sex characters and 'X' for the visual zone only."

    /** `MrzSexCharacterX: ` followed by [description], so logs carry no document values. */
    override fun toString(): String = "MrzSexCharacterX: $description"
}
