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
        val output = mapToMrzAlphabet(normalizedInput, ::mapCharacter)
        return TransliterationResult.Success(output)
    }

    /**
     * Builds a profile that applies [overrides] first and follows this profile for every other
     * character.
     *
     * The override is looked up before anything else, so it also wins over a character that is
     * already in the MRZ alphabet: `'C' to "J"` turns every `C` into `J`. Overrides are applied
     * exactly as given and are case-sensitive: an override for `'ç'` does not cover `'Ç'`, so give
     * both. The comma rule of this profile still applies. An override for `','` is ignored, because
     * a comma is handled before the lookup.
     *
     * The new profile is not added to [TransliterationProfileRegistry]; call
     * [TransliterationProfileRegistry.register] to do that. The map is copied, so changing it
     * afterwards has no effect.
     *
     * @param identifier Identifier of the new profile. It must not be blank.
     * @param overrides Replacement text for single characters. Each value must be made only of MRZ
     *   alphabet characters (`A` to `Z`, `0` to `9`, `<`); an empty value drops the character.
     * @return The new profile.
     * @throws IllegalArgumentException If [identifier] is blank or a value has a character outside
     *   the MRZ alphabet.
     * @since 1.0.0
     */
    public fun withOverrides(
        identifier: String,
        overrides: Map<Char, String>,
    ): TransliterationProfile {
        require(identifier.isNotBlank()) { "identifier must not be blank" }
        for ((key, value) in overrides) {
            val bad = value.firstOrNull { !isMrzAlphabetCharacter(it) }
            require(bad == null) {
                "override for '$key' has '$bad', which is not an MRZ alphabet character (A-Z, 0-9, <)"
            }
        }
        return OverriddenTransliterationProfile(identifier, overrides.toMap())
    }

    internal fun mapCharacter(char: Char): String =
        if (isMrzAlphabetCharacter(char)) char.toString() else mappings[char] ?: FILLER.toString()
}
