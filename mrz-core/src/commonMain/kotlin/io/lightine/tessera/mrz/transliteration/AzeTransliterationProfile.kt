package io.lightine.tessera.mrz.transliteration

import io.lightine.tessera.mrz.parsing.isMrzAlphabetCharacter

/**
 * Transliteration profile for the issuing state with ISO 3166-1 alpha-3 code `"AZE"`.
 *
 * Diverges systematically from the ICAO Doc 9303 Annex G defaults via **phonetic
 * Anglicization** — letters whose phonetic value in the issuing state's Latin alphabet
 * differs from common Latin practice are mapped to the English-equivalent character
 * sequence in the MRZ. The eight overrides (each applied identically to upper and lower
 * case):
 *
 * | Source | MRZ | Source's phonetic value (rough) |
 * |---|---|---|
 * | `Ə` / `ə` | `A` | mid-central vowel (schwa); ALA-LC `ă` strips to `A` |
 * | `Ç` / `ç` | `CH` | English "ch" (/tʃ/) |
 * | `Ğ` / `ğ` | `GH` | voiced velar/uvular fricative (/ɣ/), closest to "gh" |
 * | `Ş` / `ş` | `SH` | English "sh" (/ʃ/) |
 * | `X` / `x` | `KH` | velar fricative (/x/), like German "ach-laut" |
 * | `C` / `c` | `J` | English "j" (/dʒ/) |
 * | `J` / `j` | `ZH` | English "zh" (/ʒ/) |
 * | `Q` / `q` | `G` | voiced velar stop (/g/), like English "g" |
 *
 * **Primary citation.** The overrides match the ALA-LC romanization table for the AZE
 * Latin alphabet with diacritics stripped to ASCII — ALA-LC produces `ch`, `gh`, `kh`,
 * `sh`, `ġ` (for Q), `ă` (for Ə), `ı̐` (for I), `i` (for İ), `ȯ` (for Ö), `u̇` (for Ü).
 * After the stripping step the MRZ alphabet requires, the result matches observed practice
 * in AZE-issued passports and ID cards. The two-step chain (ALA-LC romanize → strip
 * diacritics) generalizes the schwa pattern ADR-009 originally cited (BGN/PCGN `Ə → Ä`
 * fallback, ICAO no-expansion `Ä → A`); this profile applies the same chain across the
 * full AZE alphabet, not just the schwa case.
 *
 * **Empirical confirmation.** Sample documents (passport + national ID cards) verified
 * the rules during the pre-`0.1.0` audit (2026-05-19) for `Ç`, `Ğ`, `İ`, `I`, `Ə`.
 * `X → KH`, `Ş → SH`, `Q → G`, `J → ZH`, `C → J` rest on a fluent speaker's testimony
 * plus the ALA-LC chain; the deferred sub-question on the empirical basis of `J → ZH`
 * and `C → J` is tracked in the project's issue tracker.
 *
 * **Inherits from ICAO Annex G without override** (where the default is correct for AZE):
 * `İ → I`, `I → I`, `Ö → O`, `Ü → U`, plus every letter not listed in the table above.
 *
 * **Implementation detail.** Several overrides (`C`, `J`, `Q`, `X` and their lowercase
 * forms) target characters that are already in the MRZ alphabet. This profile's
 * `toMrzAlphabet` consults the override map **before** the MRZ-alphabet passthrough check
 * so these overrides take effect — different from [IcaoDefaultTransliterationProfile],
 * whose conservative defaults never override an MRZ-alphabet letter and which therefore
 * keeps the original lookup order.
 *
 * Unmapped non-MRZ characters fall back to the filler `<`, so this profile always returns
 * [TransliterationResult.Success].
 *
 * Per the project's vendor-neutral framing, the country is identified by its ISO code in
 * code and never named in prose.
 */
public object AzeTransliterationProfile : TransliterationProfile {
    public const val IDENTIFIER: String = "AZE"

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
