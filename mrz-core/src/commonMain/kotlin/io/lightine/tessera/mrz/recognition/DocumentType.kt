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

    /**
     * A coarser [DocumentCategory] than [category]: [category] itself when [rawCode] is an exact match in
     * [DocumentTypeCodeTable], otherwise the category implied by just the first character of [rawCode] —
     * the same ICAO Doc 9303 reserved-leading-character rule [DocumentTypeCodeTable]'s own KDoc documents
     * (Part 4 §4.4 reserves `P`/`V` for passports/visas; Parts 5/6 reserve `A`/`C`/`I` for TD1/TD2
     * identity-family documents, whose issuer-specific second character the table deliberately does not
     * enumerate). This is a defined-standard lookup, not inference: [rawCode] stays the source of truth, and
     * [broadCategory] is only a convenience projection of it — it never claims a document is valid or
     * genuine (reader, not oracle — Principle 1).
     *
     * | First character of [rawCode] | [broadCategory] |
     * |---|---|
     * | `P` | [DocumentCategory.PASSPORT] |
     * | `I`, `A`, `C` | [DocumentCategory.IDENTITY_CARD] |
     * | `V` | [DocumentCategory.VISA] |
     * | anything else, or [rawCode] blank | `null` |
     *
     * Case-insensitive on the first character.
     *
     * ```
     * DocumentType("IA").broadCategory  // DocumentCategory.IDENTITY_CARD — not in DocumentTypeCodeTable
     *                                   // (issuer-specific second letter), but 'I' is ICAO-reserved.
     * DocumentType("PP").broadCategory  // DocumentCategory.PASSPORT — exact match, same as .category.
     * DocumentType("XY").broadCategory  // null — 'X' is not an ICAO-reserved leading character.
     * ```
     */
    public val broadCategory: DocumentCategory?
        get() = category ?: firstLetterCategory(rawCode)
}

/**
 * The [DocumentCategory] implied by just the first character of [rawCode], per ICAO Doc 9303 Part 4 §4.4
 * (`P`/`V`) and Parts 5/6 (`A`/`C`/`I`) — the same reserved-leading-character rule [DocumentTypeCodeTable]'s
 * KDoc documents. `null` when [rawCode] is blank or its first character is not one of those. Case-insensitive.
 * Backs [DocumentType.broadCategory].
 */
private fun firstLetterCategory(rawCode: String): DocumentCategory? =
    when (rawCode.firstOrNull()?.uppercaseChar()) {
        'P' -> DocumentCategory.PASSPORT
        'I', 'A', 'C' -> DocumentCategory.IDENTITY_CARD
        'V' -> DocumentCategory.VISA
        else -> null
    }
