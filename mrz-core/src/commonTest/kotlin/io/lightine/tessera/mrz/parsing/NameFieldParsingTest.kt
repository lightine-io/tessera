package io.lightine.tessera.mrz.parsing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NameFieldParsingTest {
    // TD3 name field width is 39 characters; helpers below pad to that width.
    private fun td3Padded(content: String): String = content.padEnd(39, '<')

    @Test
    fun parses_simple_primary_and_secondary_separated_by_double_filler() {
        val raw = td3Padded("SPECIMEN<<ALEX<MORGAN")
        val result = parseNameField(raw)
        assertEquals("SPECIMEN", result.primaryIdentifier)
        assertEquals("ALEX MORGAN", result.secondaryIdentifier)
        assertFalse(result.nameTruncated)
    }

    @Test
    fun parses_mononym_with_no_double_filler_separator() {
        // Field contains only the primary identifier (e.g., a single-name document).
        val raw = td3Padded("MADONNA")
        val result = parseNameField(raw)
        assertEquals("MADONNA", result.primaryIdentifier)
        assertEquals("", result.secondaryIdentifier)
        assertFalse(result.nameTruncated)
    }

    @Test
    fun parses_multi_word_primary_with_internal_single_fillers_as_spaces() {
        val raw = td3Padded("VAN<DER<BERG<<JOHN")
        val result = parseNameField(raw)
        assertEquals("VAN DER BERG", result.primaryIdentifier)
        assertEquals("JOHN", result.secondaryIdentifier)
        assertFalse(result.nameTruncated)
    }

    @Test
    fun parses_multi_word_secondary_with_internal_single_fillers_as_spaces() {
        val raw = td3Padded("SPECIMEN<<ALEX<MORGAN<JOSE")
        val result = parseNameField(raw)
        assertEquals("SPECIMEN", result.primaryIdentifier)
        assertEquals("ALEX MORGAN JOSE", result.secondaryIdentifier)
        assertFalse(result.nameTruncated)
    }

    @Test
    fun detects_truncation_when_field_fills_to_boundary_with_no_trailing_filler() {
        // 39 chars exactly, no trailing '<'. ICAO Doc 9303 convention: a complete name
        // always leaves at least one trailing '<', so a full field is treated as truncated.
        val raw = "VERYLONGPRIMARYNAME<<SECONDARYNAMEHERE0"
        assertEquals(39, raw.length)
        assertFalse(raw.endsWith('<'))
        val result = parseNameField(raw)
        assertEquals("VERYLONGPRIMARYNAME", result.primaryIdentifier)
        assertEquals("SECONDARYNAMEHERE0", result.secondaryIdentifier)
        assertTrue(result.nameTruncated)
    }

    @Test
    fun detects_truncation_when_primary_only_fills_entire_field() {
        // A long mononym that fills the field with no '<<' separator.
        val raw = "VERYLONGPRIMARYNAMETHATFILLSITALLUP1234"
        assertEquals(39, raw.length)
        val result = parseNameField(raw)
        assertEquals("VERYLONGPRIMARYNAMETHATFILLSITALLUP1234", result.primaryIdentifier)
        assertEquals("", result.secondaryIdentifier)
        assertTrue(result.nameTruncated)
    }

    @Test
    fun preserves_empty_primary_when_field_starts_with_double_filler() {
        // Malformed but parseable: '<<JOHN' decodes to primary "" and secondary "JOHN".
        val raw = td3Padded("<<JOHN")
        val result = parseNameField(raw)
        assertEquals("", result.primaryIdentifier)
        assertEquals("JOHN", result.secondaryIdentifier)
        assertFalse(result.nameTruncated)
    }

    @Test
    fun preserves_empty_secondary_when_field_ends_with_double_filler_before_padding() {
        // 'SPECIMEN<<' with subsequent padding — secondary is genuinely empty.
        val raw = td3Padded("SPECIMEN<<")
        val result = parseNameField(raw)
        assertEquals("SPECIMEN", result.primaryIdentifier)
        assertEquals("", result.secondaryIdentifier)
        assertFalse(result.nameTruncated)
    }

    @Test
    fun handles_fully_filler_field_as_empty_components_not_truncated() {
        val raw = "<".repeat(39)
        val result = parseNameField(raw)
        assertEquals("", result.primaryIdentifier)
        assertEquals("", result.secondaryIdentifier)
        assertFalse(result.nameTruncated)
    }

    @Test
    fun splits_on_first_double_filler_when_secondary_contains_malformed_extra_double_filler() {
        // Malformed input: '<<' should appear only as the primary/secondary separator.
        // If it appears again within the secondary, only the first occurrence splits;
        // the extra '<<' decodes as double space. No auto-correction.
        val raw = td3Padded("SPECIMEN<<ALEX<<MORGAN")
        val result = parseNameField(raw)
        assertEquals("SPECIMEN", result.primaryIdentifier)
        assertEquals("ALEX  MORGAN", result.secondaryIdentifier)
        assertFalse(result.nameTruncated)
    }

    @Test
    fun apostrophes_and_hyphens_decode_lossily_to_space_per_icao_reverse_mapping() {
        // Real name "O'NEILL" encodes to "O<NEILL" in the MRZ alphabet. The decoded
        // form is "O NEILL" — apostrophes and hyphens are lossy. Raw is preserved on
        // CommonFields.rawNameField for consumers who need to handle this.
        val raw = td3Padded("O<NEILL<<JEAN<PIERRE")
        val result = parseNameField(raw)
        assertEquals("O NEILL", result.primaryIdentifier)
        assertEquals("JEAN PIERRE", result.secondaryIdentifier)
        assertFalse(result.nameTruncated)
    }

    @Test
    fun handles_empty_input_as_empty_components_not_truncated() {
        // Defensive: empty input does not crash and yields empty components.
        val result = parseNameField("")
        assertEquals("", result.primaryIdentifier)
        assertEquals("", result.secondaryIdentifier)
        assertFalse(result.nameTruncated)
    }
}
