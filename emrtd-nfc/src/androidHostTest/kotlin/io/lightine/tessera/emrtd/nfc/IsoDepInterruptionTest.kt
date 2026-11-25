package io.lightine.tessera.emrtd.nfc

import android.nfc.TagLostException
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A stand-in for the platform's IsoDep that can block in [transceive] until the test releases it,
 * as the platform blocks until the chip answers or the time limit passes, and can throw any
 * exception type the platform throws, at any call.
 */
private class BlockingChannel : IsoDepChannel {
    @Volatile var connected = false

    @Volatile var block = false

    @Volatile var transceiveFailure: Throwable? = null

    @Volatile var closeFailure: Throwable? = null

    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val sent: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
    val closeCalls = AtomicInteger()

    override val isConnected: Boolean get() = connected
    override val maxTransceiveLength: Int = 261
    override val isExtendedLengthApduSupported: Boolean = false

    override fun connect() {
        connected = true
    }

    override fun setTimeout(timeoutMillis: Int) = Unit

    override fun transceive(command: ByteArray): ByteArray {
        sent += command
        if (block) {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "the test never released the call" }
        }
        transceiveFailure?.let { throw it }
        return byteArrayOf(0x90.toByte(), 0x00)
    }

    override fun close() {
        closeCalls.incrementAndGet()
        connected = false
        closed.countDown()
        closeFailure?.let { throw it }
    }
}

/**
 * TES-345: every interruption of an Android read ends cleanly. One test per cell of the matrix
 * "stage x interruption", named `stage - interruption - what must hold`. Clean means: a typed
 * error (or the cancellation) and no hang; `IsoDep` closed once (the transport's owner calls
 * close; after a cancelled command the transport closes itself); nothing sent after the
 * interruption; a new transport on a new tag right after works like a first one.
 *
 * These run the blocking calls on a real thread, as `Dispatchers.IO` does, so a blocked platform
 * call is really blocked.
 */
@RunWith(RobolectricTestRunner::class)
class IsoDepInterruptionTest {
    private val io = Executors.newFixedThreadPool(4)
    private val dispatcher: CoroutineDispatcher = io.asCoroutineDispatcher()
    private val read = byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x01)

    @After
    fun stop() {
        io.shutdownNow()
    }

    private fun transport(channel: IsoDepChannel) = IsoDepTransport(channel, 4_000, dispatcher)

    private suspend fun errorOf(block: suspend () -> Unit): NfcError = assertFailsWith<ApduTransportException> { block() }.error

    /** Point 4: a new transport on the next discovered tag reads like a first one. */
    private suspend fun aNewTransportOnANewTagReadsLikeAFirstOne() {
        val next = BlockingChannel()
        val transport = IsoDepTransport.open(next, 4_000, dispatcher)
        transport.transceive(read)
        transport.close()
        assertTrue(next.closed.await(5, TimeUnit.SECONDS))
        assertEquals(1, next.sent.size)
        assertEquals(1, next.closeCalls.get())
    }

    @Test
    fun `during a command - the app cancels the coroutine - waits for the platform, drops the answer, closes itself`() =
        runBlocking {
            val channel = BlockingChannel().apply { block = true }
            val transport = transport(channel)
            val first = async(Dispatchers.Default) { transport.transceive(read) }
            assertTrue(channel.entered.await(5, TimeUnit.SECONDS))
            // A second command queued behind the blocked one.
            val second = async(Dispatchers.Default) { runCatching { transport.transceive(read) }.exceptionOrNull() }

            first.cancel()
            delay(100)
            assertFalse(first.isCompleted, "the call waits for the platform call to return")
            assertEquals(1, channel.sent.size, "nothing overlaps the blocked command")

            channel.release.countDown()
            assertFailsWith<CancellationException> { first.await() }

            assertEquals(NfcUnavailable(NfcUnavailable.Reason.CLOSED), (second.await() as ApduTransportException).error)
            assertEquals(NfcUnavailable(NfcUnavailable.Reason.CLOSED), errorOf { transport.transceive(read) })
            assertEquals(1, channel.sent.size, "nothing is sent after the cancelled command")
            assertTrue(channel.closed.await(5, TimeUnit.SECONDS), "the transport closed IsoDep itself")
            transport.close()
            assertEquals(1, channel.closeCalls.get())
            aNewTransportOnANewTagReadsLikeAFirstOne()
        }

    @Test
    fun `waiting for a command - the app cancels before it reaches the platform - the transport stays usable`() =
        runBlocking {
            val channel = BlockingChannel().apply { block = true }
            val transport = transport(channel)
            val first = async(Dispatchers.Default) { transport.transceive(read) }
            assertTrue(channel.entered.await(5, TimeUnit.SECONDS))
            val queued = async(Dispatchers.Default) { transport.transceive(read) }
            delay(50)

            queued.cancel()
            channel.release.countDown()

            first.await()
            assertFailsWith<CancellationException> { queued.await() }
            transport.transceive(read)
            assertEquals(2, channel.sent.size)
            assertEquals(0, channel.closeCalls.get())
            transport.close()
        }

    @Test
    fun `during a command - close from another thread - the blocked call throws CLOSED and its answer is dropped`() =
        runBlocking {
            val channel = BlockingChannel().apply { block = true }
            val transport = transport(channel)
            val call = async(Dispatchers.Default) { runCatching { transport.transceive(read) }.exceptionOrNull() }
            assertTrue(channel.entered.await(5, TimeUnit.SECONDS))

            transport.close() // returns at once although the platform call is blocked
            channel.release.countDown()

            assertEquals(
                NfcUnavailable(NfcUnavailable.Reason.CLOSED),
                (withTimeout(5_000) { call.await() } as ApduTransportException).error,
            )
            assertEquals(NfcUnavailable(NfcUnavailable.Reason.CLOSED), errorOf { transport.transceive(read) })
            assertEquals(1, channel.sent.size)
            assertTrue(channel.closed.await(5, TimeUnit.SECONDS))
            assertEquals(1, channel.closeCalls.get())
            aNewTransportOnANewTagReadsLikeAFirstOne()
        }

    @Test
    fun `during a command - the card moves away, reader mode is turned off, or the tag is out of date - typed, done, nothing more sent`() =
        runBlocking {
            val thrown =
                listOf(
                    TagLostException("Tag was lost."),
                    IOException("Transceive failed"),
                    SecurityException("Permission Denial: Tag is out of date"),
                    IllegalStateException("Call connect() first!"),
                )
            for (failure in thrown) {
                val channel = BlockingChannel().apply { transceiveFailure = failure }
                val transport = transport(channel)
                assertEquals(NfcTagLost, errorOf { transport.transceive(read) }, "$failure")
                assertEquals(NfcTagLost, errorOf { transport.transceive(read) }, "$failure")
                assertEquals(1, channel.sent.size, "$failure: nothing is sent after the failure")
                transport.close()
                assertTrue(channel.closed.await(5, TimeUnit.SECONDS), "$failure")
                assertEquals(1, channel.closeCalls.get())
            }
            aNewTransportOnANewTagReadsLikeAFirstOne()
        }

    @Test
    fun `ending - a platform close that throws any type is swallowed and the transport stays closed`() =
        runBlocking {
            for (failure in listOf(IOException("close failed"), SecurityException("out of date"), IllegalStateException("gone"))) {
                val channel = BlockingChannel().apply { closeFailure = failure }
                val transport = transport(channel)
                transport.transceive(read)
                transport.close()
                transport.close()
                assertTrue(channel.closed.await(5, TimeUnit.SECONDS))
                assertEquals(NfcUnavailable(NfcUnavailable.Reason.CLOSED), errorOf { transport.transceive(read) })
                assertEquals(1, channel.closeCalls.get(), "$failure")
                assertEquals(1, channel.sent.size)
            }
        }

    @Test
    fun `before a command - a stale tag that cannot connect - typed, nothing sent, closable`() =
        runBlocking {
            val channel =
                object : IsoDepChannel by BlockingChannel() {
                    val sent = AtomicInteger()

                    override fun connect(): Unit = throw SecurityException("Permission Denial: Tag is out of date")

                    override fun transceive(command: ByteArray): ByteArray {
                        sent.incrementAndGet()
                        return byteArrayOf(0x90.toByte(), 0x00)
                    }
                }
            assertEquals(NfcTagLost, errorOf { IsoDepTransport.open(channel, 4_000, dispatcher) })
            assertEquals(0, channel.sent.get())
            aNewTransportOnANewTagReadsLikeAFirstOne()
        }

    @Test
    fun `between reads - the owner closes in finally after any outcome - IsoDep is closed once`() =
        runBlocking {
            for (failure in listOf<Throwable?>(null, TagLostException("lost"))) {
                val channel = BlockingChannel().apply { transceiveFailure = failure }
                runCatching { transport(channel).use { it.transceive(read) } }
                assertTrue(channel.closed.await(5, TimeUnit.SECONDS))
                assertEquals(1, channel.closeCalls.get())
            }
            aNewTransportOnANewTagReadsLikeAFirstOne()
        }
}
