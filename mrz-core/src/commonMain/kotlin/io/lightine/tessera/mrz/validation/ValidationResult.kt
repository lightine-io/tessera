package io.lightine.tessera.mrz.validation

import io.lightine.tessera.types.errors.MrzParseError
import io.lightine.tessera.types.errors.MrzValidationError
import io.lightine.tessera.types.errors.MrzWarning

/**
 * The output of [`MrzValidator.validate`][MrzValidator.validate]: per-field validation
 * failures and informational warnings emitted while checking a parsed
 * [`MrzDocument`][io.lightine.tessera.mrz.model.MrzDocument].
 *
 * Used directly by callers of the validator. The parser also invokes the validator
 * internally and folds the result into
 * [`ResultMetadata`][io.lightine.tessera.mrz.parsing.ResultMetadata] so consumers of the
 * parse path receive validation results alongside the parsed document.
 *
 * A `ValidationResult` with empty [validationFailures], empty [warnings], and a null
 * [structuralFailure] indicates the input passed every check the validator currently applies.
 */
public data class ValidationResult(
    val validationFailures: List<MrzValidationError>,
    val warnings: List<MrzWarning>,
    /**
     * The hard structural failure that prevented the input from being parsed into a document at all
     * — a wrong line count or length, a character outside the MRZ alphabet, or (for the
     * auto-detecting string overloads) an unrecognized shape.
     *
     * Non-null **only** from the raw-string / pre-split-lines `validate` overloads
     * ([`validate(input: String)`][MrzValidator.validate] and friends) when the input could not
     * clear structural validation; in that case [validationFailures] and [warnings] are empty
     * because the deeper layers could not run (see the validator's layered model). It is **always
     * null** for [`validate(document)`][MrzValidator.validate] — a parsed
     * [`MrzDocument`][io.lightine.tessera.mrz.model.MrzDocument] has, by construction, already
     * cleared structural validation.
     *
     * Structural failures are a deliberately-separate error root
     * ([`MrzParseError`][io.lightine.tessera.types.errors.MrzParseError], the hard "could not parse
     * at all" taxonomy) from the soft per-field [validationFailures]
     * ([`MrzValidationError`][io.lightine.tessera.types.errors.MrzValidationError]); the two are
     * surfaced on distinct fields so "parsing failed entirely" is never conflated with "a document
     * with some bad fields."
     */
    val structuralFailure: MrzParseError? = null,
)
