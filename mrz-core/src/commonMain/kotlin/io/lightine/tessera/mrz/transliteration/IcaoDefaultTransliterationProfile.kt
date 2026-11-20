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
     * Apostrophes and other punctuation are dropped. A comma between two name parts becomes one
     * `<`, with or without a space next to it (`ANNA, MARIA` and `ANNA,MARIA` both give
     * `ANNA<MARIA`); a comma at the start or the end is dropped. Every other character, including
     * space, hyphen, the schwa `Ə`, and non-Latin letters, becomes `<`.
     *
     * @param normalizedInput The text, already in Unicode NFC.
     * @return [TransliterationResult.Success] with the mapped text.
     */
    public override fun toMrzAlphabet(normalizedInput: String): TransliterationResult {
        val output =
            mapToMrzAlphabet(normalizedInput) { char ->
                if (isMrzAlphabetCharacter(char)) char.toString() else mappings[char] ?: FILLER.toString()
            }
        return TransliterationResult.Success(output)
    }
}
