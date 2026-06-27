package io.lightine.tessera.mrz.recognition

import io.lightine.tessera.types.vocabulary.DocumentCategory

/**
 * The SDK's recognized document type codes for MRZ document type fields.
 *
 * Includes the legacy single-character codes (`P`, `V`, `I`) and the current
 * two-character `P`-prefix codes per ICAO Doc 9303 Part 4 §4.4 ("Document Codes"),
 * plus the `AC` Crew Member Certificate code per Part 5 Appendix B for TD1 documents.
 *
 * Part 4 §4.4 specifies that **as of 1 January 2026, MRPs issued with a secondary
 * document code shall use the codes listed below**; from 1 January 2028 all MRPs
 * shall do so. This table covers every secondary code Part 4 §4.4 names.
 *
 * **Deliberate starter set on the non-`P` side.** TD1 / TD2 document codes per Parts
 * 5 / 6 are required to start with `A`, `C`, or `I` with the second character left to
 * the issuing state's discretion. This table ships only the unambiguously-defined
 * codes (`I`, `AC`) on that side; state-specific second characters are not
 * enumerable. Adding entries is a non-breaking change. See
 * [`docs/features/lookup-tables.md`](https://lightine.youtrack.cloud/articles/TES-A-22)
 * for the design and the project's issue tracker for tracking ("Document type code table
 * completeness").
 *
 * Codes not present in this table surface as
 * [`MrzUnknownDocumentTypeCode`][io.lightine.tessera.types.errors.MrzUnknownDocumentTypeCode]
 * warnings rather than validation failures, per
 * [ADR-013](https://lightine.youtrack.cloud/articles/TES-A-44).
 */
public object DocumentTypeCodeTable {
    private val entries: List<DocumentTypeCodeEntry> =
        listOf(
            // Legacy single-character codes (per ICAO Doc 9303 Part 3 historical convention).
            DocumentTypeCodeEntry(
                code = "P",
                displayName = "Passport",
                category = DocumentCategory.PASSPORT,
                generation = DocumentTypeGeneration.LEGACY_SINGLE_CHARACTER,
            ),
            DocumentTypeCodeEntry(
                code = "V",
                displayName = "Visa",
                category = DocumentCategory.VISA,
                generation = DocumentTypeGeneration.LEGACY_SINGLE_CHARACTER,
            ),
            DocumentTypeCodeEntry(
                code = "I",
                displayName = "Identity card",
                category = DocumentCategory.IDENTITY_CARD,
                generation = DocumentTypeGeneration.LEGACY_SINGLE_CHARACTER,
            ),
            // Current two-character codes per ICAO Doc 9303 Part 4 §4.4 (TD3 passports).
            // Effective 1 January 2026 for new MRPs that use a secondary document code;
            // effective 1 January 2028 for all MRPs.
            DocumentTypeCodeEntry(
                code = "PP",
                displayName = "Ordinary passport",
                category = DocumentCategory.PASSPORT,
                generation = DocumentTypeGeneration.CURRENT_TWO_CHARACTER,
            ),
            DocumentTypeCodeEntry(
                code = "PE",
                displayName = "Emergency passport",
                category = DocumentCategory.PASSPORT,
                generation = DocumentTypeGeneration.CURRENT_TWO_CHARACTER,
            ),
            DocumentTypeCodeEntry(
                code = "PD",
                displayName = "Diplomatic passport",
                category = DocumentCategory.PASSPORT,
                generation = DocumentTypeGeneration.CURRENT_TWO_CHARACTER,
            ),
            DocumentTypeCodeEntry(
                code = "PO",
                displayName = "Official/service passport",
                category = DocumentCategory.PASSPORT,
                generation = DocumentTypeGeneration.CURRENT_TWO_CHARACTER,
            ),
            DocumentTypeCodeEntry(
                code = "PR",
                displayName = "Refugee passport",
                category = DocumentCategory.PASSPORT,
                generation = DocumentTypeGeneration.CURRENT_TWO_CHARACTER,
            ),
            DocumentTypeCodeEntry(
                code = "PT",
                displayName = "Alien/Non-citizen passport",
                category = DocumentCategory.PASSPORT,
                generation = DocumentTypeGeneration.CURRENT_TWO_CHARACTER,
            ),
            DocumentTypeCodeEntry(
                code = "PS",
                displayName = "Stateless passport",
                category = DocumentCategory.PASSPORT,
                generation = DocumentTypeGeneration.CURRENT_TWO_CHARACTER,
            ),
            DocumentTypeCodeEntry(
                code = "PL",
                displayName = "Laissez-passer passport",
                category = DocumentCategory.PASSPORT,
                generation = DocumentTypeGeneration.CURRENT_TWO_CHARACTER,
            ),
            DocumentTypeCodeEntry(
                code = "PM",
                displayName = "Military passport",
                category = DocumentCategory.PASSPORT,
                generation = DocumentTypeGeneration.CURRENT_TWO_CHARACTER,
            ),
            // TD1 special-case: per ICAO Doc 9303 Part 5 Appendix B, the document code
            // "AC" is reserved for Crew Member Certificates.
            DocumentTypeCodeEntry(
                code = "AC",
                displayName = "Crew Member Certificate",
                category = DocumentCategory.OTHER,
                generation = DocumentTypeGeneration.CURRENT_TWO_CHARACTER,
            ),
        )

    private val byCode: Map<String, DocumentTypeCodeEntry> = entries.associateBy { it.code }

    /** Returns the entry for [code], or `null` if the code is not in the table. */
    public fun lookup(code: String): DocumentTypeCodeEntry? = byCode[code]

    /** Returns every entry currently in the table, in registration order. */
    public fun all(): List<DocumentTypeCodeEntry> = entries

    /** Returns every entry in the table whose category matches [category]. */
    public fun byCategory(category: DocumentCategory): List<DocumentTypeCodeEntry> = entries.filter { it.category == category }
}
