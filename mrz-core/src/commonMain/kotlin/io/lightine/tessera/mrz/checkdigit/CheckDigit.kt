package io.lightine.tessera.mrz.checkdigit

private val WEIGHTS = intArrayOf(7, 3, 1)

/**
 * Computes the check digit of an MRZ field.
 *
 * @param input The field text, using only `A`-`Z`, `0`-`9`, and `<`.
 * @return The digit `'0'` to `'9'` for the sum of each character value times the repeating weights
 *   7, 3, 1, modulo 10. Digits count as themselves, `A`-`Z` as 10-35, and `<` as 0.
 * @throws IllegalArgumentException if [input] has a character outside the MRZ alphabet. The message names
 *   its position, never the character, so it carries no document data (ADR-029).
 */
@Throws(IllegalArgumentException::class)
public fun computeCheckDigit(input: String): Char {
    var sum = 0
    for (index in input.indices) {
        sum += characterValue(input[index], index) * WEIGHTS[index % 3]
    }
    return '0' + (sum % 10)
}

private fun characterValue(
    c: Char,
    position: Int,
): Int =
    when (c) {
        in '0'..'9' -> c - '0'

        in 'A'..'Z' -> c - 'A' + 10

        '<' -> 0

        else -> throw IllegalArgumentException(
            "Character at position $position is outside the MRZ alphabet (A-Z, 0-9, <)",
        )
    }
