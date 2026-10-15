package io.lightine.tessera.mrz.recognition

import io.lightine.tessera.types.vocabulary.DocumentCategory

/**
 * The document type codes the SDK recognizes: `P`, `V`, `I`, `AC`, and the two-character `P` codes.
 *
 * The table holds `PP`, `PE`, `PD`, `PO`, `PR`, `PT`, `PS`, `PL`, and `PM`. It does not list
 * issuer-specific TD1 and TD2 codes such as `IA`. The validator reports a code that is not in the
 * table as an `MrzUnknownDocumentTypeCode` warning.
 *
 * @since 0.1.0
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

    /**
     * Returns the entry for a code.
     *
     * @param code The code to look up. The match is exact and case-sensitive.
     * @return The entry, or `null` if the table has none.
     */
    public fun lookup(code: String): DocumentTypeCodeEntry? = byCode[code]

    /** Returns every entry in the table, in table order. */
    public fun all(): List<DocumentTypeCodeEntry> = entries

    /**
     * Returns the entries of one category.
     *
     * @param category The category to select.
     * @return The matching entries, in table order.
     */
    public fun byCategory(category: DocumentCategory): List<DocumentTypeCodeEntry> = entries.filter { it.category == category }
}
