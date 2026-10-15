package io.lightine.tessera.mrz.model

/**
 * The check digits exactly as printed on the document, not computed by the SDK.
 *
 * @property documentNumber Check digit of the document number.
 * @property dateOfBirth Check digit of the date of birth.
 * @property dateOfExpiry Check digit of the date of expiry.
 * @property optionalData Check digit of the optional data. `null` in every format except TD3, where
 *   it is the personal number check digit.
 * @property composite Composite check digit. `null` in MRV-A and MRV-B.
 * @since 0.1.0
 */
public data class MrzCheckDigits(
    val documentNumber: Char,
    val dateOfBirth: Char,
    val dateOfExpiry: Char,
    val optionalData: Char?,
    val composite: Char?,
)
