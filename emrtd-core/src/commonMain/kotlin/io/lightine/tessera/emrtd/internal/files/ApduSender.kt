package io.lightine.tessera.emrtd.internal.files

import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.internal.apdu.ApduChannel
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu

/** The APDU seam used by file operations; a secure-messaging sender can wrap it later. */
internal interface ApduSender {
    val maxCommandLength: Int
    val supportsExtendedLength: Boolean

    suspend fun send(
        command: CommandApdu,
        extendedLengthAllowed: Boolean,
    ): ResponseApdu
}

/** Sends plain APDUs through the platform transport. */
internal class PlainApduSender(
    private val transport: ApduTransport,
) : ApduSender {
    override val maxCommandLength: Int get() = transport.maxCommandLength
    override val supportsExtendedLength: Boolean get() = transport.supportsExtendedLength

    override suspend fun send(
        command: CommandApdu,
        extendedLengthAllowed: Boolean,
    ): ResponseApdu = ApduChannel(transport, extendedLengthAllowed).send(command)
}
