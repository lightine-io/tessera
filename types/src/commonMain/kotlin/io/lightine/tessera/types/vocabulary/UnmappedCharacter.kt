package io.lightine.tessera.types.vocabulary

/**
 * A character that a transliteration profile could not map into the MRZ alphabet.
 *
 * The character comes from a value a person typed or a document holds. `toString()` shows no
 * document values; they are in the properties.
 *
 * @property character The character that was not mapped.
 * @property position Zero-based index of the character in the normalized input.
 * @since 0.1.0
 */
public data class UnmappedCharacter(
    val character: Char,
    val position: Int,
) {
    /** Where the character sits; never the character itself. */
    override fun toString(): String = "UnmappedCharacter(position=$position)"
}
