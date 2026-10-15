package io.lightine.tessera.types.errors

/**
 * Root of the errors that stop `MrzParser` from building a document.
 *
 * Returned in `ParseResult.Failure`.
 *
 * @since 0.1.0
 */
public sealed class MrzParseError : MrzError()
