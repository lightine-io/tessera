package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * Root of the errors of the connection to a chip: the platform's NFC link, not the chip protocol.
 *
 * A platform transport reports these when it cannot send a command to the chip or get its answer.
 *
 * May gain members in a later release: a `when` over it needs an `else` branch.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public sealed class NfcError : ChipError()
