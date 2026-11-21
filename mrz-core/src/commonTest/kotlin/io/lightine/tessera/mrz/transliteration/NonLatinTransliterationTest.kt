package io.lightine.tessera.mrz.transliteration

import io.lightine.tessera.mrz.parsing.isMrzAlphabetCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The Cyrillic and Arabic-script tables of ICAO Doc 9303 Part 3 (8th edition), Sections 6.B and
 * 6.C, in the ICAO default profile and the Cyrillic language profiles. Names are synthetic: the
 * project specimen ALEX MORGAN written in each script, and made-up words. The standard's own
 * printed examples are in the conformance tests.
 */
class NonLatinTransliterationTest {
    private fun run(
        profile: TransliterationProfile,
        text: String,
    ): String = assertIs<TransliterationResult.Success>(profile.transliterate(text).result).output

    private fun icao(text: String): String = run(IcaoDefaultTransliterationProfile, text)

    // Section 6.B, main values, in the table's order: Unicode value of the capital, then the value.
    private val cyrillicTable: List<Pair<Char, String>> =
        listOf(
            'Ё' to "E",
            'Ђ' to "D",
            'Є' to "IE",
            'Ѕ' to "DZ",
            'І' to "I",
            'Ї' to "I",
            'Ј' to "J",
            'Љ' to "LJ",
            'Њ' to "NJ",
            'Ќ' to "K",
            'Ў' to "U",
            'Џ' to "DZ",
            'А' to "A",
            'Б' to "B",
            'В' to "V",
            'Г' to "G",
            'Д' to "D",
            'Е' to "E",
            'Ж' to "ZH",
            'З' to "Z",
            'И' to "I",
            'Й' to "I",
            'К' to "K",
            'Л' to "L",
            'М' to "M",
            'Н' to "N",
            'О' to "O",
            'П' to "P",
            'Р' to "R",
            'С' to "S",
            'Т' to "T",
            'У' to "U",
            'Ф' to "F",
            'Х' to "KH",
            'Ц' to "TS",
            'Ч' to "CH",
            'Ш' to "SH",
            'Щ' to "SHCH",
            'Ъ' to "IE",
            'Ы' to "Y",
            'Э' to "E",
            'Ю' to "IU",
            'Я' to "IA",
            'Ѫ' to "U",
            'Ѵ' to "Y",
            'Ґ' to "G",
            'Ғ' to "G",
            'Һ' to "C",
        )

    @Test
    fun every_cyrillic_capital_and_its_small_letter_follow_the_main_value() {
        for ((capital, expected) in cyrillicTable) {
            assertEquals(expected, icao(capital.toString()), "U+${capital.code.toString(16)}")
            assertEquals(expected, icao(capital.lowercaseChar().toString()), "small of U+${capital.code.toString(16)}")
        }
    }

    @Test
    fun cyrillic_letters_the_table_does_not_list_become_the_filler() {
        // Soft sign, Tshe, Gje: not in Section 6.B.
        for (char in listOf('Ь', 'ь', 'Ћ', 'Ѓ')) {
            assertEquals("<", icao(char.toString()), "U+${char.code.toString(16)}")
        }
    }

    @Test
    fun specimen_name_in_cyrillic() {
        assertEquals("ALEKS<MORGAN", icao("Алекс Морган"))
        assertEquals("MORGAN<ALEKS", icao("МОРГАН, АЛЕКС"))
        assertEquals("ZHANNA<SHCHUKINA<KHVOSTOVA", icao("Жанна Щукина-Хвостова"))
    }

    @Test
    fun the_default_applies_no_language_exception() {
        assertEquals("GIEVA<IURII<IANA", icao("Гєва Юрій Яна"))
    }

    @Test
    fun language_exceptions() {
        val cases =
            listOf(
                Triple(IcaoCyrillicLanguageProfiles.belarusian, "Гёрка", "HIORKA"),
                Triple(IcaoCyrillicLanguageProfiles.bulgarian, "Щерев", "SHTEREV"),
                Triple(IcaoCyrillicLanguageProfiles.macedonian, "Ќаџах Цреғ", "KJADJAH<CREGJ"),
                Triple(IcaoCyrillicLanguageProfiles.serbian, "Гажхцчш", "HAZHCCS"),
                Triple(IcaoCyrillicLanguageProfiles.ukrainian, "Ганна Ґиря", "HANNA<GYRIA"),
            )
        for ((profile, text, expected) in cases) {
            assertEquals(expected, run(profile, text), profile.identifier)
        }
    }

    @Test
    fun ukrainian_first_letter_rule_applies_at_the_start_of_each_name_part_only() {
        val ukrainian = IcaoCyrillicLanguageProfiles.ukrainian
        assertEquals("YEVA<YIZHAK<YOSYP<YURA<YANA", run(ukrainian, "Єва Їжак Йосип Юра Яна"))
        assertEquals("ALIEIA<KAIIUIA<ZIIIA", run(ukrainian, "Алєя Каїюя Зійя"))
        assertEquals("YANA<YURA", run(ukrainian, "Яна-Юра"))
        assertEquals("MORHAN<YANA", run(ukrainian, "Морган,Яна"))
        // After an apostrophe the letter is inside the name part.
        assertEquals("MARIANA", run(ukrainian, "Мар'яна"))
    }

    @Test
    fun language_profiles_leave_other_scripts_as_the_default() {
        for (profile in IcaoCyrillicLanguageProfiles.all) {
            // Морсо: no letter any language changes.
            for (text in listOf("ÄÖÜ æ, O'BRIEN-SMITH", "Морсо", "جميلة", "Ω")) {
                assertEquals(icao(text), run(profile, text), "${profile.identifier}: $text")
            }
        }
    }

    @Test
    fun language_profiles_are_registered() {
        assertEquals(
            listOf("ICAO-BELARUSIAN", "ICAO-BULGARIAN", "ICAO-MACEDONIAN", "ICAO-SERBIAN", "ICAO-UKRAINIAN"),
            IcaoCyrillicLanguageProfiles.all.map { it.identifier },
        )
        for (profile in IcaoCyrillicLanguageProfiles.all) {
            assertSame(profile, TransliterationProfileRegistry.lookup(profile.identifier))
        }
    }

    // Section 6.C plus the two Appendix B.5.4 letters, Unicode value to value.
    private val arabicTable: List<Pair<Char, String>> =
        listOf(
            'ء' to "XE",
            'آ' to "XAA",
            'أ' to "XAE",
            'ؤ' to "U",
            'إ' to "I",
            'ئ' to "XI",
            'ا' to "A",
            'ب' to "B",
            'ت' to "T",
            'ث' to "XTH",
            'ج' to "J",
            'ح' to "XH",
            'خ' to "XKH",
            'د' to "D",
            'ذ' to "XDH",
            'ر' to "R",
            'ز' to "Z",
            'س' to "S",
            'ش' to "XSH",
            'ص' to "XSS",
            'ض' to "XDZ",
            'ط' to "XTT",
            'ظ' to "XZZ",
            'ع' to "E",
            'غ' to "G",
            'ف' to "F",
            'ق' to "Q",
            'ك' to "K",
            'ل' to "L",
            'م' to "M",
            'ن' to "N",
            'ه' to "H",
            'و' to "W",
            'ى' to "XAY",
            'ي' to "Y",
            'ٱ' to "XXA",
            'ٹ' to "XXT",
            'ټ' to "XRT",
            'پ' to "P",
            'ځ' to "XKE",
            'څ' to "XXH",
            'چ' to "XC",
            'ڈ' to "XXD",
            'ډ' to "XDR",
            'ڑ' to "XXR",
            'ړ' to "XRR",
            'ږ' to "XRX",
            'ژ' to "XJ",
            'ښ' to "XXS",
            'ڤ' to "V",
            'ڥ' to "XF",
            'ک' to "XKK",
            'ګ' to "XXK",
            'ڭ' to "XNG",
            'گ' to "XGG",
            'ں' to "XNN",
            'ڼ' to "XXN",
            'ھ' to "XDO",
            'ۀ' to "XYH",
            'ہ' to "XXG",
            'ۂ' to "XGE",
            'ۃ' to "XTG",
            'ی' to "XYA",
            'ۍ' to "XXY",
            'ې' to "Y",
            'ے' to "XYB",
            'ۓ' to "XBE",
        )

    @Test
    fun every_arabic_letter_follows_the_table() {
        for ((letter, expected) in arabicTable) {
            assertEquals(expected, icao(letter.toString()), "U+${letter.code.toString(16)}")
        }
    }

    @Test
    fun not_encoded_marks_and_letters_produce_nothing() {
        val notEncoded = "ـًٌٍَُِْٰڜڢڧڨ"
        for (char in notEncoded) {
            assertEquals("B", icao("ب$char"), "U+${char.code.toString(16)}")
        }
    }

    @Test
    fun specimen_name_in_arabic_script() {
        // اليكس مورغان
        assertEquals("ALYKS<MWRGAN", icao("اليكس مورغان"))
    }

    @Test
    fun teh_marbuta_is_XAH_at_the_end_of_a_name_part_and_XTA_inside() {
        val word = "بة" // beh, teh marbuta
        assertEquals("BXAH", icao(word))
        assertEquals("BXAH<BXAH", icao("$word $word"))
        assertEquals("BXAH<B", icao("$word-ب"))
        assertEquals("BXTAB", icao("${word}ب"))
        // A short vowel after it does not make it inner.
        assertEquals("BXAH", icao("$wordُ"))
    }

    @Test
    fun shadda_repeats_the_letter_before_it() {
        assertEquals("BB", icao("بّ"))
        assertEquals("XDZXDZ", icao("ضّ"))
        // Fatha typed after the shadda: NFC puts the fatha first, and the shadda still doubles.
        assertEquals("BBA", icao("بَّا"))
        // Nothing to double.
        assertEquals("", icao("ّ"))
        assertEquals("B<", icao("ب ّ"))
    }

    @Test
    fun shadda_repeats_an_overridden_letter_as_overridden() {
        val profile = IcaoDefaultTransliterationProfile.withOverrides("TEST", mapOf('ب' to "BH"))
        assertEquals("BHBH", run(profile, "بّ"))
    }

    @Test
    fun greek_letters_become_the_filler() {
        // Doc 9303 Part 3 Section 6 has no Greek table.
        assertEquals("<<<<", icao("Αλεξ"))
    }

    @Test
    fun latin_and_every_character_below_cyrillic_convert_as_before() {
        // The rule the ICAO default had before the non-Latin tables: MRZ alphabet as is, then the
        // Latin table, then the filler. Checked for every character before the Cyrillic block.
        val latin = buildIcaoLatinMappings()
        for (code in 0x0000 until 0x0400) {
            val char = code.toChar()
            if (char == ',') continue
            val expected = if (isMrzAlphabetCharacter(char)) char.toString() else latin[char] ?: "<"
            val actual = assertIs<TransliterationResult.Success>(IcaoDefaultTransliterationProfile.toMrzAlphabet(char.toString())).output
            assertEquals(expected, actual, "U+${code.toString(16)}")
        }
    }
}
