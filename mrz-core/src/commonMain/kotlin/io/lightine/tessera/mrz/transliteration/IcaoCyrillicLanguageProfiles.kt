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
 * All five are registered in [TransliterationProfileRegistry] at startup.
 *
 * @since 1.0.0
 */
public object IcaoCyrillicLanguageProfiles {
    /** Belarusian: `Ё` is `IO`, `Г` is `H`. Identifier `ICAO-BELARUSIAN`. */
    public val belarusian: TransliterationProfile =
        IcaoCyrillicLanguageProfile("ICAO-BELARUSIAN", CyrillicLanguage.BELARUSIAN)

    /** Bulgarian: `Щ` is `SHT`. Identifier `ICAO-BULGARIAN`. */
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
     * `Я` is `YA`. Identifier `ICAO-UKRAINIAN`.
     */
    public val ukrainian: TransliterationProfile =
        IcaoCyrillicLanguageProfile("ICAO-UKRAINIAN", CyrillicLanguage.UKRAINIAN)

    /** The five profiles, in alphabetical order of the language. */
    public val all: List<TransliterationProfile> = listOf(belarusian, bulgarian, macedonian, serbian, ukrainian)
}

/** One profile of [IcaoCyrillicLanguageProfiles]. */
internal class IcaoCyrillicLanguageProfile(
    override val identifier: String,
    language: CyrillicLanguage,
) : TransliterationProfile {
    private val rules: IcaoNameRules = IcaoNameRules(cyrillicLanguage = language)

    override fun toMrzAlphabet(normalizedInput: String): TransliterationResult =
        TransliterationResult.Success(rules.convert(normalizedInput))
}
