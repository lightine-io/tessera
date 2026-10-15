package io.lightine.tessera.mrz.recognition

import io.lightine.tessera.types.vocabulary.CountryCodeCategory

/**
 * One country code in [CountryCodeTable].
 *
 * @property code The code as printed in an MRZ.
 * @property displayName The SDK's display name for the code.
 * @property category The category of the code.
 * @since 0.1.0
 */
public data class CountryCodeEntry(
    val code: String,
    val displayName: String,
    val category: CountryCodeCategory,
)
