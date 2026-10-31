package io.lightine.tessera.types.errors

import kotlinx.datetime.LocalDate

/**
 * Warning: the expiry date is more than [thresholdYears] after [referenceDate].
 *
 * Reported by the validator when the computed expiry date is later than the reference date
 * plus 10 years.
 *
 * @property expiryDate The computed expiry date.
 * @property referenceDate The UTC date the expiry date was compared with.
 * @property thresholdYears The limit that was exceeded, in years (10).
 * @since 0.1.0
 */
public data class MrzExpiryDateImplausiblyFar(
    val expiryDate: LocalDate,
    val referenceDate: LocalDate,
    val thresholdYears: Int,
) : MrzWarning() {
    /** English text for logs: the limit. It never contains dates; they are in the properties. */
    override val description: String
        get() = "Document expiry date is more than $thresholdYears years after the reference date"

    /** `MrzExpiryDateImplausiblyFar: ` followed by [description], so logs carry no document values. */
    override fun toString(): String = "MrzExpiryDateImplausiblyFar: $description"
}
