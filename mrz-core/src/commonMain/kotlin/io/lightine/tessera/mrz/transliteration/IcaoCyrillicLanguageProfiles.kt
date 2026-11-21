package io.lightine.tessera.mrz.transliteration

/**
 * Profiles for the languages that ICAO Doc 9303 Part 3 Section 6.B names in its Cyrillic table
 * exceptions.
 *
 * The Cyrillic table gives one value per letter and a few exceptions by language, for example `Г`
 * is `G` but `H` in Ukrainian. [IcaoDefaultTransliterationProfile] uses the main values. Each
 * profile here is the ICAO default with one language's exceptions applied; every other character,
 * Latin and Arabic script included, converts as in the default. The SDK never picks one for you:
 * the language of a name is the app's knowledge, so the app passes the profile it wants.
 *
 * Letters the table does not list include the soft sign (`Ь` U+042C, `ь` U+044C), Tshe (`Ћ`
 * U+040B, `ћ` U+045B), and Gje (`Ѓ` U+0403, `ѓ` U+0453). In [IcaoDefaultTransliterationProfile]
 * and in the Belarusian, Macedonian and Serbian profiles here they become `<` unless the app
 * overrides them, for example `'Ь' to ""`. The Ukrainian profile drops the soft sign (it produces
 * nothing, no filler): Ukraine's Cabinet of Ministers resolution No. 55 of 27 January 2010 on
 * transliteration of the Ukrainian alphabet into Latin says the soft sign is not reproduced. The
 * Bulgarian profile maps it to `Y`, from Bulgaria's Transliteration Act (2009); whether that act's
 * values are used for passports is not verified. Tshe and Gje become `<` in every profile. Arabic
 * presentation forms in U+FB50 to U+FEFF are not folded: input is normalized to NFC only, so they
 * become `<`.
 *
 * Call [withOverrides] with one of the five profiles as its `base` to build a copy with
 * app-provided character replacements.
 *
 * All five are registered in [TransliterationProfileRegistry] at startup.
 *
 * @since 1.0.0
 */
public object IcaoCyrillicLanguageProfiles {
    /** Belarusian: `Ё` is `IO`, `Г` is `H`. Identifier `ICAO-BELARUSIAN`. */
    public val belarusian: TransliterationProfile =
        IcaoCyrillicLanguageProfile("ICAO-BELARUSIAN", CyrillicLanguage.BELARUSIAN)

    /**
     * Bulgarian: `Щ` is `SHT`, and the soft sign `Ь` (`ь`) is `Y`, from Bulgaria's Transliteration
     * Act (2009); whether that act's values are used for passports is not verified. Identifier
     * `ICAO-BULGARIAN`.
     */
    public val bulgarian: TransliterationProfile =
        IcaoCyrillicLanguageProfile("ICAO-BULGARIAN", CyrillicLanguage.BULGARIAN)

    /**
     * Macedonian: `Ќ` is `KJ`, `Џ` is `DJ`, `Х` is `H`, `Ц` is `C`, and `Ғ` (U+0492, the letter the
     * table prints) is `GJ`. Identifier `ICAO-MACEDONIAN`.
     */
    public val macedonian: TransliterationProfile =
        IcaoCyrillicLanguageProfile("ICAO-MACEDONIAN", CyrillicLanguage.MACEDONIAN)

    /**
     * Serbian: `Г` is `H` (as the table prints it), `Ж` is `Z`, `Х` is `H`, `Ц` is `C`, `Ч` is
     * `C`, and `Ш` is `S`. Identifier `ICAO-SERBIAN`.
     */
    public val serbian: TransliterationProfile =
        IcaoCyrillicLanguageProfile("ICAO-SERBIAN", CyrillicLanguage.SERBIAN)

    /**
     * Ukrainian: `Г` is `H` and `И` is `Y`. As the first letter of a name part (at the start, or
     * after a space, a hyphen or a comma) `Є` is `YE`, `Ї` is `YI`, `Й` is `Y`, `Ю` is `YU`, and
     * `Я` is `YA`. The soft sign `Ь` (`ь`) produces nothing: Ukraine's Cabinet of Ministers
     * resolution No. 55 of 27 January 2010 on transliteration of the Ukrainian alphabet into
     * Latin says the soft sign is not reproduced. Identifier `ICAO-UKRAINIAN`.
     */
    public val ukrainian: TransliterationProfile =
        IcaoCyrillicLanguageProfile("ICAO-UKRAINIAN", CyrillicLanguage.UKRAINIAN)

    /** The five profiles, in alphabetical order of the language. */
    public val all: List<TransliterationProfile> = listOf(belarusian, bulgarian, macedonian, serbian, ukrainian)

    /**
     * Builds a profile based on one of this object's five language profiles.
     *
     * Overrides are looked up first, are case-sensitive, and may contain only MRZ alphabet
     * characters (`A` to `Z`, `0` to `9`, `<`); an empty value drops the character. The identifier
     * must not be blank. The map is copied, and the new profile is not registered automatically.
     * Language rules still apply to characters that are not overridden, but an override wins over
     * the base profile's own rules, so an override for `Ь` also wins over the Ukrainian profile's
     * drop of the soft sign. The comma rule still applies, so an override for `','` has no effect.
     *
     * @param base The profile to base the new one on. It must be one of [all].
     * @param identifier Identifier of the new profile. It must not be blank.
     * @param overrides Replacement text for single characters.
     * @return The new profile.
     * @throws IllegalArgumentException If [base] is not one of [all], [identifier] is blank, or an
     *   override value has a character outside the MRZ alphabet.
     * @since 1.0.0
     */
    public fun withOverrides(
        base: TransliterationProfile,
        identifier: String,
        overrides: Map<Char, String>,
    ): TransliterationProfile {
        require(base in all) {
            "base must be one of the profiles in IcaoCyrillicLanguageProfiles.all, but was '${base.identifier}'"
        }
        return (base as IcaoCyrillicLanguageProfile).withOverrides(identifier, overrides)
    }
}

/** One profile of [IcaoCyrillicLanguageProfiles]. */
internal class IcaoCyrillicLanguageProfile(
    override val identifier: String,
    private val language: CyrillicLanguage,
) : TransliterationProfile {
    private val rules: IcaoNameRules = IcaoNameRules(cyrillicLanguage = language)

    internal fun withOverrides(
        identifier: String,
        overrides: Map<Char, String>,
    ): TransliterationProfile = createOverriddenTransliterationProfile(identifier, overrides, language)

    override fun toMrzAlphabet(normalizedInput: String): TransliterationResult =
        TransliterationResult.Success(rules.convert(normalizedInput))
}
