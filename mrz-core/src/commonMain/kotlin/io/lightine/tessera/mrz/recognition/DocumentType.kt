package io.lightine.tessera.mrz.recognition

import io.lightine.tessera.types.vocabulary.DocumentCategory
import kotlin.jvm.JvmInline

/**
 * A document type code as printed in an MRZ: one or two characters.
 *
 * @property rawCode The code exactly as printed.
 * @since 0.1.0
 */
@JvmInline
public value class DocumentType(
    public val rawCode: String,
) {
    /** The [DocumentTypeCodeTable] entry for [rawCode], or `null` if the table has none. */
    public val entry: DocumentTypeCodeEntry?
        get() = DocumentTypeCodeTable.lookup(rawCode)

    /** `true` if [DocumentTypeCodeTable] has an entry for [rawCode]. */
    public val isRecognized: Boolean
        get() = entry != null

    /** The category from [entry], or `null` if the code is not recognized. */
    public val category: DocumentCategory?
        get() = entry?.category

    /**
     * The category from [entry], else the one implied by the first character of [rawCode].
     *
     * A first character of `P` gives passport, `I`, `A`, or `C` gives identity card, and `V` gives
     * visa, in either case. Any other first character, or a blank [rawCode], gives `null`.
     */
    public val broadCategory: DocumentCategory?
        get() = category ?: firstLetterCategory(rawCode)
}

/**
 * The [DocumentCategory] implied by only the first character of [rawCode], per ICAO Doc 9303 Part 4 §4.4
 * (`P`/`V`) and Parts 5/6 (`A`/`C`/`I`) - the same reserved-leading-character rule [DocumentTypeCodeTable]'s
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
