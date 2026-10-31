package io.lightine.tessera.types.errors

/**
 * Root of the warnings: the data is readable, but something is unusual.
 *
 * Warnings are listed in `ResultMetadata.warnings` and `ValidationResult.warnings`.
 * They never change the parse result variant.
 *
 * @since 0.1.0
 */
public sealed class MrzWarning {
    /**
     * English text for logs. It is not localized.
     *
     * The text never contains values from the document or the input: names, numbers, dates, codes,
     * and characters. Those values are in the typed properties of each subtype. `toString()` of
     * every subtype is its simple class name, a colon, and this text.
     */
    public abstract val description: String
}
