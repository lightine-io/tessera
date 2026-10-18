package io.lightine.tessera.types.errors

import kotlinx.datetime.LocalDate

/**
 * Warning: no century puts the birth date in the past within [thresholdYears] of [referenceDate].
 *
 * Reported when the year, month, and day form a real date but every century candidate is
 * either in the future or older than [thresholdYears] (130) at the reference date.
 *
 * Never reported: the MRZ year has two digits, and one of the two century candidates always gives
 * an age under 130 years (until the year 2130). Deprecated in 0.5.3 and removed at 1.0.
 *
 * @property rawYear The two year digits as printed.
 * @property rawMonth The two month digits as printed.
 * @property rawDay The two day digits as printed.
 * @property referenceDate The UTC date the age was measured against.
 * @property thresholdYears The age limit that was exceeded, in years.
 * @since 0.1.0
 */
@Deprecated(
    "Never reported: a two-digit MRZ year always fits a century with an age under 130 years. " +
        "Removed at 1.0; drop any handling of it.",
)
public data class MrzBirthDateImplausiblyOld(
    val rawYear: String,
    val rawMonth: String,
    val rawDay: String,
    val referenceDate: LocalDate,
    val thresholdYears: Int,
) : MrzWarning() {
    /** English text for logs: the raw date parts, the age limit, and the reference date. */
    override val description: String
        get() =
            "Document birth date components (year='$rawYear', month='$rawMonth', day='$rawDay') " +
                "imply an age greater than $thresholdYears years at every candidate century " +
                "relative to reference date $referenceDate"
}
