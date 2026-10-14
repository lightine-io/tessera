package io.lightine.tessera.types.errors

/**
 * Warning: the document type code is not in the SDK's document type table.
 *
 * Reported by the validator. A code missing from the table is not necessarily an invalid code.
 *
 * @property rawCode The document type code as printed.
 * @property position Zero-based index of the first code character across the lines joined with no separator.
 * @since 0.1.0
 */
public data class MrzUnknownDocumentTypeCode(
    val rawCode: String,
    val position: Int,
) : MrzWarning() {
    /** English text for logs: the code and its position. */
    override val description: String
        get() = "Document type code '$rawCode' at position $position is not in the SDK's recognized lookup tables"
}
