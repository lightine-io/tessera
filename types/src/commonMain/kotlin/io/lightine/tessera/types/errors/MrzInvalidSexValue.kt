package io.lightine.tessera.types.errors

/**
 * Validation failure: the sex character is not `M`, `F`, `<`, or `X`.
 *
 * Reported by the validator. The parsed `Sex` is `UNSPECIFIED` for this character, and
 * `CommonFields.rawSex` keeps it. The character `X` is reported as [MrzSexCharacterX] instead.
 *
 * @property observed The sex character as printed.
 * @property position Zero-based index of the character across the lines joined with no separator.
 * @since 0.1.0
 */
public data class MrzInvalidSexValue(
    val observed: Char,
    val position: Int,
) : MrzValidationError() {
    /** English text for logs: the position and the accepted set. It never contains the character; that is in [observed]. */
    override val description: String
        get() =
            "Sex field at position $position contains an unaccepted value; accepted values are " +
                "'M', 'F', '<', or 'X' (X surfaces as MrzSexCharacterX warning rather than this failure)"

    /** `MrzInvalidSexValue: ` followed by [description], so logs carry no document values. */
    override fun toString(): String = "MrzInvalidSexValue: $description"
}
