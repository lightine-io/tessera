package io.lightine.tessera.mrz.recognition

import io.lightine.tessera.types.vocabulary.DocumentCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentTypeTest {
    @Test
    fun raw_code_is_preserved_verbatim_for_recognized_code() {
        assertEquals("P", DocumentType("P").rawCode)
    }

    @Test
    fun raw_code_is_preserved_verbatim_for_unrecognized_code() {
        assertEquals("XX", DocumentType("XX").rawCode)
    }

    @Test
    fun is_recognized_is_true_when_code_is_in_lookup_table() {
        assertTrue(DocumentType("P").isRecognized)
        assertTrue(DocumentType("PD").isRecognized)
    }

    @Test
    fun is_recognized_is_false_when_code_is_not_in_lookup_table() {
        assertFalse(DocumentType("XX").isRecognized)
    }

    @Test
    fun category_resolves_via_lookup_table_for_recognized_code() {
        assertEquals(DocumentCategory.PASSPORT, DocumentType("P").category)
        assertEquals(DocumentCategory.VISA, DocumentType("V").category)
    }

    @Test
    fun category_is_null_for_unrecognized_code() {
        assertNull(DocumentType("XX").category)
    }

    @Test
    fun entry_resolves_via_lookup_table_for_recognized_code() {
        val entry = DocumentType("PD").entry
        assertNotNull(entry)
        assertEquals("Diplomatic passport", entry.displayName)
        assertEquals(DocumentTypeGeneration.CURRENT_TWO_CHARACTER, entry.generation)
    }

    @Test
    fun entry_is_null_for_unrecognized_code() {
        assertNull(DocumentType("XX").entry)
    }

    @Test
    fun two_document_types_with_same_raw_code_are_equal() {
        assertEquals(DocumentType("P"), DocumentType("P"))
    }

    @Test
    fun preserves_empty_raw_code_verbatim_without_recognizing_it() {
        val empty = DocumentType("")
        assertEquals("", empty.rawCode)
        assertFalse(empty.isRecognized)
    }

    // ----------------------------------------------------------------------------------------------------
    // TES-99: broadCategory — category widened with the ICAO reserved-leading-character fallback so a
    // spec-conformant code the exact-match table does not enumerate (e.g. a TD1/TD2 qualified code like
    // "IA") still resolves to a category instead of null.
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun broad_category_matches_category_for_a_recognized_passport_code() {
        assertEquals(DocumentCategory.PASSPORT, DocumentType("P").broadCategory)
    }

    @Test
    fun broad_category_falls_back_to_first_letter_for_an_unqualified_identity_card_code() {
        assertEquals(DocumentCategory.IDENTITY_CARD, DocumentType("I").broadCategory)
    }

    @Test
    fun broad_category_falls_back_to_first_letter_for_a_qualified_identity_card_code() {
        // "IA" is not an exact match in DocumentTypeCodeTable (the second character is issuer-specific),
        // but 'I' is an ICAO-reserved leading character for TD1/TD2 identity-family documents.
        assertEquals(DocumentCategory.IDENTITY_CARD, DocumentType("IA").broadCategory)
    }

    @Test
    fun broad_category_falls_back_to_first_letter_for_a_and_c_prefixed_codes() {
        assertEquals(DocumentCategory.IDENTITY_CARD, DocumentType("AX").broadCategory)
        assertEquals(DocumentCategory.IDENTITY_CARD, DocumentType("CX").broadCategory)
    }

    @Test
    fun broad_category_resolves_visa_for_an_unqualified_and_a_qualified_code() {
        assertEquals(DocumentCategory.VISA, DocumentType("V").broadCategory)
        assertEquals(DocumentCategory.VISA, DocumentType("VX").broadCategory)
    }

    @Test
    fun broad_category_is_null_for_an_unreserved_leading_character() {
        assertNull(DocumentType("XY").broadCategory)
    }

    @Test
    fun broad_category_is_null_for_an_empty_or_blank_raw_code() {
        assertNull(DocumentType("").broadCategory)
        assertNull(DocumentType(" ").broadCategory)
    }

    @Test
    fun broad_category_is_case_insensitive_on_the_first_character() {
        assertEquals(DocumentCategory.PASSPORT, DocumentType("p").broadCategory)
        assertEquals(DocumentCategory.IDENTITY_CARD, DocumentType("ia").broadCategory)
        assertEquals(DocumentCategory.VISA, DocumentType("v").broadCategory)
    }
}
