package io.lightine.tessera.mrz.transliteration

import io.lightine.tessera.mrz.parsing.isMrzAlphabetCharacter

/**
 * The default profile: the tables of ICAO Doc 9303 Part 3 Section 6 for Latin (6.A, no expansion),
 * Cyrillic (6.B, main values) and Arabic script (6.C).
 *
 * @since 0.1.0
 */
public object IcaoDefaultTransliterationProfile : TransliterationProfile {
    /** The profile identifier: `ICAO`. */
    public const val IDENTIFIER: String = "ICAO"

    /** The profile identifier: [IDENTIFIER]. */
    public override val identifier: String = IDENTIFIER

    private val rules: IcaoNameRules = IcaoNameRules()

    /**
     * Maps text to the MRZ alphabet and always succeeds.
     *
     * Characters already in the MRZ alphabet pass through, and lowercase Latin letters become
     * uppercase. Accented Latin letters lose their accent (`Ä` becomes `A`). `Æ` becomes `AE`, `Œ`
     * becomes `OE`, `ß` becomes `SS`, `Þ` becomes `TH`, `Ð` becomes `D`, and `Ĳ` becomes `IJ`.
     * Apostrophes and other punctuation are dropped. A comma between two name parts becomes one
     * `<`, with or without a space next to it (`ANNA, MARIA` and `ANNA,MARIA` both give
     * `ANNA<MARIA`); a comma at the start or the end is dropped.
     *
     * Cyrillic letters follow the main values of Section 6.B, capital and small alike (`Ж` becomes
     * `ZH`, `Щ` becomes `SHCH`). The table's language exceptions are not applied here; they are in
     * the profiles of [IcaoCyrillicLanguageProfiles]. Letters the table does not list, such as the
     * soft sign `Ь`, become `<`.
     *
     * Arabic-script letters follow Section 6.C, plus veh and feh with 3 dots below from Appendix B.
     * Short vowels, sukun, superscript alef, tatweel and the four letters the table marks "not
     * encoded" produce nothing. Teh marbuta is `XTA`, or `XAH` at the end of a name part. Shadda
     * repeats the letter before it (`XDZ` becomes `XDZXDZ`).
     *
     * Every other character, including space, hyphen, the schwa `Ə`, and Greek letters, becomes
     * `<`.
     *
     * @param normalizedInput The text, already in Unicode NFC.
     * @return [TransliterationResult.Success] with the mapped text.
     */
    public override fun toMrzAlphabet(normalizedInput: String): TransliterationResult =
        TransliterationResult.Success(rules.convert(normalizedInput))

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
     * @since 0.8.0
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
}
