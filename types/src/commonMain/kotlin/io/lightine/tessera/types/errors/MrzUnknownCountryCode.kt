package io.lightine.tessera.types.errors

import io.lightine.tessera.types.vocabulary.MrzField

/**
 * Warning: an issuing state or nationality code is not in the SDK's country code table.
 *
 * Reported by the validator. A code missing from the table is not necessarily an invalid code.
 *
 * @property field The field that holds the code, `ISSUING_STATE` or `NATIONALITY`.
 * @property rawCode The three-character code as printed.
 * @property position Zero-based index of the first code character across the lines joined with no separator.
 * @since 0.1.0
 */
public data class MrzUnknownCountryCode(
    val field: MrzField,
    val rawCode: String,
    val position: Int,
) : MrzWarning() {
    /** English text for logs: the field, the code, and the position. */
    override val description: String
        get() = "Country code '$rawCode' for ${this.field} at position $position is not in the SDK's recognized lookup tables"
}
