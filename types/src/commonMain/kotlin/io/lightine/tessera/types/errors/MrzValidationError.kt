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
    /**
     * English text for logs. It is not localized.
     *
     * The text never contains values from the document or the input: names, numbers, dates, codes,
     * and characters. Those values are in the typed properties of each subtype. `toString()` of
     * every subtype is its simple class name, a colon, and this text.
     */
    public abstract val description: String
}
