package io.lightine.tessera.types.errors

/**
 * Warning: the name field fills its width with no trailing `<`.
 *
 * Reported by the validator. The SDK does not rebuild the full name.
 *
 * @property rawNameField The name field as printed.
 * @property position Zero-based index of the first name character across the lines joined with no separator.
 * @since 0.1.0
 */
public data class MrzNameTruncated(
    val rawNameField: String,
    val position: Int,
) : MrzWarning() {
    /** English text for logs: the position and the missing trailing filler. */
    override val description: String
        get() = "Name field at position $position fills the available width with no trailing filler, indicating ICAO Doc 9303 truncation"
}
