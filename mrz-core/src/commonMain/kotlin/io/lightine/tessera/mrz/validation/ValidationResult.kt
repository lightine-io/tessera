package io.lightine.tessera.mrz.validation

import io.lightine.tessera.types.errors.MrzParseError
import io.lightine.tessera.types.errors.MrzValidationError
import io.lightine.tessera.types.errors.MrzWarning
import io.lightine.tessera.types.vocabulary.CheckOutcome

/**
 * The result of validating an MRZ: validation failures, warnings, a structural failure, and one
 * record per check.
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
    /**
     * One record per check, in [MrzCheck] order: which checks ran and how each went, so a consumer
     * can tell "the document number check digit agreed" from "that check was not run". Every
     * format reports its full set of checks, including the ones it does not have as
     * [CheckOutcome.NotApplicable]; when [structuralFailure] is set, every check is
     * [CheckOutcome.NotRun] with the failure named in the reason ([MrzNotRunReason]). Empty only
     * when this result was built without checks. Default: an empty list.
     *
     * @since 1.0.0
     */
    val checks: List<MrzCheckRecord> = emptyList(),
) {
    /**
     * The constructor without [checks]: the checks come out empty.
     *
     * @param validationFailures Validation failures found.
     * @param warnings Warnings found.
     * @param structuralFailure The structural failure that stopped the input from being read, if any.
     * @since 1.0.0
     */
    public constructor(
        validationFailures: List<MrzValidationError>,
        warnings: List<MrzWarning>,
        structuralFailure: MrzParseError?,
    ) : this(validationFailures, warnings, structuralFailure, emptyList())
}
