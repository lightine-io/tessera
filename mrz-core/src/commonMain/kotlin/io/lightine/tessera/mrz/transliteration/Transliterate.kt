package io.lightine.tessera.mrz.transliteration

/**
 * Public end-to-end transliteration entry point. Composes Unicode normalization (per
 * [ADR-014](https://lightine.youtrack.cloud/articles/TES-A-47),
 * internal) with profile application (per
 * [ADR-009](https://lightine.youtrack.cloud/articles/TES-A-42)),
 * surfacing the post-normalization, pre-transliteration form alongside the original
 * input and the profile's output per Principle 5.
 *
 * Use this when you want transliteration outside the generator (for example, to
 * pre-transliterate a name before passing it to a non-primitive generator entry point,
 * or to apply a profile to a value that isn't an MRZ field at all).
 */
public fun TransliterationProfile.transliterate(input: String): TransliterationOutcome {
    val normalized = normalizeForTransliteration(input)
    val result = this.toMrzAlphabet(normalized)
    return TransliterationOutcome(
        profileIdentifier = this.identifier,
        originalInput = input,
        normalizedInput = normalized,
        result = result,
    )
}

/**
 * The outcome of [`TransliterationProfile.transliterate`][transliterate]: which profile
 * ran, what the consumer passed in, what the SDK saw after Unicode normalization, and
 * the profile's [TransliterationResult].
 */
public data class TransliterationOutcome(
    val profileIdentifier: String,
    val originalInput: String,
    val normalizedInput: String,
    val result: TransliterationResult,
)
