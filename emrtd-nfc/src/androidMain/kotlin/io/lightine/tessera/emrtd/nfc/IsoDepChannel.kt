package io.lightine.tessera.emrtd.nfc

import android.nfc.tech.IsoDep

/**
 * The part of [IsoDep] that [IsoDepTransport] uses, so the transport's error mapping and threading
 * can be tested on the JVM with a fake and no device. All calls block.
 */
internal interface IsoDepChannel {
    val isConnected: Boolean
    val maxTransceiveLength: Int
    val isExtendedLengthApduSupported: Boolean

    fun connect()

    fun setTimeout(timeoutMillis: Int)

    fun transceive(command: ByteArray): ByteArray

    fun close()

    /**
     * Lets go of a channel whose [connect] threw, with no call to the platform: a failed connect
     * leaves nothing connected, and a close would be one more call on a tag the platform has lost.
     */
    fun forget() = Unit
}

/** The real thing: forwards every call to the platform [IsoDep]. */
internal class PlatformIsoDepChannel(
    private val isoDep: IsoDep,
) : IsoDepChannel {
    override val isConnected: Boolean get() = isoDep.isConnected
    override val maxTransceiveLength: Int get() = isoDep.maxTransceiveLength
    override val isExtendedLengthApduSupported: Boolean get() = isoDep.isExtendedLengthApduSupported

    override fun connect() = isoDep.connect()

    override fun setTimeout(timeoutMillis: Int) {
        isoDep.timeout = timeoutMillis
    }

    override fun transceive(command: ByteArray): ByteArray = isoDep.transceive(command)

    override fun close() = isoDep.close()
}

/**
 * Runs [onReleased] once the platform let go of the tag after a close (or at once after [forget]),
 * whatever the close itself answers. The read entry point hands the platform's tag over to the next read only after that, so
 * a close that is still talking to the tag is never raced by a new connection.
 */
internal class ReleaseSignallingChannel(
    private val base: IsoDepChannel,
    private val onReleased: () -> Unit,
) : IsoDepChannel by base {
    override fun close() {
        try {
            base.close()
        } finally {
            onReleased()
        }
    }

    override fun forget() {
        try {
            base.forget()
        } finally {
            onReleased()
        }
    }
}
