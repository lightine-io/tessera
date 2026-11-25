package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.vocabulary.MrzField

/**
 * Input error: in the MRZ access key, the check digit of one or more fields does not match its
 * field, so the reader sent nothing to the chip.
 *
 * The chip derives its key from the fields together with their check digits (ICAO Doc 9303
 * Part 11, section 4.3.2), so a value read or typed with a mistake would cost the chip an access
 * attempt. The read stops before any command instead. An app that reads a document printed with a
 * wrong check digit can allow the mismatch with the read option `allowCheckDigitMismatch`.
 *
 * This is a finding about the key the app gave, not about the document.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property fields The fields whose check digit does not match: [MrzField.DOCUMENT_NUMBER],
 *   [MrzField.DATE_OF_BIRTH] or [MrzField.DATE_OF_EXPIRY]. Never their values.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public data class ChipKeyCheckDigitMismatch(
    val fields: Set<MrzField>,
) : ChipError() {
    /** English text for logs: the names of the fields, never their values. */
    override val description: String
        get() {
            val names =
                fields.sortedBy { it.ordinal }.joinToString(", ") {
                    when (it) {
                        MrzField.DOCUMENT_NUMBER -> "document number"
                        MrzField.DATE_OF_BIRTH -> "date of birth"
                        MrzField.DATE_OF_EXPIRY -> "date of expiry"
                        else -> it.name.lowercase().replace('_', ' ')
                    }
                }
            return "The check digit of the access key's $names does not match, so nothing was sent to the chip"
        }

    /** A stable name of this error: see [ChipError.code]. */
    override val code: String
        get() = "key-check-digit-mismatch"

    /** `ChipKeyCheckDigitMismatch: ` followed by [description]. */
    override fun toString(): String = "ChipKeyCheckDigitMismatch: $description"
}
