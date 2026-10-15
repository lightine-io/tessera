package io.lightine.tessera.mrz.parsing

import io.lightine.tessera.mrz.model.MrzDocument
import io.lightine.tessera.types.errors.MrzParseError

/**
 * The result of parsing an MRZ: [Success], [PartialSuccess], or [Failure].
 *
 * Success and PartialSuccess hold the personal data of the document, and Failure holds the raw
 * input, so do not log a whole result verbatim.
 *
 * @since 0.1.0
 */
public sealed class ParseResult {
    /** The reading method, warnings, and validation failures of this result. */
    public abstract val metadata: ResultMetadata

    /**
     * The input parsed and the validator found no validation failure.
     *
     * @property document The parsed document.
     * @property metadata The reading method and any warnings.
     * @since 0.1.0
     */
    public data class Success(
        val document: MrzDocument,
        override val metadata: ResultMetadata,
    ) : ParseResult()

    /**
     * The input parsed, and the validator found at least one validation failure.
     *
     * @property document The parsed document.
     * @property metadata The reading method, warnings, and the validation failures.
     * @since 0.1.0
     */
    public data class PartialSuccess(
        val document: MrzDocument,
        override val metadata: ResultMetadata,
    ) : ParseResult()

    /**
     * The input could not be parsed.
     *
     * @property error Why parsing failed.
     * @property rawInput The input lines joined with `\n`.
     * @property metadata The reading method, with empty warnings and validation failures.
     * @since 0.1.0
     */
    public data class Failure(
        val error: MrzParseError,
        val rawInput: String?,
        override val metadata: ResultMetadata,
    ) : ParseResult()
}
