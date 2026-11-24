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
     * May gain members in a later release: a `when` over it needs an `else` branch.
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

        /**
         * The chip reports the key as blocked: "authentication method blocked" (`6983`), or no tries
         * left (`63C0`). Trying again with this key cannot open the chip.
         *
         * @since 1.0.0
         */
        KEY_BLOCKED,

        /**
         * The chip reports the key as suspended (`63C1`, or `6985` during PACE): one try is left,
         * and the chip asks for its card access number first.
         *
         * @since 1.0.0
         */
        KEY_SUSPENDED,

        /**
         * The chip reports the key as deactivated: "reference data not usable" (`6984`), or the
         * warning `6283` during PACE.
         *
         * @since 1.0.0
         */
        KEY_DEACTIVATED,

        /**
         * The chip reports a try counter for the key before any try was made (`63C2` to `63CF`
         * during PACE: the number of tries left is the last digit of [statusWord]). MRZ values and
         * card access numbers have no try counter, so the reader stopped without using a try.
         *
         * @since 1.0.0
         */
        KEY_TRIES_LIMITED,
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
                    Reason.KEY_BLOCKED -> "the chip reports the access key as blocked"
                    Reason.KEY_SUSPENDED -> "the chip reports the access key as suspended"
                    Reason.KEY_DEACTIVATED -> "the chip reports the access key as deactivated"
                    Reason.KEY_TRIES_LIMITED -> "the chip reports a limited number of tries for the access key, so none was used"
                }
            val sw = statusWord?.let { " (status ${it.toString(16).uppercase().padStart(4, '0')})" } ?: ""
            return "Chip access with protocol $protocol was not granted: $what$sw"
        }

    /** `ChipAccessDenied: ` followed by [description]. */
    override fun toString(): String = "ChipAccessDenied: $description"
}
