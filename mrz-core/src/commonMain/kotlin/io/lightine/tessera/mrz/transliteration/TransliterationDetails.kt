package io.lightine.tessera.mrz.transliteration

import io.lightine.tessera.types.vocabulary.MrzField

/**
 * Per-field audit trail of transliteration applied during MRZ generation. Exposed on
 * [`ResultMetadata.transliterationDetails`][io.lightine.tessera.mrz.parsing.ResultMetadata]
 * per Principle 5 (transparency) and
 * [ADR-014](https://lightine.youtrack.cloud/articles/TES-A-47):
 * consumers can inspect the post-normalization, pre-transliteration form for each field
 * the SDK rewrote, alongside the original input and the encoded output.
 *
 * Populated only by the primitive-input generator methods (e.g.,
 * [`MrzGenerator.generateTD3`][io.lightine.tessera.mrz.generation.MrzGenerator]) when a
 * profile was supplied. `null` on parse paths and on generation paths that took no
 * profile.
 */
public data class TransliterationDetails(
    val profileIdentifier: String,
    val transliteratedFields: List<TransliteratedField>,
)

/**
 * A single entry in [TransliterationDetails.transliteratedFields]: which [field] was
 * rewritten, the consumer's [originalInput], the Unicode-NFC-normalized form
 * ([normalizedInput]) the profile saw, and the resulting [transliteratedOutput] that was
 * encoded into the MRZ.
 */
public data class TransliteratedField(
    val field: MrzField,
    val originalInput: String,
    val normalizedInput: String,
    val transliteratedOutput: String,
)
