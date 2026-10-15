package io.lightine.tessera.mrz.parsing

import io.lightine.tessera.mrz.transliteration.TransliterationDetails
import io.lightine.tessera.types.errors.MrzValidationError
import io.lightine.tessera.types.errors.MrzWarning
import io.lightine.tessera.types.vocabulary.ReadMethod

/**
 * The reading method, warnings, and validation failures attached to every parse and generation
 * result.
 *
 * A parse [ParseResult.Failure] carries empty `warnings` and `validationFailures`.
 *
 * @property readMethod How the data reached the SDK: [MrzParser] and the generator set
 *   `BACKEND_STRING_INPUT`, and [ManualMrzReader] sets `MANUAL_ENTRY`.
 * @property warnings Warnings found while validating. Empty if there are none, on a failure, and on
 *   every generation result.
 * @property validationFailures Validation failures found. Non-empty only on a
 *   [ParseResult.PartialSuccess].
 * @property transliterationDetails The transliteration a generator applied. `null` on parse results
 *   and when the generator applied no profile. Default: `null`.
 * @since 0.1.0
 */
public data class ResultMetadata(
    val readMethod: ReadMethod,
    val warnings: List<MrzWarning>,
    val validationFailures: List<MrzValidationError>,
    val transliterationDetails: TransliterationDetails? = null,
)
