package io.lightine.tessera.mrz.transliteration

import io.lightine.tessera.mrz.parsing.isMrzAlphabetCharacter

/**
 * The profile for the issuing state with ISO 3166-1 alpha-3 code `AZE`.
 *
 * @since 0.1.0
 */
public object AzeTransliterationProfile : TransliterationProfile {
    /** The profile identifier: `AZE`. */
    public const val IDENTIFIER: String = "AZE"

    /** The profile identifier: [IDENTIFIER]. */
    public override val identifier: String = IDENTIFIER

    private const val FILLER: Char = '<'

    private val mappings: Map<Char, String> =
        buildIcaoLatinMappings().apply {
            // Schwa (Ə U+018F / ə U+0259) — outside Annex G; mapped here via
            // BGN/PCGN fallback + ICAO no-expansion chain (Ə → Ä → A). See ADR-009.
            put('Ə', "A")
            put('ə', "A")

            // AZE-specific phonetic Anglicization overrides. Each pair maps the
            // source letter (in both cases) to the English digraph that matches its
            // phonetic value. The ICAO default would either (a) leave the letter
            // alone (`C`, `J`, `Q`, `X` — already in the MRZ alphabet) or
            // (b) collapse it under no-expansion (`Ç → C`, `Ğ → G`, `Ş → S`); both
            // would lose the source phoneme.
            put('Ç', "CH")
            put('ç', "CH")
            put('Ğ', "GH")
            put('ğ', "GH")
            put('Ş', "SH")
            put('ş', "SH")
            put('X', "KH")
            put('x', "KH")
            put('C', "J")
            put('c', "J")
            put('J', "ZH")
            put('j', "ZH")
            put('Q', "G")
            put('q', "G")
        }

    /**
     * Maps text to the MRZ alphabet and always succeeds.
     *
     * Upper and lower case map alike: `Ə` to `A`, `Ç` to `CH`, `Ğ` to `GH`, `Ş` to `SH`, `X` to
     * `KH`, `C` to `J`, `J` to `ZH`, and `Q` to `G`. These apply to `C`, `J`, `Q`, and `X` even
     * though they are in the MRZ alphabet. Every other character follows
     * [IcaoDefaultTransliterationProfile].
     *
     * @param normalizedInput The text, already in Unicode NFC.
     * @return [TransliterationResult.Success] with the mapped text.
     */
    public override fun toMrzAlphabet(normalizedInput: String): TransliterationResult {
        val output = StringBuilder(normalizedInput.length)
        for (char in normalizedInput) {
            val mapped = mappings[char]
            when {
                mapped != null -> output.append(mapped)
                isMrzAlphabetCharacter(char) -> output.append(char)
                else -> output.append(FILLER)
            }
        }
        return TransliterationResult.Success(output.toString())
    }
}
