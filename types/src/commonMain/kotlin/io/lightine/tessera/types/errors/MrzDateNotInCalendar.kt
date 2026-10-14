package io.lightine.tessera.types.errors

import io.lightine.tessera.types.vocabulary.MrzField

/**
 * Validation failure: a date's numeric parts form no real calendar date, for example month `13`.
 *
 * Reported by the validator for the date of birth and date of expiry when no century turns
 * the parts into a real date. Parts that are not digits are not reported here.
 *
 * @property field The date field, `DATE_OF_BIRTH` or `DATE_OF_EXPIRY`.
 * @property rawYear The two year digits as printed.
 * @property rawMonth The two month digits as printed.
 * @property rawDay The two day digits as printed.
 * @property position Zero-based index of the first date character across the lines joined with no separator.
 * @since 0.1.0
 */
public data class MrzDateNotInCalendar(
    val field: MrzField,
    val rawYear: String,
    val rawMonth: String,
    val rawDay: String,
    val position: Int,
) : MrzValidationError() {
    /** English text for logs: the field, the position, and the raw date parts. */
    override val description: String
        get() =
            "Date in ${this.field} at position $position is not a real calendar date: " +
                "rawYear='$rawYear', rawMonth='$rawMonth', rawDay='$rawDay'"
}
