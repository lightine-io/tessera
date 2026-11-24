package io.lightine.tessera.emrtd.nfc

import android.nfc.TagLostException
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
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
    var setTimeoutFailure: Throwable? = null
    val sent = mutableListOf<ByteArray>()
    var connectCalls = 0
    var setTimeoutCalls = 0
    var transceiveCalls = 0
    var closeCalls = 0
    var threadName: String? = null
    var closeThreadName: String? = null
    val calls = mutableListOf<String>()

    override val isConnected: Boolean get() = connected

    override fun connect() {
        connectCalls++
        calls += "connect"
        connectFailure?.let { throw it }
        connected = true
    }

    override fun setTimeout(timeoutMillis: Int) {
        setTimeoutCalls++
        calls += "setTimeout"
        setTimeoutFailure?.let { throw it }
        timeoutSet = timeoutMillis
    }

    override fun transceive(command: ByteArray): ByteArray {
        transceiveCalls++
        calls += "transceive"
        threadName = Thread.currentThread().name
        transceiveFailure?.let { throw it }
        sent += command
        return answer
    }

    override fun close() {
        closeThreadName = Thread.currentThread().name
        closeCalls++
        connected = false
    }
}

// advanceUntilIdle runs the platform close that close() hands to the I/O dispatcher.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class IsoDepTransportTest {
    private suspend fun errorOf(block: suspend () -> Unit): NfcError {
        val e = assertFailsWith<ApduTransportException> { block() }
        return e.error
    }

    private fun TestScope.transport(
        channel: IsoDepChannel,
        timeoutMillis: Int = 4_000,
        clock: () -> Long = System::nanoTime,
    ) = IsoDepTransport(channel, timeoutMillis, StandardTestDispatcher(testScheduler), clock)

    /** A clock that moves [stepMillis] on every reading. */
    private fun steppingClock(stepMillis: Long): () -> Long {
        var now = 0L
        return { now.also { now += stepMillis * 1_000_000 } }
    }

    // --- Sending ------------------------------------------------------------------------------

    @Test
    fun `sends the command unchanged and returns the answer unchanged`() =
        runTest {
            val channel = FakeChannel().apply { answer = byteArrayOf(1, 2, 3, 0x90.toByte(), 0) }
            val transport = transport(channel)
            val command = byteArrayOf(0, 0xA4.toByte(), 4, 0xC, 0)

            val response = transport.transceive(command)

            assertContentEquals(command, channel.sent.single())
            assertContentEquals(byteArrayOf(1, 2, 3, 0x90.toByte(), 0), response)
        }

    @Test
    fun `runs the blocking call off the calling dispatcher`() =
        runTest {
            val channel = FakeChannel()
            val transport = IsoDepTransport(channel, 4_000, Dispatchers.IO)

            transport.transceive(byteArrayOf(0))

            assertTrue(channel.threadName!!.contains("DefaultDispatcher"), channel.threadName)
        }

    // --- Connecting once ----------------------------------------------------------------------

    @Test
    fun `connects on first use and sets the time limit before the first command`() =
        runTest {
            val channel = FakeChannel()
            val transport = transport(channel)

            transport.transceive(byteArrayOf(0))
            transport.transceive(byteArrayOf(0))

            assertEquals(listOf("connect", "setTimeout", "transceive", "transceive"), channel.calls)
            assertEquals(4_000, channel.timeoutSet)
        }

    @Test
    fun `does not connect a connected tag again but still sets the time limit once`() =
        runTest {
            val channel = FakeChannel().apply { connected = true }
            val transport = transport(channel)

            transport.transceive(byteArrayOf(0))
            transport.transceive(byteArrayOf(0))

            assertEquals(0, channel.connectCalls)
            assertEquals(1, channel.setTimeoutCalls)
            assertEquals(4_000, channel.timeoutSet)
        }

    @Test
    fun `never connects again when the platform says the tag is no longer connected`() =
        runTest {
            val channel = FakeChannel()
            val transport = transport(channel)
            transport.transceive(byteArrayOf(0))

            channel.connected = false // the link dropped between two commands
            transport.transceive(byteArrayOf(0))

            assertEquals(1, channel.connectCalls)
            assertEquals(1, channel.setTimeoutCalls)
        }

    @Test
    fun `open connects, sets the time limit and returns the transport`() =
        runTest {
            val channel = FakeChannel()

            val transport = IsoDepTransport.open(channel, 3_000, StandardTestDispatcher(testScheduler))
            transport.transceive(byteArrayOf(0))

            assertEquals(listOf("connect", "setTimeout", "transceive"), channel.calls)
            assertEquals(3_000, channel.timeoutSet)
        }

    // --- Error mapping ------------------------------------------------------------------------

    @Test
    fun `TagLostException is NfcTagLost and the cause carries the time the call took`() =
        runTest {
            val platform = TagLostException("Tag was lost.")
            val channel = FakeChannel().apply { transceiveFailure = platform }
            val transport = transport(channel, clock = steppingClock(4_990))

            val e = assertFailsWith<ApduTransportException> { transport.transceive(byteArrayOf(0)) }

            assertEquals(NfcTagLost, e.error)
            val cause = assertIs<IsoDepCallFailure>(e.cause)
            assertEquals(4_990, cause.elapsedMillis)
            assertEquals("IsoDep.transceive threw TagLostException after 4990 ms (time limit 4000 ms)", cause.message)
            assertSame(platform, cause.cause)
        }

    @Test
    fun `a TagLostException at once carries a short time`() =
        runTest {
            val channel = FakeChannel().apply { transceiveFailure = TagLostException("Tag was lost.") }
            val transport = transport(channel, clock = steppingClock(3))

            val e = assertFailsWith<ApduTransportException> { transport.transceive(byteArrayOf(0)) }

            assertEquals(3, assertIs<IsoDepCallFailure>(e.cause).elapsedMillis)
        }

    @Test
    fun `an IO failure whose text says timed out is still NfcTagLost, never a guessed timeout`() =
        runTest {
            val channel = FakeChannel().apply { transceiveFailure = IOException("Transceive timed out") }

            assertEquals(NfcTagLost, errorOf { transport(channel).transceive(byteArrayOf(0)) })
        }

    @Test
    fun `any other IO failure is NfcTagLost and keeps the platform exception`() =
        runTest {
            val platform = IOException("Transceive failed")
            val channel = FakeChannel().apply { transceiveFailure = platform }

            val e = assertFailsWith<ApduTransportException> { transport(channel).transceive(byteArrayOf(0)) }

            assertEquals(NfcTagLost, e.error)
            assertSame(platform, e.cause?.cause)
        }

    @Test
    fun `an out-of-date tag object is NfcTagLost`() =
        runTest {
            val channel = FakeChannel().apply { transceiveFailure = SecurityException("Tag is out of date") }

            assertEquals(NfcTagLost, errorOf { transport(channel).transceive(byteArrayOf(0)) })
        }

    @Test
    fun `a technology the platform says is not connected is NfcTagLost`() =
        runTest {
            val channel = FakeChannel().apply { transceiveFailure = IllegalStateException("Call connect() first!") }

            assertEquals(NfcTagLost, errorOf { transport(channel).transceive(byteArrayOf(0)) })
        }

    @Test
    fun `a command the platform calls too long is NfcUnavailable NOT_SUPPORTED`() =
        runTest {
            val channel =
                FakeChannel().apply { transceiveFailure = IOException("Transceive length exceeds supported maximum") }

            assertEquals(
                NfcUnavailable(NfcUnavailable.Reason.NOT_SUPPORTED),
                errorOf { transport(channel).transceive(byteArrayOf(0)) },
            )
        }

    @Test
    fun `another technology already connected is NfcUnavailable BUSY`() =
        runTest {
            val channel =
                FakeChannel().apply { connectFailure = IOException("Only one TagTechnology can be connected at a time.") }

            assertEquals(
                NfcUnavailable(NfcUnavailable.Reason.BUSY),
                errorOf { transport(channel).transceive(byteArrayOf(0)) },
            )
        }

    @Test
    fun `a technology the adapter cannot connect is NfcUnavailable NOT_SUPPORTED`() =
        runTest {
            val channel = FakeChannel().apply { connectFailure = UnsupportedOperationException("not supported") }

            assertEquals(
                NfcUnavailable(NfcUnavailable.Reason.NOT_SUPPORTED),
                errorOf { IsoDepTransport.open(channel, 3_000, StandardTestDispatcher(testScheduler)) },
            )
        }

    @Test
    fun `a time limit the platform refuses is NfcUnavailable NOT_SUPPORTED`() =
        runTest {
            val channel = FakeChannel().apply { setTimeoutFailure = IllegalArgumentException("The supplied timeout is not valid") }

            assertEquals(
                NfcUnavailable(NfcUnavailable.Reason.NOT_SUPPORTED),
                errorOf { IsoDepTransport.open(channel, 3_000, StandardTestDispatcher(testScheduler)) },
            )
            assertEquals(0, channel.transceiveCalls)
        }

    @Test
    fun `a failing connect on first use is typed and carries the time`() =
        runTest {
            val channel = FakeChannel().apply { connectFailure = TagLostException() }

            val e = assertFailsWith<ApduTransportException> { transport(channel, clock = steppingClock(7)).transceive(byteArrayOf(0)) }

            assertEquals(NfcTagLost, e.error)
            assertEquals("IsoDep.connect threw TagLostException after 7 ms (time limit 4000 ms)", e.cause?.message)
        }

    @Test
    fun `open reports a connect failure as a typed error, never a guessed timeout`() =
        runTest {
            val channel = FakeChannel().apply { connectFailure = IOException("Connection timed out") }

            val error = errorOf { IsoDepTransport.open(channel, 3_000, StandardTestDispatcher(testScheduler)) }

            assertEquals(NfcTagLost, error)
            assertEquals(0, channel.setTimeoutCalls)
        }

    // --- A failed transport stays failed ------------------------------------------------------

    @Test
    fun `after a failure every later call throws the same error without touching the tag`() =
        runTest {
            val channel = FakeChannel().apply { transceiveFailure = TagLostException("Tag was lost.") }
            val transport = transport(channel)
            val first = assertFailsWith<ApduTransportException> { transport.transceive(byteArrayOf(0)) }

            channel.transceiveFailure = null // the tag answers again: the transport must not try
            channel.connected = false
            val second = assertFailsWith<ApduTransportException> { transport.transceive(byteArrayOf(0)) }

            assertEquals(first.error, second.error)
            assertSame(first.cause, second.cause)
            assertEquals(1, channel.transceiveCalls)
            assertEquals(1, channel.connectCalls)
        }

    @Test
    fun `after a failed connect no command reaches the tag`() =
        runTest {
            val channel = FakeChannel().apply { connectFailure = IOException("Only one TagTechnology can be connected at a time.") }
            val transport = transport(channel)
            errorOf { transport.transceive(byteArrayOf(0)) }

            channel.connectFailure = null
            val error = errorOf { transport.transceive(byteArrayOf(0)) }

            assertEquals(NfcUnavailable(NfcUnavailable.Reason.BUSY), error)
            assertEquals(1, channel.connectCalls)
            assertEquals(0, channel.transceiveCalls)
        }

    // --- Closing ------------------------------------------------------------------------------

    @Test
    fun `a closed transport is NfcUnavailable and the platform is closed once`() =
        runTest {
            val channel = FakeChannel()
            val transport = transport(channel)
            transport.transceive(byteArrayOf(0))

            transport.close()
            transport.close()
            advanceUntilIdle()

            val error = errorOf { transport.transceive(byteArrayOf(0)) }
            assertEquals(NfcUnavailable(NfcUnavailable.Reason.CLOSED), error)
            assertEquals(1, channel.closeCalls)
            assertEquals(1, channel.sent.size)
        }

    @Test
    fun `close returns at once and leaves the platform close to the IO dispatcher`() =
        runTest {
            val channel = FakeChannel()
            val transport = transport(channel)

            transport.close()
            assertEquals(0, channel.closeCalls) // nothing ran on the calling thread

            advanceUntilIdle()
            assertEquals(1, channel.closeCalls)
        }

    @Test
    fun `the platform close runs on an IO thread`() {
        val channel = FakeChannel()
        val transport = IsoDepTransport(channel, 4_000, Dispatchers.IO)
        val caller = Thread.currentThread().name

        transport.close()
        val deadline = System.currentTimeMillis() + 5_000
        while (channel.closeThreadName == null && System.currentTimeMillis() < deadline) Thread.sleep(10)

        val closeThread = channel.closeThreadName!!
        assertTrue(closeThread != caller && closeThread.contains("DefaultDispatcher"), closeThread)
    }

    @Test
    fun `a platform close that throws is swallowed`() =
        runTest {
            val channel =
                object : IsoDepChannel by FakeChannel() {
                    override fun close(): Unit = throw SecurityException("Tag is out of date")
                }
            val transport = transport(channel)

            transport.close()
            advanceUntilIdle()

            assertEquals(NfcUnavailable(NfcUnavailable.Reason.CLOSED), errorOf { transport.transceive(byteArrayOf(0)) })
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
            transport = transport(channel)

            assertEquals(NfcUnavailable(NfcUnavailable.Reason.CLOSED), errorOf { transport.transceive(byteArrayOf(0)) })
        }

    // --- Limits -------------------------------------------------------------------------------

    @Test
    fun `reports short APDU limits when extended length is not supported`() {
        val transport = IsoDepTransport(FakeChannel(maxTransceiveLength = 65_279), 1_000, Dispatchers.IO)

        assertFalse(transport.supportsExtendedLength)
        assertEquals(261, transport.maxCommandLength)
    }

    @Test
    fun `reports the platform limit when extended length is supported`() {
        val transport =
            IsoDepTransport(
                FakeChannel(maxTransceiveLength = 65_279, isExtendedLengthApduSupported = true),
                1_000,
                Dispatchers.IO,
            )

        assertTrue(transport.supportsExtendedLength)
        assertEquals(65_279, transport.maxCommandLength)
    }

    @Test
    fun `a tag that already left makes the limits fall back to the short APDU`() {
        val gone =
            object : IsoDepChannel by FakeChannel() {
                override val maxTransceiveLength: Int get() = throw SecurityException("gone")
                override val isExtendedLengthApduSupported: Boolean get() = throw SecurityException("gone")
            }

        val transport = IsoDepTransport(gone, 1_000, Dispatchers.IO)

        assertFalse(transport.supportsExtendedLength)
        assertEquals(261, transport.maxCommandLength)
    }

    @Test
    fun `a time limit that is not positive is refused`() {
        assertFailsWith<IllegalArgumentException> {
            IsoDepTransport(FakeChannel(), 0, Dispatchers.IO)
        }
    }

    @Test
    fun `the default time limit is 5 seconds`() {
        assertEquals(5_000, IsoDepTransport.DEFAULT_TIMEOUT_MILLIS)
    }
}
