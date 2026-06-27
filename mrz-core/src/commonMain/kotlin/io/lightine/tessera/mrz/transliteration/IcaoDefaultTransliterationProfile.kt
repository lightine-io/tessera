package io.lightine.tessera.mrz.transliteration

import io.lightine.tessera.mrz.parsing.isMrzAlphabetCharacter

/**
 * The default transliteration profile. Implements the recommendations in ICAO Doc 9303
 * Part 3 Section 6 (Annex G) under the no-expansion convention:
 *
 * - Single-character substitutions: `Ä → A`, `ü → U`, `é → E`, etc. — accented Latin
 *   letters collapse to their unaccented base.
 * - Multi-character substitutions per the standard: `Æ → AE`, `Œ → OE`, `ß → SS`,
 *   `Þ → TH`, `Ð → D`, `Ĳ → IJ`.
 * - Punctuation per Part 3 §4.6: apostrophes and "other punctuation characters" are
 *   omitted from the MRZ entirely (no filler character is inserted in their place).
 *
 * Characters not covered by Annex G fall back to the filler `<` so this profile always
 * returns [`TransliterationResult.Success`][TransliterationResult.Success]. Notable
 * non-coverage: the Latin schwa `Ə` / `ə` (Unicode U+018F / U+0259) is outside Annex
 * G's table (which ends at U+017D plus U+1E9E), so it falls through to filler. Profiles
 * that need to map schwa, such as [AzeTransliterationProfile], add their own override.
 *
 * **Coverage.** The Latin section of Annex G (§6.A) is now populated in full as of the
 * 2026-05-18 pre-`0.1.0` conformance audit — Latin-1 Supplement (U+00C0-00DE), Latin
 * Extended-A (U+0100-017D), and the capital sharp s (U+1E9E), both cases. Non-Latin
 * scripts (Cyrillic §6.B, Arabic §6.C, and the Greek table) are not yet implemented;
 * Adding entries is a non-breaking change. Tracked in the project's issue tracker under
 * "Transliteration profile coverage completeness".
 *
 * Country-specific divergences from this profile are captured by separate profiles such
 * as [AzeTransliterationProfile].
 */
public object IcaoDefaultTransliterationProfile : TransliterationProfile {
    public const val IDENTIFIER: String = "ICAO"

    public override val identifier: String = IDENTIFIER

    private const val FILLER: Char = '<'

    private val mappings: Map<Char, String> = buildIcaoLatinMappings()

    public override fun toMrzAlphabet(normalizedInput: String): TransliterationResult {
        val output = StringBuilder(normalizedInput.length)
        for (char in normalizedInput) {
            when {
                isMrzAlphabetCharacter(char) -> output.append(char)
                else -> output.append(mappings[char] ?: FILLER.toString())
            }
        }
        return TransliterationResult.Success(output.toString())
    }
}
