package io.lightine.tessera.types.errors

/**
 * Root of the errors that stop `MrzGenerator` from building an MRZ.
 *
 * Returned in `GenerationResult.Failure`.
 *
 * @since 0.1.0
 */
public sealed class MrzGenerationError : MrzError()
