package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol

/**
 * Protocol error: the chip did not grant access, or did not let the reader read a file.
 *
 * The most common cause is an access key that does not match the one the chip was set up with:
 * MRZ values typed or read with a mistake, or the wrong card access number. A chip that cannot be
 * read is a fact about the read, not about the document.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property protocol The access protocol that was running, or [ChipAccessProtocol.NONE] when none was.
 * @property reason Why access was not granted.
 * @property statusWord The status word the chip answered with, when there is one (for example
 *   `0x6300` or `0x6982`).
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public data class ChipAccessDenied(
    val protocol: ChipAccessProtocol,
    val reason: Reason,
    val statusWord: Int? = null,
) : ChipError() {
    /**
     * Why access was not granted.
     *
     * @since 1.0.0
     */
    public enum class Reason {
        /** The chip refused the reader's proof of the key: usually the key does not match the chip's. */
        KEY_NOT_MATCHED,

        /** The chip refused a step of the access protocol with an error status word. */
        CHIP_REFUSED,

        /** The chip's answer during the access protocol was not what the protocol defines, so the reader stopped. */
        UNEXPECTED_CHIP_ANSWER,

        /** A card access number opens a chip only with PACE, and this chip offers no PACE the reader can run. */
        KEY_NEEDS_PACE,

        /** The access key was cleared before the read, so it can no longer open a chip. */
        KEY_CLEARED,

        /** The chip answered "security status not satisfied" (`6982`) for a file. */
        FILE_PROTECTED,
    }

    /** English text for logs: the protocol, the reason and the status word, when known. */
    override val description: String
        get() {
            val what =
                when (reason) {
                    Reason.KEY_NOT_MATCHED -> "the chip did not match the access key"
                    Reason.CHIP_REFUSED -> "the chip refused a step of the access protocol"
                    Reason.UNEXPECTED_CHIP_ANSWER -> "the chip's answer did not follow the access protocol"
                    Reason.KEY_NEEDS_PACE -> "a card access number needs PACE, which the chip does not offer in a usable form"
                    Reason.KEY_CLEARED -> "the access key was cleared"
                    Reason.FILE_PROTECTED -> "the chip does not allow reading this file with the current access"
                }
            val sw = statusWord?.let { " (status ${it.toString(16).uppercase().padStart(4, '0')})" } ?: ""
            return "Chip access with protocol $protocol was not granted: $what$sw"
        }

    /** `ChipAccessDenied: ` followed by [description]. */
    override fun toString(): String = "ChipAccessDenied: $description"
}
