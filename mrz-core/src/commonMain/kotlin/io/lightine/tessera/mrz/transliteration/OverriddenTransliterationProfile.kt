package io.lightine.tessera.mrz.transliteration

/** The profile made by [IcaoDefaultTransliterationProfile.withOverrides]. */
internal class OverriddenTransliterationProfile(
    override val identifier: String,
    private val overrides: Map<Char, String>,
) : TransliterationProfile {
    override fun toMrzAlphabet(normalizedInput: String): TransliterationResult {
        val output =
            mapToMrzAlphabet(normalizedInput) { char ->
                overrides[char] ?: IcaoDefaultTransliterationProfile.mapCharacter(char)
            }
        return TransliterationResult.Success(output)
    }
}
