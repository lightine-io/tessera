package io.lightine.tessera.mrz.transliteration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NormalizationTest {
    @Test
    fun empty_input_returns_empty_string() {
        assertEquals("", normalizeForTransliteration(""))
    }

    @Test
    fun ascii_input_is_unchanged() {
        val input = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        assertEquals(input, normalizeForTransliteration(input))
    }

    @Test
    fun mrz_alphabet_including_filler_is_unchanged() {
        val input = "P<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<"
        assertEquals(input, normalizeForTransliteration(input))
    }

    @Test
    fun nfc_precomposed_input_is_unchanged() {
        val precomposed = "é" // é as single code point
        assertEquals(precomposed, normalizeForTransliteration(precomposed))
    }

    @Test
    fun nfd_decomposed_input_is_recomposed_to_nfc() {
        val decomposed = "é" // e + combining acute accent
        val precomposed = "é" // é as single code point
        assertNotEquals(precomposed, decomposed)
        assertEquals(precomposed, normalizeForTransliteration(decomposed))
    }

    @Test
    fun canonically_equivalent_forms_produce_equal_output() {
        val precomposed = "ñ" // ñ as single code point
        val decomposed = "ñ" // n + combining tilde
        assertEquals(
            normalizeForTransliteration(precomposed),
            normalizeForTransliteration(decomposed),
        )
    }

    @Test
    fun normalization_is_idempotent() {
        val input = "Café näive" // mixed NFD diacritics
        val once = normalizeForTransliteration(input)
        val twice = normalizeForTransliteration(once)
        assertEquals(once, twice)
    }

    @Test
    fun mixed_precomposed_and_decomposed_in_one_string_normalizes_to_nfc() {
        val mixed = "Ä" + "Ö" // Ä (NFC) + Ö (NFD)
        val expected = "ÄÖ" // ÄÖ both NFC
        assertEquals(expected, normalizeForTransliteration(mixed))
    }

    @Test
    fun sharp_s_has_no_canonical_decomposition_and_is_unchanged() {
        // ß (U+00DF) has only a compatibility decomposition (NFKD → "SS"),
        // not a canonical one. NFC normalization must leave it alone.
        val sharpS = "ß"
        assertEquals(sharpS, normalizeForTransliteration(sharpS))
    }

    @Test
    fun schwa_is_a_single_code_point_in_nfc() {
        val upperSchwa = "Ə" // Ə — referenced in transliteration.md
        val lowerSchwa = "ə" // ə
        assertEquals(upperSchwa, normalizeForTransliteration(upperSchwa))
        assertEquals(lowerSchwa, normalizeForTransliteration(lowerSchwa))
    }

    @Test
    fun multiple_combining_marks_compose_to_nfc_when_a_precomposed_form_exists() {
        val decomposed = "ü" // u + combining diaeresis
        val precomposed = "ü" // ü
        assertEquals(precomposed, normalizeForTransliteration(decomposed))
    }
}
