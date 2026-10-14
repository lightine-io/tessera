package io.lightine.tessera.mrz.transliteration

import io.lightine.tessera.types.vocabulary.MrzField

/**
 * The transliteration a generator applied, one entry per name identifier.
 *
 * The generator sets it on [io.lightine.tessera.mrz.parsing.ResultMetadata.transliterationDetails]
 * when you pass a profile.
 *
 * @property profileIdentifier Identifier of the profile that ran.
 * @property transliteratedFields One entry for each non-empty primary or secondary identifier
 *   passed through the profile.
 * @since 0.1.0
 */
public data class TransliterationDetails(
    val profileIdentifier: String,
    val transliteratedFields: List<TransliteratedField>,
)

/**
 * One value passed through a transliteration profile.
 *
 * @property field The MRZ field the value belongs to.
 * @property originalInput The value as passed in.
 * @property normalizedInput The value after Unicode NFC normalization, as the profile saw it.
 * @property transliteratedOutput The profile's output for the value.
 * @since 0.1.0
 */
public data class TransliteratedField(
    val field: MrzField,
    val originalInput: String,
    val normalizedInput: String,
    val transliteratedOutput: String,
)
