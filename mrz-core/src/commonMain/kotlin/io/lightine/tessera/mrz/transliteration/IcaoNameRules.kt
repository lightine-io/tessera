package io.lightine.tessera.mrz.transliteration

import io.lightine.tessera.mrz.parsing.isMrzAlphabetCharacter

/**
 * The rules of ICAO Doc 9303 Part 3 Section 6 that the ICAO profiles share: the Latin table (6.A),
 * the Cyrillic table (6.B) and the Arabic-script table (6.C), plus the comma rule of section 4.6.
 *
 * Lookup order for each character: [overrides], then the MRZ alphabet as is, then the Latin, the
 * Cyrillic and the Arabic tables, then the filler. Language-specific Cyrillic rules apply when the
 * character has no override. For Latin text this is exactly the order the ICAO default profile
 * always had.
 *
 * @property cyrillicLanguage The language whose Section 6.B exceptions apply, or `null` for the
 *   table's main values.
 * @property overrides Replacements that win over everything else.
 */
internal class IcaoNameRules(
    private val cyrillicLanguage: CyrillicLanguage? = null,
    private val overrides: Map<Char, String> = emptyMap(),
) {
    fun convert(text: String): String = mapToMrzAlphabetAt(text) { index -> mapAt(text, index) }

    private fun mapAt(
        text: String,
        index: Int,
    ): String {
        val char = text[index]
        overrides[char]?.let { return it }
        if (isMrzAlphabetCharacter(char)) return char.toString()
        LATIN_MAPPINGS[char]?.let { return it }
        mapCyrillic(char, cyrillicLanguage, startsNamePart(text, index))?.let { return it }
        mapArabic(text, index) { mapAt(text, it) }?.let { return it }
        return FILLER
    }

    private companion object {
        const val FILLER: String = "<"
        val LATIN_MAPPINGS: Map<Char, String> = buildIcaoLatinMappings()

        // A name part starts at the start of the text or after a space, a hyphen or a comma: the
        // characters the profiles turn into a filler between two parts. Section 6.B says "first
        // character" without saying of what; the name part is our reading.
        fun startsNamePart(
            text: String,
            index: Int,
        ): Boolean {
            if (index == 0) return true
            val previous = text[index - 1]
            return previous.isWhitespace() || previous == '-' || previous == ',' || previous == '<'
        }
    }
}
