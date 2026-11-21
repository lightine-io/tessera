package io.lightine.tessera.mrz.transliteration

import io.lightine.tessera.types.vocabulary.MrzField

/**
 * The transliteration a generator applied.
 *
 * The generator sets it on [io.lightine.tessera.mrz.parsing.ResultMetadata.transliterationDetails]
 * when you pass a profile. `toString()` shows no document values; they are in the properties.
 *
 * @property profileIdentifier Identifier of the profile that ran.
 * @property transliteratedFields The values passed through the profile. From field values: one
 *   entry for each non-empty primary or secondary identifier. From a document: one entry for the
 *   whole raw name field, when it is not empty.
 * @since 0.1.0
 */
public data class TransliterationDetails(
    val profileIdentifier: String,
    val transliteratedFields: List<TransliteratedField>,
) {
    /** The profile and how many values it ran over; never a document value. */
    override fun toString(): String =
        "TransliterationDetails(profileIdentifier=$profileIdentifier, " +
            "transliteratedFields=${transliteratedFields.size})"
}

/**
 * One value passed through a transliteration profile.
 *
 * `toString()` shows no document values; they are in the properties.
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
) {
    /** The field the value belongs to; never the value itself. */
    override fun toString(): String = "TransliteratedField(field=$field)"
}
