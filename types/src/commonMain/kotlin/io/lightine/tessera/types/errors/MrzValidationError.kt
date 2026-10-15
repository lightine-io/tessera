package io.lightine.tessera.types.errors

/**
 * Root of the validation failures: the text was read, but a check did not pass.
 *
 * The document stays in the result. Failures are listed in `ResultMetadata.validationFailures`
 * and `ValidationResult.validationFailures`. A parse with at least one failure is a `PartialSuccess`.
 *
 * @since 0.1.0
 */
public sealed class MrzValidationError {
    /** English text for logs. It is not localized. */
    public abstract val description: String
}
