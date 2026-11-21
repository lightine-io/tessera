package io.lightine.tessera.types.errors

import kotlinx.datetime.LocalDate

/**
 * Warning: a passport whose document code has no second letter expires on or after 1 January 2038.
 *
 * ICAO Doc 9303 Part 4 (8th edition), section 4.4: a passport issued without a second letter in
 * its document code shall expire before 1 January 2038. Reported by the validator for a TD3
 * document whose document code is `P` followed by the filler `<`, when the expiry date has a
 * computed date on or after 2038-01-01. Not reported when the expiry century is unknown
 * (that is `MrzExpiryDateCenturyNotInferred`). This states only how the printed values relate to
 * the rule; it says nothing about whether the document is genuine or valid.
 *
 * @property rawDocumentCode The two document code characters as printed, such as `P<`.
 * @property expiryDate The computed expiry date.
 * @since 1.0.0
 */
public data class MrzExpiryDateAfterSingleLetterPassportLimit(
    val rawDocumentCode: String,
    val expiryDate: LocalDate,
) : MrzWarning() {
    /** English text for logs. It never contains the code or the date; they are in the properties. */
    override val description: String
        get() =
            "Passport with a one-letter document code expires on or after 1 January 2038, " +
                "the limit in ICAO Doc 9303 Part 4, section 4.4"

    /** `MrzExpiryDateAfterSingleLetterPassportLimit: ` followed by [description], so logs carry no document values. */
    override fun toString(): String = "MrzExpiryDateAfterSingleLetterPassportLimit: $description"
}
