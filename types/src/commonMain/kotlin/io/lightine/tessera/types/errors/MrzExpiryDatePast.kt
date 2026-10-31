package io.lightine.tessera.types.errors

import kotlinx.datetime.LocalDate

/**
 * Warning: the expiry date is earlier than [referenceDate].
 *
 * Reported by the validator when the computed expiry date is strictly earlier than the
 * reference date. An expiry date equal to the reference date is not reported.
 *
 * @property expiryDate The computed expiry date.
 * @property referenceDate The UTC date the expiry date was compared with.
 * @since 0.1.0
 */
public data class MrzExpiryDatePast(
    val expiryDate: LocalDate,
    val referenceDate: LocalDate,
) : MrzWarning() {
    /** English text for logs. It never contains dates; they are in [expiryDate] and [referenceDate]. */
    override val description: String
        get() = "Document expiry date is before the reference date"

    /** `MrzExpiryDatePast: ` followed by [description], so logs carry no document values. */
    override fun toString(): String = "MrzExpiryDatePast: $description"
}
