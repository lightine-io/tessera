package io.lightine.tessera.emrtd.internal.sm

import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.internal.apdu.ApduChannel
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu

/**
 * Sends one command APDU and returns the chip's answer: the seam the access protocols and the file
 * code send through. [PlainExchange] sends commands as they are; [SecureChannel] is a decorator that
 * protects each command with secure messaging and checks each answer.
 *
 * The shape matches the file reader's sender seam (`internal.files`), so one adapter joins the two.
 */
internal interface ApduExchange {
    /** The longest encoded command the transport sends. */
    val maxCommandLength: Int

    /** Whether the transport carries extended-length APDUs. */
    val supportsExtendedLength: Boolean

    /**
     * Sends [command] and returns the answer. [extendedLengthAllowed] says whether the chip has
     * stated it accepts extended length (Part 11, section 9.3.1.2). Transport failures pass through
     * as [io.lightine.tessera.emrtd.ApduTransportException].
     */
    suspend fun send(
        command: CommandApdu,
        extendedLengthAllowed: Boolean = false,
    ): ResponseApdu
}

/** Sends commands unprotected over an [ApduTransport], through [ApduChannel]. */
internal class PlainExchange(
    private val transport: ApduTransport,
) : ApduExchange {
    override val maxCommandLength: Int
        get() = transport.maxCommandLength

    override val supportsExtendedLength: Boolean
        get() = transport.supportsExtendedLength

    override suspend fun send(
        command: CommandApdu,
        extendedLengthAllowed: Boolean,
    ): ResponseApdu = ApduChannel(transport, extendedLengthAllowed).send(command)
}
