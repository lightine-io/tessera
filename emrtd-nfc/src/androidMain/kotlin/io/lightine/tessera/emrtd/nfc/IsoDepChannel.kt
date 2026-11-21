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
