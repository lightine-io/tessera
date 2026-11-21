package io.lightine.tessera.emrtd.nfc

import android.nfc.TagLostException
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A scripted stand-in for the platform's IsoDep: no device, no radio. */
private class FakeChannel(
    override val maxTransceiveLength: Int = 261,
    override val isExtendedLengthApduSupported: Boolean = false,
) : IsoDepChannel {
    var connected = false
    var connectFailure: Throwable? = null
    var transceiveFailure: Throwable? = null
    var answer: ByteArray = byteArrayOf(0x90.toByte(), 0x00)
    var timeoutSet: Int? = null
    val sent = mutableListOf<ByteArray>()
    var closeCalls = 0
    var threadName: String? = null

    override val isConnected: Boolean get() = connected

    override fun connect() {
        connectFailure?.let { throw it }
        connected = true
    }

    override fun setTimeout(timeoutMillis: Int) {
        timeoutSet = timeoutMillis
    }

    override fun transceive(command: ByteArray): ByteArray {
        threadName = Thread.currentThread().name
        transceiveFailure?.let { throw it }
        sent += command
        return answer
    }

    override fun close() {
        closeCalls++
        connected = false
    }
}

@RunWith(RobolectricTestRunner::class)
class IsoDepTransportTest {
    private suspend fun errorOf(block: suspend () -> Unit): NfcError {
        val e = assertFailsWith<ApduTransportException> { block() }
        return e.error
    }

    @Test
    fun `sends the command unchanged and returns the answer unchanged`() =
        runTest {
            val channel = FakeChannel().apply { answer = byteArrayOf(1, 2, 3, 0x90.toByte(), 0) }
            val transport = IsoDepTransport(channel, 4_000, StandardTestDispatcher(testScheduler))
            val command = byteArrayOf(0, 0xA4.toByte(), 4, 0xC, 0)

            val response = transport.transceive(command)

            assertContentEquals(command, channel.sent.single())
            assertContentEquals(byteArrayOf(1, 2, 3, 0x90.toByte(), 0), response)
        }

    @Test
    fun `connects on first use and sets the time limit after connecting`() =
        runTest {
            val channel = FakeChannel()
            val transport = IsoDepTransport(channel, 4_000, StandardTestDispatcher(testScheduler))

            transport.transceive(byteArrayOf(0))

            assertTrue(channel.connected)
            assertEquals(4_000, channel.timeoutSet)
        }

    @Test
    fun `does not reconnect a connected tag`() =
        runTest {
            val channel = FakeChannel().apply { connected = true }
            val transport = IsoDepTransport(channel, 4_000, StandardTestDispatcher(testScheduler))

            transport.transceive(byteArrayOf(0))

            assertEquals(null, channel.timeoutSet)
        }

    @Test
    fun `runs the blocking call off the calling dispatcher`() =
        runTest {
            val channel = FakeChannel()
            val transport = IsoDepTransport(channel, 4_000, kotlinx.coroutines.Dispatchers.IO)

            transport.transceive(byteArrayOf(0))

            assertTrue(channel.threadName!!.contains("DefaultDispatcher"), channel.threadName)
        }

    @Test
    fun `reports short APDU limits when extended length is not supported`() {
        val transport = IsoDepTransport(FakeChannel(maxTransceiveLength = 65_279), 1_000, kotlinx.coroutines.Dispatchers.IO)

        assertFalse(transport.supportsExtendedLength)
        assertEquals(261, transport.maxCommandLength)
    }

    @Test
    fun `reports the platform limit when extended length is supported`() {
        val transport =
            IsoDepTransport(
                FakeChannel(maxTransceiveLength = 65_279, isExtendedLengthApduSupported = true),
                1_000,
                kotlinx.coroutines.Dispatchers.IO,
            )

        assertTrue(transport.supportsExtendedLength)
        assertEquals(65_279, transport.maxCommandLength)
    }

    @Test
    fun `a tag lost during an exchange is NfcTagLost`() =
        runTest {
            val channel = FakeChannel().apply { transceiveFailure = TagLostException("Tag was lost.") }
            val transport = IsoDepTransport(channel, 4_000, StandardTestDispatcher(testScheduler))

            assertEquals(NfcTagLost, errorOf { transport.transceive(byteArrayOf(0)) })
        }

    @Test
    fun `an IO failure that says timed out is NfcTimeout with the time limit`() =
        runTest {
            val channel = FakeChannel().apply { transceiveFailure = IOException("Transceive timed out") }
            val transport = IsoDepTransport(channel, 4_000, StandardTestDispatcher(testScheduler))

            assertEquals(NfcTimeout(4_000), errorOf { transport.transceive(byteArrayOf(0)) })
        }

    @Test
    fun `any other IO failure is NfcTagLost and keeps the cause`() =
        runTest {
            val cause = IOException("Transceive failed")
            val channel = FakeChannel().apply { transceiveFailure = cause }
            val transport = IsoDepTransport(channel, 4_000, StandardTestDispatcher(testScheduler))

            val e = assertFailsWith<ApduTransportException> { transport.transceive(byteArrayOf(0)) }

            assertEquals(NfcTagLost, e.error)
            assertEquals(cause, e.cause)
        }

    @Test
    fun `a tag object used after the tag left is NfcTagLost`() =
        runTest {
            val channel = FakeChannel().apply { transceiveFailure = SecurityException("Tag is out of date") }
            val transport = IsoDepTransport(channel, 4_000, StandardTestDispatcher(testScheduler))

            assertEquals(NfcTagLost, errorOf { transport.transceive(byteArrayOf(0)) })
        }

    @Test
    fun `a failing connect on first use is typed too`() =
        runTest {
            val channel = FakeChannel().apply { connectFailure = TagLostException() }
            val transport = IsoDepTransport(channel, 4_000, StandardTestDispatcher(testScheduler))

            assertEquals(NfcTagLost, errorOf { transport.transceive(byteArrayOf(0)) })
        }

    @Test
    fun `a closed transport is NfcUnavailable and the platform is closed once per call`() =
        runTest {
            val channel = FakeChannel()
            val transport = IsoDepTransport(channel, 4_000, StandardTestDispatcher(testScheduler))
            transport.transceive(byteArrayOf(0))

            transport.close()
            transport.close()

            val error = errorOf { transport.transceive(byteArrayOf(0)) }
            assertEquals(NfcUnavailable(NfcUnavailable.Reason.CLOSED), error)
            assertEquals(2, channel.closeCalls)
            assertEquals(1, channel.sent.size)
        }

    @Test
    fun `an IO failure after close is NfcUnavailable`() =
        runTest {
            lateinit var transport: IsoDepTransport
            val channel =
                object : IsoDepChannel by FakeChannel() {
                    override val isConnected: Boolean get() = true

                    override fun transceive(command: ByteArray): ByteArray {
                        // close() raced with the call: the flag is set before the IOException arrives.
                        transport.close()
                        throw IOException("canceled")
                    }
                }
            transport = IsoDepTransport(channel, 4_000, StandardTestDispatcher(testScheduler))

            assertEquals(NfcUnavailable(NfcUnavailable.Reason.CLOSED), errorOf { transport.transceive(byteArrayOf(0)) })
        }

    @Test
    fun `open connects, sets the time limit and returns the transport`() =
        runTest {
            val channel = FakeChannel()

            val transport = IsoDepTransport.open(channel, 3_000, StandardTestDispatcher(testScheduler))

            assertTrue(channel.connected)
            assertEquals(3_000, channel.timeoutSet)
            assertContentEquals(byteArrayOf(0x90.toByte(), 0), transport.transceive(byteArrayOf(0)))
        }

    @Test
    fun `open reports a connect failure as a typed error`() =
        runTest {
            val channel = FakeChannel().apply { connectFailure = IOException("Connection timed out") }

            val error = errorOf { IsoDepTransport.open(channel, 3_000, StandardTestDispatcher(testScheduler)) }

            assertEquals(NfcTimeout(3_000), error)
        }

    @Test
    fun `a tag that already left makes the limits fall back to the short APDU`() {
        val gone =
            object : IsoDepChannel by FakeChannel() {
                override val maxTransceiveLength: Int get() = throw SecurityException("gone")
                override val isExtendedLengthApduSupported: Boolean get() = throw SecurityException("gone")
            }

        val transport = IsoDepTransport(gone, 1_000, kotlinx.coroutines.Dispatchers.IO)

        assertFalse(transport.supportsExtendedLength)
        assertEquals(261, transport.maxCommandLength)
    }

    @Test
    fun `a time limit that is not positive is refused`() {
        assertFailsWith<IllegalArgumentException> {
            IsoDepTransport(FakeChannel(), 0, kotlinx.coroutines.Dispatchers.IO)
        }
    }
}
