package io.lightine.tessera.emrtd.internal.apdu

import io.lightine.tessera.emrtd.ApduTransport

/**
 * Sends one command APDU and returns the chip's one answer: the seam the access protocols and the
 * file code send through.
 *
 * Implementations agree on the shape but not on the follow-ups. [PlainApduSender] sends one command
 * and returns one raw answer; [ApduChannel] decorates a raw sender with the plain ISO/IEC 7816-4
 * answer-length hints (61xx, 6Cxx); the secure-messaging channel decorates a raw sender with
 * protection instead and never follows those plain hints.
 */
internal interface ApduSender {
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

/**
 * Sends plain APDUs through the platform transport: one command, one answer, no follow-ups. This is
 * the raw sender that [ApduChannel] (plain 61xx/6Cxx) and the secure-messaging channel decorate.
 */
internal class PlainApduSender(
    private val transport: ApduTransport,
) : ApduSender {
    override val maxCommandLength: Int
        get() = transport.maxCommandLength

    override val supportsExtendedLength: Boolean
        get() = transport.supportsExtendedLength

    override suspend fun send(
        command: CommandApdu,
        extendedLengthAllowed: Boolean,
    ): ResponseApdu {
        val bytes = command.encode(extendedLengthAllowed && transport.supportsExtendedLength)
        if (bytes.size > transport.maxCommandLength) {
            throw CommandTooLong(bytes.size, transport.maxCommandLength)
        }
        return ResponseApdu.parse(transport.transceive(bytes))
    }
}

/** The encoded command is longer than the transport can send. */
internal class CommandTooLong(
    val length: Int,
    val max: Int,
) : IllegalStateException("The command has $length bytes; the transport sends at most $max")
