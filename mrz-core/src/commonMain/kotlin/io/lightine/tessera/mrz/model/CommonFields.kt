package io.lightine.tessera.mrz.model

import io.lightine.tessera.mrz.recognition.CountryCode
import io.lightine.tessera.mrz.recognition.DocumentType
import io.lightine.tessera.types.vocabulary.Sex

/**
 * The MRZ fields that every format carries.
 *
 * @property documentType Document type code as printed, with trailing `<` removed.
 * @property issuingState Issuing state code as printed.
 * @property primaryIdentifier Name before the first `<<`. Each `<` reads as a space.
 * @property secondaryIdentifier Name after the first `<<`, each `<` read as a space. Empty if there
 *   is none.
 * @property nameTruncated `true` if the name field ends without a `<`.
 * @property rawNameField Name field exactly as printed, before decoding. It keeps what the two
 *   identifiers lose.
 * @property documentNumber Document number as printed, including any `<` filler.
 * @property nationality Nationality code as printed.
 * @property dateOfBirth Date of birth.
 * @property sex Sex read from [rawSex]: `M` is male, `F` is female, any other character is
 *   unspecified.
 * @property rawSex Sex character as printed.
 * @property dateOfExpiry Date of expiry.
 * @property checkDigits Check digits as printed.
 * @since 0.1.0
 */
public data class CommonFields(
    val documentType: DocumentType,
    val issuingState: CountryCode,
    val primaryIdentifier: String,
    val secondaryIdentifier: String,
    val nameTruncated: Boolean,
    val rawNameField: String,
    val documentNumber: String,
    val nationality: CountryCode,
    val dateOfBirth: MrzDate,
    val sex: Sex,
    val rawSex: Char,
    val dateOfExpiry: MrzDate,
    val checkDigits: MrzCheckDigits,
)
