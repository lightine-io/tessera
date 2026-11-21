package io.lightine.tessera.mrz.transliteration

import io.lightine.tessera.mrz.parsing.isMrzAlphabetCharacter

/** An ICAO profile made with character overrides. */
internal class OverriddenTransliterationProfile(
    override val identifier: String,
    overrides: Map<Char, String>,
    cyrillicLanguage: CyrillicLanguage? = null,
) : TransliterationProfile {
    private val rules: IcaoNameRules =
        IcaoNameRules(cyrillicLanguage = cyrillicLanguage, overrides = overrides)

    override fun toMrzAlphabet(normalizedInput: String): TransliterationResult =
        TransliterationResult.Success(rules.convert(normalizedInput))
}

internal fun createOverriddenTransliterationProfile(
    identifier: String,
    overrides: Map<Char, String>,
    cyrillicLanguage: CyrillicLanguage? = null,
): TransliterationProfile {
    require(identifier.isNotBlank()) { "identifier must not be blank" }
    for ((key, value) in overrides) {
        val bad = value.firstOrNull { !isMrzAlphabetCharacter(it) }
        require(bad == null) {
            "override for '$key' has '$bad', which is not an MRZ alphabet character (A-Z, 0-9, <)"
        }
    }
    return OverriddenTransliterationProfile(identifier, overrides.toMap(), cyrillicLanguage)
}
