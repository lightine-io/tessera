package io.lightine.tessera.types.errors

/**
 * Root of the errors that stop an operation: [MrzParseError] and [MrzGenerationError].
 *
 * Returned in the `Failure` variant of a parse or generation result.
 *
 * @since 0.1.0
 */
public sealed class MrzError {
    /**
     * English text for logs. It is not localized.
     *
     * The text never contains values from the document or the input: names, numbers, dates, codes,
     * and characters. Those values are in the typed properties of each subtype. `toString()` of
     * every subtype is its simple class name, a colon, and this text.
     */
    public abstract val description: String
}
