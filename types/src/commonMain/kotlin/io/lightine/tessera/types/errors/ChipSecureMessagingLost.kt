package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * Protocol error: the secure-messaging session with the chip ended, so no further command can be
 * sent in it (ICAO Doc 9303 Part 11, section 9.8).
 *
 * The files read before the session ended stay available. Reading again needs a new access.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property reason Why the session ended.
 * @property statusWord The chip's status word when it ended the session itself, else `null`.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public data class ChipSecureMessagingLost(
    val reason: Reason,
    override val statusWord: Int? = null,
) : ChipError() {
    /**
     * Why the session ended.
     *
     * May gain members in a later release: a `when` over it needs an `else` branch.
     *
     * @since 1.0.0
     */
    public enum class Reason {
        /** An answer's checksum did not match the one computed over it. */
        WRONG_CHECKSUM,

        /** An answer had no checksum, so it could not be checked. */
        MISSING_CHECKSUM,

        /** An answer's data objects were not in the defined form. */
        MALFORMED_ANSWER,

        /** A decrypted answer did not end in the defined padding. */
        WRONG_PADDING,

        /** The chip answered without protection with a status word that ends a session. */
        CHIP_ENDED_SESSION,

        /** The chip answered a protected command with a plain length hint (`61xx` or `6Cxx`). */
        PLAIN_LENGTH_HINT,

        /** The session had already ended; nothing was sent. */
        SESSION_CLOSED,

        /**
         * An answer's status word came without the checksum that covers it, and it was not the one
         * plain answer a session allows (an empty `9000`): the chip's answer cannot be relied on.
         */
        UNAUTHENTICATED_STATUS,
    }

    /** English text for logs: the reason and the status word, when known. */
    override val description: String
        get() {
            val what =
                when (reason) {
                    Reason.WRONG_CHECKSUM -> "an answer's checksum did not match"
                    Reason.MISSING_CHECKSUM -> "an answer had no checksum"
                    Reason.MALFORMED_ANSWER -> "an answer was not in the defined form"
                    Reason.WRONG_PADDING -> "a decrypted answer had the wrong padding"
                    Reason.CHIP_ENDED_SESSION -> "the chip ended the session"
                    Reason.PLAIN_LENGTH_HINT -> "the chip answered with a plain length hint"
                    Reason.SESSION_CLOSED -> "the session had already ended"
                    Reason.UNAUTHENTICATED_STATUS -> "an answer's status word was not covered by its checksum"
                }
            val sw = statusWord?.let { " (status ${it.toString(16).uppercase().padStart(4, '0')})" } ?: ""
            return "The secure-messaging session with the chip ended: $what$sw"
        }

    /** A stable name of this error: see [ChipError.code]. */
    override val code: String
        get() = "secure-messaging-lost." + slug(reason.name)

    /** `ChipSecureMessagingLost: ` followed by [description]. */
    override fun toString(): String = "ChipSecureMessagingLost: $description"
}
