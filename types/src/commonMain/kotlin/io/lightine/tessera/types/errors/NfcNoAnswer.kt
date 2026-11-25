package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * Connection error: a chip read ended while the chip had not answered: no try of the read got
 * past the first commands, the ones before the key is used.
 *
 * The document was found, but each try was lost before the chip answered those commands, until
 * the read's time for such tries passed. The chip may have answered some of the first ones
 * (selecting and reading the first files) and then gone silent: that is this error too. The usual
 * cause is a position where the chip gets too little power to answer; another position of the
 * document on the device often helps.
 *
 * A chip read reports it, never a transport. When a try of the read got past those first commands
 * and the link was lost later, the read reports the loss itself ([NfcTagLost] or [NfcTimeout]).
 *
 * Experimental: see [ExperimentalTesseraChipApi]. In Swift: `NfcNoAnswer.shared`.
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public data object NfcNoAnswer : NfcError() {
    /** English text for logs. */
    override val description: String
        get() = "The chip did not answer: no try got past the first commands"

    /** A stable name of this error: see [ChipError.code]. */
    override val code: String
        get() = "no-answer"

    /** `NfcNoAnswer: ` followed by [description]. */
    override fun toString(): String = "NfcNoAnswer: $description"
}
