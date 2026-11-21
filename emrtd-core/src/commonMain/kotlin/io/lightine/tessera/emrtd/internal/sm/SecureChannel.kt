package io.lightine.tessera.emrtd.internal.sm

import io.lightine.tessera.emrtd.internal.apdu.ApduSender
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto

/**
 * A secure-messaging session as an [ApduSender]: a decorator over the raw sender [inner] that
 * protects every command and checks every answer ([SecureMessaging]).
 *
 * Once a session is open, every command goes through it; the reader never sends a plain command
 * beside it (Part 11, section 4.3.2: "all subsequent communication MUST be protected"; a chip ends
 * its session on a plain command, section 9.8.3). Unlike the plain
 * [io.lightine.tessera.emrtd.internal.apdu.ApduChannel], it never follows the plain 61xx/6Cxx
 * answer-length hints: inside a session those status words mean the chip answered outside its
 * protection, so [SecureMessaging] turns them into a [SecureMessagingException].
 *
 * The first failure ends the session for good, because the send sequence counters of reader and
 * chip can no longer be trusted to agree:
 * - an answer that fails authentication or the chip's bare session-ending status word:
 *   [SecureMessagingException] with the [SmFailure];
 * - a transport failure (for example the tag was lost): the
 *   [io.lightine.tessera.emrtd.ApduTransportException] passes through unchanged;
 * - any other failure while sending, including cancellation.
 *
 * Every later [send] throws [SecureMessagingException] with [SmFailure.SESSION_CLOSED] without
 * sending anything. The channel owns [keys] (and the keys given to [restart]) and wipes them when
 * the session ends; it copies [ssc].
 */
internal class SecureChannel(
    private val inner: ApduSender,
    crypto: ChipCrypto,
    keys: SessionKeys,
    ssc: ByteArray,
) : ApduSender {
    private val sm = SecureMessaging(crypto, keys, ssc)

    /** Whether the session is still usable. */
    var isOpen: Boolean = true
        private set

    /** The cipher of the current session keys. */
    val cipher: SmCipher
        get() = sm.cipher

    override val maxCommandLength: Int
        get() = inner.maxCommandLength

    override val supportsExtendedLength: Boolean
        get() = inner.supportsExtendedLength

    override suspend fun send(
        command: CommandApdu,
        extendedLengthAllowed: Boolean,
    ): ResponseApdu {
        if (!isOpen) throw SecureMessagingException(SmFailure.SESSION_CLOSED)
        try {
            val answer = inner.send(sm.protect(command), extendedLengthAllowed)
            return sm.unprotect(answer)
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    /**
     * Restarts secure messaging with new keys and start counter (Part 11, section 9.8.2), after the
     * key agreement of chip authentication succeeded. Its commands, including the answer to the
     * last one, went through this channel with the old keys; the next command uses the new ones.
     */
    fun restart(
        newKeys: SessionKeys,
        newSsc: ByteArray,
    ) {
        if (!isOpen) throw SecureMessagingException(SmFailure.SESSION_CLOSED)
        sm.restart(newKeys, newSsc)
    }

    /** Ends the session and wipes its keys. Safe to call more than once. */
    fun close() {
        if (!isOpen) return
        isOpen = false
        sm.wipe()
    }

    /** A copy of the current send sequence counter, for tests. */
    internal fun sscCopy(): ByteArray = sm.sscCopy()
}
