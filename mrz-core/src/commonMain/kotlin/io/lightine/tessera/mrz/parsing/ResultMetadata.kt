package io.lightine.tessera.mrz.parsing

import io.lightine.tessera.mrz.transliteration.TransliterationDetails
import io.lightine.tessera.mrz.validation.MrzCheckRecord
import io.lightine.tessera.types.errors.MrzValidationError
import io.lightine.tessera.types.errors.MrzWarning
import io.lightine.tessera.types.vocabulary.ReadMethod

/**
 * The reading method, warnings, validation failures, and check records attached to every parse
 * and generation result.
 *
 * A parse [ParseResult.Failure] carries empty `warnings`, `validationFailures`, and `checks`.
 *
 * @property readMethod How the data reached the SDK: [MrzParser] and the generator set
 *   `BACKEND_STRING_INPUT`, and [ManualMrzReader] sets `MANUAL_ENTRY`.
 * @property warnings Warnings found while validating. Empty if there are none, on a failure, and on
 *   every generation result.
 * @property validationFailures Validation failures found. Non-empty only on a
 *   [ParseResult.PartialSuccess].
 * @property transliterationDetails The transliteration a generator applied. `null` on parse results
 *   and when the generator applied no profile. Default: `null`.
 * @property checks One record per check the validator ran on the document, in
 *   [io.lightine.tessera.mrz.validation.MrzCheck] order, so a consumer can tell which checks ran
 *   and passed from the ones that failed. Empty on a failure and on every generation result.
 *   Default: an empty list.
 * @since 0.1.0
 */
public data class ResultMetadata(
    val readMethod: ReadMethod,
    val warnings: List<MrzWarning>,
    val validationFailures: List<MrzValidationError>,
    val transliterationDetails: TransliterationDetails? = null,
    val checks: List<MrzCheckRecord> = emptyList(),
) {
    /**
     * The constructor without [checks]: the checks come out empty.
     *
     * @param readMethod How the data reached the SDK.
     * @param warnings Warnings found while validating.
     * @param validationFailures Validation failures found.
     * @param transliterationDetails The transliteration a generator applied, if any.
     * @since 1.0.0
     */
    public constructor(
        readMethod: ReadMethod,
        warnings: List<MrzWarning>,
        validationFailures: List<MrzValidationError>,
        transliterationDetails: TransliterationDetails?,
    ) : this(readMethod, warnings, validationFailures, transliterationDetails, emptyList())
}
