package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * Root of the errors of the connection to a chip: the platform's NFC link, not the chip protocol.
 *
 * A platform transport reports these when it cannot send a command to the chip or get its answer.
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public sealed class NfcError : ChipError()
