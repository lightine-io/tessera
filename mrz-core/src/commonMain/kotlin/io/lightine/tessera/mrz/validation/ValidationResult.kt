package io.lightine.tessera.mrz.validation

import io.lightine.tessera.types.errors.MrzParseError
import io.lightine.tessera.types.errors.MrzValidationError
import io.lightine.tessera.types.errors.MrzWarning

/**
 * The result of validating an MRZ: validation failures, warnings, and a structural failure.
 *
 * @since 0.1.0
 */
public data class ValidationResult(
    /** Validation failures found. Empty if there are none, and when [structuralFailure] is set. */
    val validationFailures: List<MrzValidationError>,
    /** Warnings found. Empty if there are none, and when [structuralFailure] is set. */
    val warnings: List<MrzWarning>,
    /**
     * The structural failure that stopped the input from being read: a wrong line count or
     * length, a character outside the MRZ alphabet, or an undetected format.
     *
     * The `validate` overloads that take a string or lines set it when the parse fails.
     * `validate(document)` sets it when the document's `rawLines` do not fit its format or hold a
     * character outside the MRZ alphabet, and never reports an undetected format. Default: `null`.
     */
    val structuralFailure: MrzParseError? = null,
)
