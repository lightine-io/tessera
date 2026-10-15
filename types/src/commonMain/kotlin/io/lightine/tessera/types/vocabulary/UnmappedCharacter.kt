package io.lightine.tessera.types.vocabulary

/**
 * A character that a transliteration profile could not map into the MRZ alphabet.
 *
 * @property character The character that was not mapped.
 * @property position Zero-based index of the character in the normalized input.
 * @since 0.1.0
 */
public data class UnmappedCharacter(
    val character: Char,
    val position: Int,
)
