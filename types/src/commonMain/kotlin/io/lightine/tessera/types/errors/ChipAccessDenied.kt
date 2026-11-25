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
 *   `0x6300` or `0x6982`): the fact the reason is based on.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public data class ChipAccessDenied(
    val protocol: ChipAccessProtocol,
    val reason: Reason,
    override val statusWord: Int? = null,
) : ChipError() {
    /**
     * Why access was not granted.
     *
     * May gain members in a later release: an exhaustive `when` over it then stops compiling until
     * the new member is handled. An `else` branch keeps it compiling without handling the new member:
     * that is your choice, and today the compiler warns that such an `else` is redundant.
     *
     * @since 1.0.0
     */
    public enum class Reason {
        /**
         * The chip refused the reader's proof of the key at the authentication step (BAC's
         * EXTERNAL AUTHENTICATE, PACE's mutual authentication) with `6300`, `63CX` or, for BAC,
         * `6982`; [statusWord] holds it. Usually the key does not match the chip's; on a device a
         * chip also refused, with `63C0`, a key that it accepted on the next read. The status word
         * is the chip's answer, not a count of tries: the MRZ key and the card access number have
         * no try counter.
         */
        KEY_NOT_MATCHED,

        /**
         * The chip answered a step of the access protocol with a status word other than success, and
         * the reader stopped; [statusWord] holds it. Also `6983`, `6984` or `6985` at BAC's EXTERNAL
         * AUTHENTICATE or at any PACE step, and a warning (`62xx`, `63xx`) to PACE's MSE:Set AT:
         * words that report a password's state only for passwords with a try counter, which the SDK
         * does not use.
         */
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
         * For a password with a try counter: the chip reports it as blocked ("authentication method
         * blocked" `6983`, or no tries left `63C0`; TR-03110-3, B.14).
         *
         * Not reported by this version: the SDK opens chips only with the MRZ key and the card
         * access number, which have no try counter, so for them these status words come as
         * [KEY_NOT_MATCHED] or [CHIP_REFUSED] with the status word. Kept for a later password type
         * with a counter.
         *
         * @since 1.0.0
         */
        KEY_BLOCKED,

        /**
         * For a password with a try counter: the chip reports it as suspended (`63C1`, or `6985`
         * during PACE): one try is left, and the chip asks for its card access number first.
         *
         * Not reported by this version, for the reason given at [KEY_BLOCKED].
         *
         * @since 1.0.0
         */
        KEY_SUSPENDED,

        /**
         * For a password with a try counter: the chip reports it as deactivated ("reference data
         * not usable" `6984`, or the warning `6283` during PACE).
         *
         * Not reported by this version, for the reason given at [KEY_BLOCKED].
         *
         * @since 1.0.0
         */
        KEY_DEACTIVATED,

        /**
         * For a password with a try counter: the chip reports the tries left before any try was made
         * (`63C2` to `63CF` to PACE's MSE:Set AT; the number of tries left is the last digit of
         * [statusWord]).
         *
         * Not reported by this version, for the reason given at [KEY_BLOCKED].
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
                    Reason.KEY_NOT_MATCHED -> "the chip refused the reader's proof of the access key"
                    Reason.CHIP_REFUSED -> "the chip answered a step of the access protocol with a status other than success"
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

    /** A stable name of this error: see [ChipError.code]. */
    override val code: String
        get() = "access-denied." + slug(reason.name)

    /** `ChipAccessDenied: ` followed by [description]. */
    override fun toString(): String = "ChipAccessDenied: $description"
}
