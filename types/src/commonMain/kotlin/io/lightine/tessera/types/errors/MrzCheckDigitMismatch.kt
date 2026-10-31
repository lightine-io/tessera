package io.lightine.tessera.types.errors

import io.lightine.tessera.types.vocabulary.MrzField

/**
 * Validation failure: a printed check digit differs from the one the SDK computes.
 *
 * Reported by the validator for the document number, date of birth, date of expiry,
 * optional data (TD3 personal number only), and composite check digits.
 *
 * @property field The field the check digit covers.
 * @property expected The check digit the SDK computed from the field.
 * @property observed The check digit printed on the document.
 * @property position Zero-based index of the printed check digit across the lines joined with no separator.
 * @since 0.1.0
 */
public data class MrzCheckDigitMismatch(
    val field: MrzField,
    val expected: Char,
    val observed: Char,
    val position: Int,
) : MrzValidationError() {
    /** English text for logs: the field and the position. It never contains the digits; they are in [expected] and [observed]. */
    override val description: String
        get() = "Check digit for ${this.field} at position $position does not match"

    /** `MrzCheckDigitMismatch: ` followed by [description], so logs carry no document values. */
    override fun toString(): String = "MrzCheckDigitMismatch: $description"
}
