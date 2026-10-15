package io.lightine.tessera.mrz.recognition

import io.lightine.tessera.types.vocabulary.DocumentCategory

/**
 * One document type code in [DocumentTypeCodeTable].
 *
 * @property code The code as printed in an MRZ.
 * @property displayName The SDK's display name for the code.
 * @property category The category of the code.
 * @property generation The code convention the code belongs to.
 * @since 0.1.0
 */
public data class DocumentTypeCodeEntry(
    val code: String,
    val displayName: String,
    val category: DocumentCategory,
    val generation: DocumentTypeGeneration,
)
