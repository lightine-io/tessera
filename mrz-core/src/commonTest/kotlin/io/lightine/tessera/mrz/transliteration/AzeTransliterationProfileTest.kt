package io.lightine.tessera.mrz.transliteration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class AzeTransliterationProfileTest {
    @Test
    fun identifier_is_AZE() {
        assertEquals("AZE", AzeTransliterationProfile.identifier)
        assertEquals("AZE", AzeTransliterationProfile.IDENTIFIER)
    }

    @Test
    fun empty_input_returns_empty_success() {
        val result = AzeTransliterationProfile.toMrzAlphabet("")
        assertEquals(TransliterationResult.Success(""), result)
    }

    @Test
    fun mrz_alphabet_passes_through_unchanged_except_aze_overrides() {
        // A through Z minus the AZE-override letters (C, J, Q, X), plus digits and filler.
        // The four override letters are tested separately below — they intentionally do
        // NOT pass through under the AZE profile.
        val input = "ABDEFGHIKLMNOPRSTUVWYZ0123456789<"
        val result = AzeTransliterationProfile.toMrzAlphabet(input)
        assertEquals(TransliterationResult.Success(input), result)
    }

    @Test
    fun schwa_maps_to_A_via_bgn_pcgn_and_icao_chain() {
        // The original load-bearing override per ADR-009 + Phase 4 verification:
        // BGN/PCGN 1993 Agreement Note 1 says schwa's fallback when unreproducible is Ä;
        // ICAO Annex G under no-expansion maps Ä → A. The chained substitution
        // Ə → Ä → A matches observed AZE practice. The ALA-LC chain reaches the same
        // result via Ə → ă → A (strip the breve).
        assertEquals(
            TransliterationResult.Success("A"),
            AzeTransliterationProfile.toMrzAlphabet("Ə"),
        )
        assertEquals(
            TransliterationResult.Success("A"),
            AzeTransliterationProfile.toMrzAlphabet("ə"),
        )
        // ICAO default has no schwa entry — falls through to filler.
        assertEquals(
            TransliterationResult.Success("<"),
            IcaoDefaultTransliterationProfile.toMrzAlphabet("Ə"),
        )
    }

    @Test
    fun ch_override_for_c_cedilla() {
        // Ç → CH (was C under inherited Annex G no-expansion).
        // ALA-LC chain: Ç → ch → CH.
        assertEquals(TransliterationResult.Success("CH"), AzeTransliterationProfile.toMrzAlphabet("Ç"))
        assertEquals(TransliterationResult.Success("CH"), AzeTransliterationProfile.toMrzAlphabet("ç"))
        // Diverges from ICAO default.
        assertEquals(TransliterationResult.Success("C"), IcaoDefaultTransliterationProfile.toMrzAlphabet("Ç"))
    }

    @Test
    fun gh_override_for_g_breve() {
        // Ğ → GH (was G under inherited no-expansion). ALA-LC: Ğ → gh.
        assertEquals(TransliterationResult.Success("GH"), AzeTransliterationProfile.toMrzAlphabet("Ğ"))
        assertEquals(TransliterationResult.Success("GH"), AzeTransliterationProfile.toMrzAlphabet("ğ"))
        assertEquals(TransliterationResult.Success("G"), IcaoDefaultTransliterationProfile.toMrzAlphabet("Ğ"))
    }

    @Test
    fun sh_override_for_s_cedilla() {
        // Ş → SH (was S under inherited no-expansion). ALA-LC: Ş → sh.
        assertEquals(TransliterationResult.Success("SH"), AzeTransliterationProfile.toMrzAlphabet("Ş"))
        assertEquals(TransliterationResult.Success("SH"), AzeTransliterationProfile.toMrzAlphabet("ş"))
        assertEquals(TransliterationResult.Success("S"), IcaoDefaultTransliterationProfile.toMrzAlphabet("Ş"))
    }

    @Test
    fun kh_override_for_x_already_in_mrz_alphabet() {
        // X is in the MRZ alphabet (A-Z) and would normally pass through. AZE overrides
        // it because the source phoneme is /x/ (velar fricative), not the English X
        // sound. ALA-LC: X → kh.
        assertEquals(TransliterationResult.Success("KH"), AzeTransliterationProfile.toMrzAlphabet("X"))
        assertEquals(TransliterationResult.Success("KH"), AzeTransliterationProfile.toMrzAlphabet("x"))
        // ICAO default passes X through unchanged (no source-phoneme assumption).
        assertEquals(TransliterationResult.Success("X"), IcaoDefaultTransliterationProfile.toMrzAlphabet("X"))
    }

    @Test
    fun j_override_for_c_already_in_mrz_alphabet() {
        // C is in the MRZ alphabet but AZE's C is phonetically /dʒ/ (English "j"),
        // so it overrides to J. ALA-LC doesn't list C explicitly (it's a Latin letter)
        // but observed AZE practice is consistent with this phonetic mapping.
        assertEquals(TransliterationResult.Success("J"), AzeTransliterationProfile.toMrzAlphabet("C"))
        assertEquals(TransliterationResult.Success("J"), AzeTransliterationProfile.toMrzAlphabet("c"))
        assertEquals(TransliterationResult.Success("C"), IcaoDefaultTransliterationProfile.toMrzAlphabet("C"))
    }

    @Test
    fun zh_override_for_j_already_in_mrz_alphabet() {
        // AZE J is phonetically /ʒ/ (English "zh"), not the English J sound. Override
        // to ZH. Source-phoneme reasoning + observed AZE name practice.
        assertEquals(TransliterationResult.Success("ZH"), AzeTransliterationProfile.toMrzAlphabet("J"))
        assertEquals(TransliterationResult.Success("ZH"), AzeTransliterationProfile.toMrzAlphabet("j"))
        assertEquals(TransliterationResult.Success("J"), IcaoDefaultTransliterationProfile.toMrzAlphabet("J"))
    }

    @Test
    fun g_override_for_q_already_in_mrz_alphabet() {
        // AZE Q is phonetically /g/ (voiced velar stop, English "g"), so it overrides
        // to G. ALA-LC: Q → ġ (which strips to G).
        assertEquals(TransliterationResult.Success("G"), AzeTransliterationProfile.toMrzAlphabet("Q"))
        assertEquals(TransliterationResult.Success("G"), AzeTransliterationProfile.toMrzAlphabet("q"))
        assertEquals(TransliterationResult.Success("Q"), IcaoDefaultTransliterationProfile.toMrzAlphabet("Q"))
    }

    @Test
    fun aze_inherited_icao_mappings_for_letters_without_override() {
        // Letters where AZE inherits the ICAO Annex G default — verified against the
        // empirical sample documents during the pre-`0.1.0` audit (2026-05-19).
        assertEquals(TransliterationResult.Success("I"), AzeTransliterationProfile.toMrzAlphabet("İ"))
        assertEquals(TransliterationResult.Success("I"), AzeTransliterationProfile.toMrzAlphabet("ı"))
        assertEquals(TransliterationResult.Success("I"), AzeTransliterationProfile.toMrzAlphabet("I"))
        assertEquals(TransliterationResult.Success("O"), AzeTransliterationProfile.toMrzAlphabet("Ö"))
        assertEquals(TransliterationResult.Success("O"), AzeTransliterationProfile.toMrzAlphabet("ö"))
        assertEquals(TransliterationResult.Success("U"), AzeTransliterationProfile.toMrzAlphabet("Ü"))
        assertEquals(TransliterationResult.Success("U"), AzeTransliterationProfile.toMrzAlphabet("ü"))
    }

    @Test
    fun letters_with_no_aze_override_match_icao_output() {
        // For input that contains only AZE-inheriting letters (no Ç, Ğ, Ş, X, C, J, Q,
        // or Ə), AZE and ICAO must produce identical output. Confirms the AZE profile
        // doesn't accidentally diverge on letters it isn't supposed to.
        val sample = "Müller Straße Æther Œuvre Þórr"
        assertEquals(
            IcaoDefaultTransliterationProfile.toMrzAlphabet(sample),
            AzeTransliterationProfile.toMrzAlphabet(sample),
        )
    }

    @Test
    fun letters_with_aze_override_diverge_from_icao() {
        // Inverse of the previous test: input containing each AZE override should
        // produce output that differs from ICAO default in the override positions.
        val sample = "ÇĞŞXCJQƏ"
        val aze = AzeTransliterationProfile.toMrzAlphabet(sample)
        val icao = IcaoDefaultTransliterationProfile.toMrzAlphabet(sample)
        assertNotEquals(aze, icao)
        // Exact AZE output: CH GH SH KH J ZH G A → "CHGHSHKHJZHGA".
        assertEquals(TransliterationResult.Success("CHGHSHKHJZHGA"), aze)
    }

    @Test
    fun phonetic_anglicization_chain_for_multi_override_input() {
        // Multi-letter input combining several AZE overrides with schwa.
        // ÇQXJ traces: Ç → CH, Q → G, X → KH, J → ZH. Result: CHGKHZH (7 chars).
        assertEquals(
            TransliterationResult.Success("CHGKHZH"),
            AzeTransliterationProfile.toMrzAlphabet("ÇQXJ"),
        )
        // CƏÇ traces: C → J, Ə → A, Ç → CH. Result: JACH.
        assertEquals(
            TransliterationResult.Success("JACH"),
            AzeTransliterationProfile.toMrzAlphabet("CƏÇ"),
        )
    }

    @Test
    fun mixed_schwa_and_diacritic_uses_aze_schwa_override() {
        // The original mixed-input test, still valid: ü stays U (Annex G inherits),
        // ə becomes A (schwa override). No C/J/Q/X present, so this exercises only
        // the original override path.
        val result = AzeTransliterationProfile.toMrzAlphabet("Müllər")
        assertEquals(TransliterationResult.Success("MULLAR"), result)
    }

    @Test
    fun override_precedes_mrz_alphabet_passthrough() {
        // Architectural guarantee: the override map is consulted before the
        // MRZ-alphabet passthrough check. This is what makes the C → J, J → ZH,
        // Q → G, X → KH overrides possible (those source letters are already in the
        // MRZ alphabet). Lock the contract so future refactors don't silently revert.
        assertEquals(TransliterationResult.Success("J"), AzeTransliterationProfile.toMrzAlphabet("C"))
        assertEquals(TransliterationResult.Success("ZH"), AzeTransliterationProfile.toMrzAlphabet("J"))
        assertEquals(TransliterationResult.Success("G"), AzeTransliterationProfile.toMrzAlphabet("Q"))
        assertEquals(TransliterationResult.Success("KH"), AzeTransliterationProfile.toMrzAlphabet("X"))
    }

    @Test
    fun unmapped_character_falls_back_to_filler() {
        // Cyrillic letter — outside the table for both profiles.
        val result = AzeTransliterationProfile.toMrzAlphabet("Я")
        assertEquals(TransliterationResult.Success("<"), result)
    }

    @Test
    fun aze_profile_never_returns_failure() {
        val result = AzeTransliterationProfile.toMrzAlphabet("anything including ✓ and ☃")
        assertIs<TransliterationResult.Success>(result)
    }

    @Test
    fun aze_is_pre_registered_in_registry() {
        val looked = TransliterationProfileRegistry.lookup(AzeTransliterationProfile.IDENTIFIER)
        assertEquals(AzeTransliterationProfile, looked)
    }

    @Test
    fun comma_between_name_components_is_one_filler_per_icao_part_3_section_4_6() {
        // The components avoid C and X, which this profile writes as J and KH.
        for (input in listOf("ADAM, MORGAN", "ADAM,MORGAN")) {
            assertEquals(
                TransliterationResult.Success("ADAM<MORGAN"),
                AzeTransliterationProfile.toMrzAlphabet(input),
                "input: $input",
            )
        }
    }
}
