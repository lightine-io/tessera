package io.lightine.tessera.types.errors

import kotlinx.datetime.LocalDate

/**
 * Warning: a passport whose document code has no harmonized second letter expires on or after
 * 1 January 2038.
 *
 * ICAO Doc 9303 Part 4 (8th edition), section 4.4: the second letter of a passport code comes from
 * the table (`PP`, `PE`, `PD`, `PO`, `PR`, `PT`, `PS`, `PL`, `PM`), and a passport issued without
 * a harmonized second letter shall expire before 1 January 2038. Reported by the validator for a
 * TD3 document whose document code starts with `P` and whose second character is not a letter of
 * that table (the filler `<` and any other character, such as `PX`), when the expiry date has a
 * computed date on or after 2038-01-01. `PU`, the code for single sheet documents, is never
 * reported. Not reported when the expiry century is unknown (that is
 * `MrzExpiryDateCenturyNotInferred`). The SDK cannot tell when the document was issued, so a code
 * outside the table is not reported on its own, only together with a late expiry date. This states
 * only how the printed values relate to the rule; it says nothing about whether the document is
 * genuine or valid.
 *
 * @property rawDocumentCode The two document code characters as printed, such as `P<` or `PX`.
 * @property expiryDate The computed expiry date.
 * @since 0.8.0
 */
public data class MrzExpiryDateAfterNonHarmonizedPassportLimit(
    val rawDocumentCode: String,
    val expiryDate: LocalDate,
) : MrzWarning() {
    /** English text for logs. It never contains the code or the date; they are in the properties. */
    override val description: String
        get() =
            "Passport whose document code has no harmonized second letter expires on or after 1 January 2038, " +
                "the limit in ICAO Doc 9303 Part 4, section 4.4"

    /** `MrzExpiryDateAfterNonHarmonizedPassportLimit: ` followed by [description], so logs carry no document values. */
    override fun toString(): String = "MrzExpiryDateAfterNonHarmonizedPassportLimit: $description"
}
