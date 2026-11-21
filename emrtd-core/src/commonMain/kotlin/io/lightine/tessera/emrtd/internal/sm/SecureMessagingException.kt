package io.lightine.tessera.emrtd.internal.sm

import io.lightine.tessera.emrtd.internal.apdu.StatusWord

/** Why a secure-messaging session ended. Each value ends the session: no further command is sent. */
internal enum class SmFailure {
    /** The answer's checksum (DO'8E') does not match the one computed over it. */
    WRONG_MAC,

    /** A protected answer without DO'8E', or an unprotected 9000: the answer is not authenticated. */
    MISSING_MAC,

    /** The answer's data objects are not in the order of Part 11, section 9.8.4, or do not parse. */
    MALFORMED_RESPONSE,

    /** The decrypted answer does not end in ISO/IEC 9797-1 padding method 2. */
    WRONG_PADDING,

    /**
     * The chip answered without secure messaging with a status word that ends a session (a
     * secure-messaging error or "security status not satisfied"; section 9.8.5): it has dropped
     * its session keys.
     */
    CHIP_ENDED_SESSION,

    /**
     * The session had already ended (by one of the failures above, a lost connection, or
     * [SecureChannel.close]); nothing was sent.
     */
    SESSION_CLOSED,
}

/**
 * The secure-messaging session ended. Carries only the reason and, for [SmFailure.CHIP_ENDED_SESSION],
 * the chip's status word: never key, counter or data bytes.
 */
internal class SecureMessagingException(
    val failure: SmFailure,
    val sw: Int? = null,
    cause: Throwable? = null,
) : IllegalStateException(
        "Secure messaging ended: $failure" + (sw?.let { " (status ${StatusWord.hex(it)})" } ?: ""),
        cause,
    )
