package io.lightine.tessera.mrz.recognition

import io.lightine.tessera.types.vocabulary.DocumentCategory
import kotlin.jvm.JvmInline

/**
 * The document type code as it appears in the MRZ's document type field (one or two
 * characters per ICAO Doc 9303 Part 3 Section 4 — e.g., `"P"` for passport, `"PP"` for
 * ordinary passport).
 *
 * The [rawCode] is what the document actually contains; the other properties consult
 * [DocumentTypeCodeTable] to add SDK-recognized context (display name, category,
 * generation). Lookup failures are not errors — see [isRecognized] and
 * [`MrzUnknownDocumentTypeCode`][io.lightine.tessera.types.errors.MrzUnknownDocumentTypeCode]
 * for the recognition-failure flow per
 * [ADR-013](https://lightine.youtrack.cloud/articles/TES-A-44).
 */
@JvmInline
public value class DocumentType(
    public val rawCode: String,
) {
    /** The [DocumentTypeCodeTable] entry for [rawCode], or `null` if the code is not in the table. */
    public val entry: DocumentTypeCodeEntry?
        get() = DocumentTypeCodeTable.lookup(rawCode)

    /** True if [rawCode] is in [DocumentTypeCodeTable]. */
    public val isRecognized: Boolean
        get() = entry != null

    /** The category from [entry], or `null` if the code is not recognized. */
    public val category: DocumentCategory?
        get() = entry?.category
}
