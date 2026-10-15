package io.lightine.tessera.mrz.transliteration

import io.lightine.tessera.mrz.parsing.isMrzAlphabetCharacter

/**
 * The default profile: the ICAO Doc 9303 Part 3 Annex G Latin table, no expansion.
 *
 * @since 0.1.0
 */
public object IcaoDefaultTransliterationProfile : TransliterationProfile {
    /** The profile identifier: `ICAO`. */
    public const val IDENTIFIER: String = "ICAO"

    /** The profile identifier: [IDENTIFIER]. */
    public override val identifier: String = IDENTIFIER

    private const val FILLER: Char = '<'

    private val mappings: Map<Char, String> = buildIcaoLatinMappings()

    /**
     * Maps text to the MRZ alphabet and always succeeds.
     *
     * Characters already in the MRZ alphabet pass through, and lowercase Latin letters become
     * uppercase. Accented Latin letters lose their accent (`Ä` becomes `A`). `Æ` becomes `AE`, `Œ`
     * becomes `OE`, `ß` becomes `SS`, `Þ` becomes `TH`, `Ð` becomes `D`, and `Ĳ` becomes `IJ`.
     * Apostrophes and other punctuation are dropped. Every other character, including space,
     * hyphen, the schwa `Ə`, and non-Latin letters, becomes `<`.
     *
     * @param normalizedInput The text, already in Unicode NFC.
     * @return [TransliterationResult.Success] with the mapped text.
     */
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
