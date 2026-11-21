package io.lightine.tessera.mrz.transliteration

/** The profile made by [IcaoDefaultTransliterationProfile.withOverrides]. */
internal class OverriddenTransliterationProfile(
    override val identifier: String,
    overrides: Map<Char, String>,
) : TransliterationProfile {
    private val rules: IcaoNameRules = IcaoNameRules(overrides = overrides)

    override fun toMrzAlphabet(normalizedInput: String): TransliterationResult =
        TransliterationResult.Success(rules.convert(normalizedInput))
}
