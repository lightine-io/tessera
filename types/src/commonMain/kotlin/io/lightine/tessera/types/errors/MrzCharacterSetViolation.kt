package io.lightine.tessera.types.errors

/**
 * Parse error: the input has a character outside `A`-`Z`, `0`-`9`, and `<`.
 *
 * Reported by the parser when the line count and lengths fit a format. Only the first
 * offending character is reported. Lowercase letters count as outside the alphabet.
 *
 * @property offendingCharacter The first character outside the MRZ alphabet.
 * @property position Zero-based index of that character across the lines joined with no separator.
 * @since 0.1.0
 */
public data class MrzCharacterSetViolation(
    val offendingCharacter: Char,
    val position: Int,
) : MrzParseError() {
    /** English text for logs: the position. It never contains the character; that is in [offendingCharacter]. */
    override val description: String
        get() = "Character at position $position is outside the MRZ alphabet (A-Z, 0-9, <)"

    /** `MrzCharacterSetViolation: ` followed by [description], so logs carry no document values. */
    override fun toString(): String = "MrzCharacterSetViolation: $description"
}
