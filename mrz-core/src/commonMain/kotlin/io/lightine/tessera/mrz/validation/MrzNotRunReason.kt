package io.lightine.tessera.mrz.validation

import io.lightine.tessera.types.vocabulary.CheckReason

/**
 * Why an MRZ check came out [io.lightine.tessera.types.vocabulary.CheckOutcome.NotRun]: the input
 * the check needs was missing or could not be read. Each value is a fact about the input, never
 * a verdict on the document.
 *
 * @since 1.0.0
 */
public enum class MrzNotRunReason : CheckReason {
    /** The line count or a line length does not fit the document's format. */
    INVALID_LENGTH,

    /** A character is outside the MRZ alphabet (`A`-`Z`, `0`-`9`, `<`). */
    CHARACTER_SET_VIOLATION,

    /** The format of the input could not be detected. */
    FORMAT_NOT_DETECTED,

    /** The document carries no composite check digit to check. */
    NO_COMPOSITE_CHECK_DIGIT,

    ;

    /** The stable code of this reason: `mrz` and the name in lower case, words joined by hyphens. */
    override val code: String
        get() = "mrz." + name.lowercase().replace('_', '-')
}
