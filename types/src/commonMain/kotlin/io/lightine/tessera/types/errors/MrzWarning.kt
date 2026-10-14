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
    /** English text for logs. It is not localized. */
    public abstract val description: String
}
