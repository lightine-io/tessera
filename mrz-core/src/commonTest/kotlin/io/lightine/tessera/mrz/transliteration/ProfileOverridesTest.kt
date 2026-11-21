package io.lightine.tessera.mrz.transliteration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProfileOverridesTest {
    private val profile =
        IcaoDefaultTransliterationProfile.withOverrides(
            "XYZ",
            mapOf('C' to "J", 'Ç' to "CH", 'ç' to "CH", 'ß' to "B", '-' to "", '1' to "ONE"),
        )

    private fun run(
        p: TransliterationProfile,
        text: String,
    ): String = (p.toMrzAlphabet(text) as TransliterationResult.Success).output

    @Test
    fun identifier_is_reported() {
        assertEquals("XYZ", profile.identifier)
        assertEquals("XYZ", profile.transliterate("A").profileIdentifier)
    }

    @Test
    fun override_wins_over_mrz_alphabet_letter_and_digit() {
        assertEquals("JAJ", run(profile, "CAC"))
        assertEquals("ONE", run(profile, "1"))
    }

    @Test
    fun override_wins_over_annex_g_table() {
        assertEquals("CHA", run(profile, "ÇA"))
        assertEquals("B", run(profile, "ß"))
    }

    @Test
    fun overrides_are_case_sensitive() {
        // 'c' is not overridden, so it follows the ICAO default.
        assertEquals("C", run(profile, "c"))
    }

    @Test
    fun override_can_drop_a_character() {
        assertEquals("ANNAMARIA", run(profile, "ANNA-MARIA"))
    }

    @Test
    fun other_characters_match_the_icao_profile() {
        val sample =
            listOf(
                "ANNA, MARIA",
                "ANNA,MARIA",
                ",LEADING",
                "TRAILING,",
                "O'BRIEN-SMITH",
                "Ä Ö Ü æ Œ Þ Ð Ĳ",
                "Ə ə zażółć gęślą",
                "ABC 023 <<",
                "Ж日本 !?",
                "",
            )
        val plain = IcaoDefaultTransliterationProfile.withOverrides("EMPTY", emptyMap())
        for (text in sample) {
            assertEquals(run(IcaoDefaultTransliterationProfile, text), run(plain, text), text)
        }
        // Characters the overrides above do not touch.
        for (text in listOf("ANNA, MARIA", "ÄÖÜ æ", "Ж日本 !?", "ABD 023")) {
            assertEquals(run(IcaoDefaultTransliterationProfile, text), run(profile, text), text)
        }
    }

    @Test
    fun comma_rule_still_applies() {
        assertEquals("JA<JB", run(profile, "CA, CB"))
    }

    @Test
    fun invalid_override_value_throws() {
        assertFailsWith<IllegalArgumentException> {
            IcaoDefaultTransliterationProfile.withOverrides("XYZ", mapOf('a' to "a"))
        }
        assertFailsWith<IllegalArgumentException> {
            IcaoDefaultTransliterationProfile.withOverrides("XYZ", mapOf('a' to "A B"))
        }
    }

    @Test
    fun blank_identifier_throws() {
        assertFailsWith<IllegalArgumentException> {
            IcaoDefaultTransliterationProfile.withOverrides("", emptyMap())
        }
        assertFailsWith<IllegalArgumentException> {
            IcaoDefaultTransliterationProfile.withOverrides("  ", emptyMap())
        }
    }

    @Test
    fun profile_is_not_registered_automatically() {
        assertNull(TransliterationProfileRegistry.lookup("XYZ"))
    }

    @Test
    fun changing_the_map_afterwards_has_no_effect() {
        val map = mutableMapOf('Q' to "K")
        val p = IcaoDefaultTransliterationProfile.withOverrides("MUT", map)
        map['Q'] = "Z"
        assertEquals("K", run(p, "Q"))
    }
}
