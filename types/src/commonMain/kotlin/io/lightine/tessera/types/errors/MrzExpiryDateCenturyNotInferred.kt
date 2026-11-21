package io.lightine.tessera.types.errors

import kotlinx.datetime.LocalDate

/**
 * Warning: the SDK could not place the expiry year in a century.
 *
 * Reported by the validator when the expiry date's parts are digits and form a real calendar
 * date, but neither the 2000s nor the 1900s puts the year from 10 years before to 50 years after
 * the year of [referenceDate]. The expiry date then has no computed date, so the other expiry
 * warnings are not reported. This states only that the century is unknown; it says nothing about
 * whether the document has expired.
 *
 * @property rawYear The two year digits as printed.
 * @property rawMonth The two month digits as printed.
 * @property rawDay The two day digits as printed.
 * @property referenceDate The UTC date the expiry year was compared with.
 * @since 1.0.0
 */
public data class MrzExpiryDateCenturyNotInferred(
    val rawYear: String,
    val rawMonth: String,
    val rawDay: String,
    val referenceDate: LocalDate,
) : MrzWarning() {
    /** English text for logs. It never contains dates; they are in the properties. */
    override val description: String
        get() = "Document expiry year could not be placed in a century"

    /** `MrzExpiryDateCenturyNotInferred: ` followed by [description], so logs carry no document values. */
    override fun toString(): String = "MrzExpiryDateCenturyNotInferred: $description"
}
