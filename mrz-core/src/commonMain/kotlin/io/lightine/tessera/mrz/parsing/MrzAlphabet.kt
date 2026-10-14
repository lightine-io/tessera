package io.lightine.tessera.mrz.parsing

/**
 * Returns whether a character is in the MRZ alphabet: `A`-`Z`, `0`-`9`, or `<`.
 *
 * @param c The character to test.
 * @return `true` for an uppercase letter, a digit, or `<`; `false` otherwise, including lowercase
 *   letters.
 */
public fun isMrzAlphabetCharacter(c: Char): Boolean = c in '0'..'9' || c in 'A'..'Z' || c == '<'
