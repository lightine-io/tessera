package io.lightine.tessera.mrz.transliteration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IcaoDefaultTransliterationProfileTest {
    @Test
    fun identifier_is_ICAO() {
        assertEquals("ICAO", IcaoDefaultTransliterationProfile.identifier)
        assertEquals("ICAO", IcaoDefaultTransliterationProfile.IDENTIFIER)
    }

    @Test
    fun empty_input_returns_empty_success() {
        val result = IcaoDefaultTransliterationProfile.toMrzAlphabet("")
        assertEquals(TransliterationResult.Success(""), result)
    }

    @Test
    fun mrz_alphabet_passes_through_unchanged() {
        val input = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<"
        val result = IcaoDefaultTransliterationProfile.toMrzAlphabet(input)
        assertEquals(TransliterationResult.Success(input), result)
    }

    @Test
    fun lowercase_latin_uppercases() {
        val result = IcaoDefaultTransliterationProfile.toMrzAlphabet("abcdefghijklmnopqrstuvwxyz")
        assertEquals(TransliterationResult.Success("ABCDEFGHIJKLMNOPQRSTUVWXYZ"), result)
    }

    @Test
    fun diacritics_use_no_expansion_convention() {
        // ä → A, ö → O, ü → U (not AE/OE/UE)
        val result = IcaoDefaultTransliterationProfile.toMrzAlphabet("Müller")
        assertEquals(TransliterationResult.Success("MULLER"), result)
    }

    @Test
    fun accented_e_strips_to_e() {
        val result = IcaoDefaultTransliterationProfile.toMrzAlphabet("café")
        assertEquals(TransliterationResult.Success("CAFE"), result)
    }

    @Test
    fun n_with_tilde_strips_to_n() {
        val result = IcaoDefaultTransliterationProfile.toMrzAlphabet("español")
        assertEquals(TransliterationResult.Success("ESPANOL"), result)
    }

    @Test
    fun aesc_ligature_expands_to_AE() {
        val result = IcaoDefaultTransliterationProfile.toMrzAlphabet("Æther")
        assertEquals(TransliterationResult.Success("AETHER"), result)
    }

    @Test
    fun oe_ligature_expands_to_OE() {
        val result = IcaoDefaultTransliterationProfile.toMrzAlphabet("Œuvre")
        assertEquals(TransliterationResult.Success("OEUVRE"), result)
    }

    @Test
    fun sharp_s_expands_to_SS() {
        val result = IcaoDefaultTransliterationProfile.toMrzAlphabet("Straße")
        assertEquals(TransliterationResult.Success("STRASSE"), result)
    }

    @Test
    fun thorn_expands_to_TH() {
        val result = IcaoDefaultTransliterationProfile.toMrzAlphabet("Þórr")
        assertEquals(TransliterationResult.Success("THORR"), result)
    }

    @Test
    fun eth_maps_to_D() {
        val result = IcaoDefaultTransliterationProfile.toMrzAlphabet("Ðagr")
        assertEquals(TransliterationResult.Success("DAGR"), result)
    }

    @Test
    fun ij_ligature_expands_to_IJ() {
        val result = IcaoDefaultTransliterationProfile.toMrzAlphabet("Ĳsselmeer")
        assertEquals(TransliterationResult.Success("IJSSELMEER"), result)
    }

    @Test
    fun schwa_falls_through_to_filler_per_icao_annex_g_absence() {
        // ICAO Doc 9303 Part 3 Annex G's Latin table covers U+00C0-00DE,
        // U+0100-017D, and U+1E9E. The Latin schwa (U+018F / U+0259) is in
        // Latin Extended-B (U+0180-024F), entirely outside Annex G's scope.
        // The ICAO default profile therefore falls through to filler.
        // Country-specific profiles (e.g., AzeTransliterationProfile) apply
        // their own override per ADR-009.
        assertEquals(
            TransliterationResult.Success("<"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("Ə"),
        )
        assertEquals(
            TransliterationResult.Success("<"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("ə"),
        )
    }

    @Test
    fun apostrophe_is_omitted_per_icao_part_3_section_4_6() {
        // Doc 9303 Part 3 §4.6: an apostrophe is dropped and the name parts it separated
        // are joined, with no filler (its example: D'ARTAGNAN becomes DARTAGNAN).
        assertEquals(
            TransliterationResult.Success("DARTAGNAN"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("D'ARTAGNAN"),
        )
        assertEquals(
            TransliterationResult.Success("OCONNOR"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("O'CONNOR"),
        )
        // Right single quotation mark (U+2019) — commonly typed apostrophe variant.
        assertEquals(
            TransliterationResult.Success("DARTAGNAN"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("D’ARTAGNAN"),
        )
    }

    @Test
    fun common_punctuation_is_omitted_per_icao_part_3_section_4_6() {
        // Doc 9303 Part 3 §4.6: other punctuation is dropped from the MRZ, with no
        // filler in its place.
        assertEquals(
            TransliterationResult.Success("ABC"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("A.B;C"),
        )
        assertEquals(
            TransliterationResult.Success("AB"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("A!B?"),
        )
        assertEquals(
            TransliterationResult.Success("AB"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("A(B)"),
        )
    }

    @Test
    fun comma_between_name_components_is_one_filler_per_icao_part_3_section_4_6() {
        // Spec example: VIZ "ANNA, MARIA" is MRZ "ANNA<MARIA". The space is not what makes the filler.
        for (input in listOf("ANNA, MARIA", "ANNA,MARIA", "ANNA ,MARIA", "ANNA , MARIA", "ANNA,  MARIA", "ANNA,,MARIA")) {
            assertEquals(
                TransliterationResult.Success("ANNA<MARIA"),
                IcaoDefaultTransliterationProfile.toMrzAlphabet(input),
                "input: $input",
            )
        }
    }

    @Test
    fun comma_that_separates_nothing_is_dropped() {
        for (input in listOf(",ANNA", "ANNA,", "ANNA, ", "ANNA,'", "',ANNA")) {
            assertEquals(
                TransliterationResult.Success("ANNA"),
                IcaoDefaultTransliterationProfile.toMrzAlphabet(input),
                "input: $input",
            )
        }
        assertEquals(TransliterationResult.Success(""), IcaoDefaultTransliterationProfile.toMrzAlphabet(","))
        // A leading space is a filler, as before; the comma in front of it adds nothing.
        assertEquals(TransliterationResult.Success("<ANNA"), IcaoDefaultTransliterationProfile.toMrzAlphabet(", ANNA"))
    }

    @Test
    fun hyphen_falls_through_to_filler_per_icao_part_3_section_4_6() {
        // Doc 9303 Part 3 §4.6: a hyphen between two name components becomes one
        // filler character (<).
        // Hyphen is intentionally NOT in the punctuation-omitted set; it uses
        // the unmapped-character fallback to filler.
        assertEquals(
            TransliterationResult.Success("MARIE<ELISE"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("MARIE-ELISE"),
        )
        assertEquals(
            TransliterationResult.Success("SMITH<JONES"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("SMITH-JONES"),
        )
    }

    @Test
    fun unmapped_character_falls_back_to_filler() {
        // Cyrillic letter outside the starter table.
        val result = IcaoDefaultTransliterationProfile.toMrzAlphabet("Я")
        assertEquals(TransliterationResult.Success("<"), result)
    }

    @Test
    fun space_falls_back_to_filler() {
        val result = IcaoDefaultTransliterationProfile.toMrzAlphabet("A B")
        assertEquals(TransliterationResult.Success("A<B"), result)
    }

    @Test
    fun icao_default_never_returns_failure() {
        val result = IcaoDefaultTransliterationProfile.toMrzAlphabet("anything including ✓ and ☃")
        assertIs<TransliterationResult.Success>(result)
    }

    @Test
    fun eng_maps_to_N_per_annex_g_uppercase_and_lowercase() {
        // Annex G entry: 014A Ŋ Eng → N. Verifies the gap closed in the
        // 2026-05-18 pre-tag conformance pass (F5).
        assertEquals(
            TransliterationResult.Success("N"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("Ŋ"),
        )
        assertEquals(
            TransliterationResult.Success("N"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("ŋ"),
        )
    }

    @Test
    fun capital_sharp_s_maps_to_SS_per_annex_g() {
        // Annex G entry: 1E9E ẞ double s (Germany) → SS. The lowercase ß was
        // already handled; the uppercase variant U+1E9E was the gap closed
        // in the 2026-05-18 pre-tag conformance pass (F5).
        assertEquals(
            TransliterationResult.Success("SS"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("ẞ"),
        )
        assertEquals(
            TransliterationResult.Success("SS"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("ß"),
        )
        // STRAẞE (uppercase) should produce STRASSE.
        assertEquals(
            TransliterationResult.Success("STRASSE"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("STRAẞE"),
        )
    }

    @Test
    fun eth_u00d0_and_d_stroke_u0110_both_map_to_D() {
        // Annex G has both 00D0 (Ð "Eth", Icelandic) and 0110 (Đ "D stroke",
        // Croatian/Vietnamese/Sami) mapping to D. These look visually similar
        // but are distinct Unicode codepoints. F13 verifies both are handled.
        // ASCII-cast to make the codepoint difference explicit.
        assertEquals('Ð', 'Ð')
        assertEquals('Đ', 'Đ')
        assertEquals(
            TransliterationResult.Success("D"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("Ð"),
        )
        assertEquals(
            TransliterationResult.Success("D"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("Đ"),
        )
        assertEquals(
            TransliterationResult.Success("D"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("ð"),
        )
        assertEquals(
            TransliterationResult.Success("D"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("đ"),
        )
    }

    @Test
    fun mixed_diacritic_and_expansion_in_one_input() {
        val result = IcaoDefaultTransliterationProfile.toMrzAlphabet("Müßig")
        assertEquals(TransliterationResult.Success("MUSSIG"), result)
    }
}
