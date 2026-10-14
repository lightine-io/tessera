package io.lightine.tessera.types.errors

/**
 * Root of the errors that stop an operation: [MrzParseError] and [MrzGenerationError].
 *
 * Returned in the `Failure` variant of a parse or generation result.
 *
 * @since 0.1.0
 */
public sealed class MrzError {
    /** English text for logs. It is not localized. */
    public abstract val description: String
}
