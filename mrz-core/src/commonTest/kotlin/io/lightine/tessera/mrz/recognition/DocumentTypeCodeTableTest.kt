package io.lightine.tessera.mrz.recognition

import io.lightine.tessera.types.vocabulary.DocumentCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentTypeCodeTableTest {
    @Test
    fun lookup_returns_entry_for_legacy_passport_code_p() {
        val entry = DocumentTypeCodeTable.lookup("P")
        assertNotNull(entry)
        assertEquals("Passport", entry.displayName)
        assertEquals(DocumentCategory.PASSPORT, entry.category)
        assertEquals(DocumentTypeGeneration.LEGACY_SINGLE_CHARACTER, entry.generation)
    }

    @Test
    fun lookup_returns_entry_for_current_diplomatic_passport_code_pd() {
        val entry = DocumentTypeCodeTable.lookup("PD")
        assertNotNull(entry)
        assertEquals("Diplomatic passport", entry.displayName)
        assertEquals(DocumentCategory.PASSPORT, entry.category)
        assertEquals(DocumentTypeGeneration.CURRENT_TWO_CHARACTER, entry.generation)
    }

    @Test
    fun lookup_returns_stateless_passport_for_ps_per_part4_4_4() {
        // Per ICAO Doc 9303 Part 4 §4.4: PS is "Stateless passport", NOT "Service passport".
        // The latter is code PO ("Official/service passport").
        val entry = DocumentTypeCodeTable.lookup("PS")
        assertNotNull(entry)
        assertEquals("Stateless passport", entry.displayName)
        assertEquals(DocumentCategory.PASSPORT, entry.category)
        assertEquals(DocumentTypeGeneration.CURRENT_TWO_CHARACTER, entry.generation)
    }

    @Test
    fun lookup_returns_official_service_passport_for_po_per_part4_4_4() {
        val entry = DocumentTypeCodeTable.lookup("PO")
        assertNotNull(entry)
        assertEquals("Official/service passport", entry.displayName)
        assertEquals(DocumentCategory.PASSPORT, entry.category)
        assertEquals(DocumentTypeGeneration.CURRENT_TWO_CHARACTER, entry.generation)
    }

    @Test
    fun lookup_returns_emergency_passport_for_pe_per_part4_4_4() {
        val entry = DocumentTypeCodeTable.lookup("PE")
        assertNotNull(entry)
        assertEquals("Emergency passport", entry.displayName)
        assertEquals(DocumentCategory.PASSPORT, entry.category)
    }

    @Test
    fun lookup_returns_refugee_passport_for_pr_per_part4_4_4() {
        val entry = DocumentTypeCodeTable.lookup("PR")
        assertNotNull(entry)
        assertEquals("Refugee passport", entry.displayName)
        assertEquals(DocumentCategory.PASSPORT, entry.category)
    }

    @Test
    fun lookup_returns_alien_non_citizen_passport_for_pt_per_part4_4_4() {
        val entry = DocumentTypeCodeTable.lookup("PT")
        assertNotNull(entry)
        assertEquals("Alien/Non-citizen passport", entry.displayName)
        assertEquals(DocumentCategory.PASSPORT, entry.category)
    }

    @Test
    fun lookup_returns_laissez_passer_passport_for_pl_per_part4_4_4() {
        val entry = DocumentTypeCodeTable.lookup("PL")
        assertNotNull(entry)
        assertEquals("Laissez-passer passport", entry.displayName)
        assertEquals(DocumentCategory.PASSPORT, entry.category)
    }

    @Test
    fun lookup_returns_military_passport_for_pm_per_part4_4_4() {
        val entry = DocumentTypeCodeTable.lookup("PM")
        assertNotNull(entry)
        assertEquals("Military passport", entry.displayName)
        assertEquals(DocumentCategory.PASSPORT, entry.category)
    }

    @Test
    fun lookup_returns_crew_member_certificate_for_ac_per_part5_appendix_b() {
        // Per ICAO Doc 9303 Part 5 Appendix B: AC is reserved for Crew Member Certificates.
        // Category OTHER because it doesn't fit PASSPORT / IDENTITY_CARD / RESIDENCE_PERMIT / VISA.
        val entry = DocumentTypeCodeTable.lookup("AC")
        assertNotNull(entry)
        assertEquals("Crew Member Certificate", entry.displayName)
        assertEquals(DocumentCategory.OTHER, entry.category)
        assertEquals(DocumentTypeGeneration.CURRENT_TWO_CHARACTER, entry.generation)
    }

    @Test
    fun lookup_returns_null_for_unrecognized_code() {
        assertNull(DocumentTypeCodeTable.lookup("XX"))
    }

    @Test
    fun lookup_is_case_sensitive_per_icao_alphabet() {
        // MRZ alphabet is uppercase only; lowercase 'p' is not a valid MRZ document type code.
        assertNull(DocumentTypeCodeTable.lookup("p"))
    }

    @Test
    fun all_returns_every_entry_in_the_starter_set() {
        // Legacy single-character: P, V, I.
        // Current two-character P-prefix per Part 4 §4.4: PP, PE, PD, PO, PR, PT, PS, PL, PM.
        // TD1 special: AC (Crew Member Certificate per Part 5 Appendix B).
        // TD1/TD2 state-specific A-/C-/I-prefix variants are intentionally not enumerated.
        val codes = DocumentTypeCodeTable.all().map { it.code }.toSet()
        assertEquals(
            setOf("P", "V", "I", "PP", "PE", "PD", "PO", "PR", "PT", "PS", "PL", "PM", "AC"),
            codes,
        )
    }

    @Test
    fun by_category_passport_returns_all_passport_entries() {
        val passportCodes = DocumentTypeCodeTable.byCategory(DocumentCategory.PASSPORT).map { it.code }.toSet()
        assertEquals(
            setOf("P", "PP", "PE", "PD", "PO", "PR", "PT", "PS", "PL", "PM"),
            passportCodes,
        )
    }

    @Test
    fun by_category_visa_returns_only_visa_entries() {
        val visaEntries = DocumentTypeCodeTable.byCategory(DocumentCategory.VISA)
        assertEquals(1, visaEntries.size)
        assertEquals("V", visaEntries.single().code)
    }

    @Test
    fun by_category_identity_card_returns_only_identity_card_entries() {
        val idEntries = DocumentTypeCodeTable.byCategory(DocumentCategory.IDENTITY_CARD)
        assertEquals(1, idEntries.size)
        assertEquals("I", idEntries.single().code)
    }

    @Test
    fun by_category_other_returns_crew_member_certificate() {
        val otherEntries = DocumentTypeCodeTable.byCategory(DocumentCategory.OTHER)
        assertEquals(1, otherEntries.size)
        assertEquals("AC", otherEntries.single().code)
    }

    @Test
    fun by_category_residence_permit_returns_empty_list_in_starter_set() {
        // Starter set still has no residence permit codes; this lock surfaces explicitly when the set is expanded.
        assertTrue(DocumentTypeCodeTable.byCategory(DocumentCategory.RESIDENCE_PERMIT).isEmpty())
    }

    @Test
    fun broad_category_agrees_with_document_type_for_every_table_code() {
        for (entry in DocumentTypeCodeTable.all()) {
            assertEquals(DocumentType(entry.code).broadCategory, DocumentTypeCodeTable.broadCategory(entry.code), entry.code)
            assertEquals(entry.category, DocumentTypeCodeTable.broadCategory(entry.code), entry.code)
        }
    }

    @Test
    fun broad_category_agrees_with_document_type_for_unknown_empty_lowercase_and_filler_codes() {
        val codes = listOf("", " ", "<", "<<", "IA", "AX", "CX", "VX", "PX", "XY", "p", "ia", "v", "pp", "ac", "Ac", "1P", "<P")
        for (code in codes) {
            assertEquals(DocumentType(code).broadCategory, DocumentTypeCodeTable.broadCategory(code), "code '$code'")
        }
    }

    @Test
    fun broad_category_gives_the_reserved_leading_character_answers() {
        assertEquals(DocumentCategory.PASSPORT, DocumentTypeCodeTable.broadCategory("PX"))
        assertEquals(DocumentCategory.IDENTITY_CARD, DocumentTypeCodeTable.broadCategory("IA"))
        assertEquals(DocumentCategory.VISA, DocumentTypeCodeTable.broadCategory("vx"))
        assertEquals(DocumentCategory.OTHER, DocumentTypeCodeTable.broadCategory("AC"))
        assertNull(DocumentTypeCodeTable.broadCategory(""))
        assertNull(DocumentTypeCodeTable.broadCategory("<<"))
        assertNull(DocumentTypeCodeTable.broadCategory("XY"))
    }
}
