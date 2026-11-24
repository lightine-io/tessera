package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreNfcTransportTest {
    private val select = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x0C, 0x02, 0x01, 0x02)

    private suspend fun errorOf(block: suspend () -> Unit): NfcError = assertFailsWith<ApduTransportException> { block() }.error

    @Test
    fun sends_the_command_as_given_and_returns_the_answer_with_its_status_word() =
        runTest {
            val channel = FakeChannel().apply { answer = byteArrayOf(0x01, 0x02, 0x90.toByte(), 0x00) }
            val transport = CoreNfcTransport(channel)

            val answer = transport.transceive(select)

            assertContentEquals(select, channel.sent.single())
            assertContentEquals(byteArrayOf(0x01, 0x02, 0x90.toByte(), 0x00), answer)
        }

    @Test
    fun short_apdus_only() {
        val transport = CoreNfcTransport(FakeChannel())
        assertEquals(261, transport.maxCommandLength)
        assertFalse(transport.supportsExtendedLength)
    }

    @Test
    fun reports_what_the_system_says_about_pace() {
        assertEquals(true, CoreNfcTransport(FakeChannel(supportsPace = true)).supportsPace)
        assertEquals(false, CoreNfcTransport(FakeChannel(supportsPace = false)).supportsPace)
        assertNull(CoreNfcTransport(FakeChannel(supportsPace = null)).supportsPace)
    }

    @Test
    fun a_platform_error_is_thrown_as_its_typed_error() =
        runTest {
            listOf(NfcTagLost, NfcTimeout(null), NfcUnavailable(NfcUnavailable.Reason.CANCELLED_BY_USER)).forEach { expected ->
                val transport = CoreNfcTransport(FakeChannel().apply { error = expected })
                assertEquals(expected, errorOf { transport.transceive(select) })
            }
        }

    @Test
    fun after_close_nothing_is_sent_and_the_error_is_closed() =
        runTest {
            val channel = FakeChannel()
            val transport = CoreNfcTransport(channel)
            transport.close()
            transport.close()

            assertEquals(NfcUnavailable(NfcUnavailable.Reason.CLOSED), errorOf { transport.transceive(select) })
            assertTrue(channel.sent.isEmpty())
        }

    @Test
    fun a_stop_while_a_command_is_in_flight_ends_it_with_the_stop_reason() =
        runTest {
            val channel = FakeChannel().apply { holdAnswers = true }
            val transport = CoreNfcTransport(channel)
            val call = async { runCatching { transport.transceive(select) } }
            runCurrent()
            assertEquals(1, channel.held.size)

            val cancelled = NfcUnavailable(NfcUnavailable.Reason.CANCELLED_BY_USER)
            transport.stop(cancelled)
            // The platform's own late answer names only that the session ended; it changes nothing.
            channel.held.single().invoke(null, NfcUnavailable(NfcUnavailable.Reason.CLOSED))

            assertEquals(cancelled, (call.await().exceptionOrNull() as ApduTransportException).error)
            assertEquals(cancelled, errorOf { transport.transceive(select) })
        }

    @Test
    fun commands_go_one_at_a_time() =
        runTest {
            val channel = FakeChannel().apply { holdAnswers = true }
            val transport = CoreNfcTransport(channel)
            val first = async { transport.transceive(byteArrayOf(1)) }
            val second = async { transport.transceive(byteArrayOf(2)) }
            runCurrent()
            assertEquals(1, channel.sent.size, "the second command waits for the first answer")

            channel.held[0].invoke(byteArrayOf(0x90.toByte(), 0x00), null)
            runCurrent()
            assertEquals(2, channel.sent.size)
            channel.held[1].invoke(byteArrayOf(0x6A, 0x82.toByte()), null)

            assertContentEquals(byteArrayOf(0x90.toByte(), 0x00), first.await())
            assertContentEquals(byteArrayOf(0x6A, 0x82.toByte()), second.await())
        }

    @Test
    fun a_cancelled_command_stops_the_transport_so_no_second_command_can_overlap_it() =
        runTest {
            val channel = FakeChannel().apply { holdAnswers = true }
            val transport = CoreNfcTransport(channel)
            val first = async { transport.transceive(byteArrayOf(1)) }
            runCurrent()

            first.cancel()
            runCurrent()
            // Core NFC still has the first command; a second one must not go out beside it.
            assertEquals(NfcUnavailable(NfcUnavailable.Reason.CLOSED), errorOf { transport.transceive(byteArrayOf(2)) })
            channel.held.single().invoke(byteArrayOf(0x90.toByte(), 0x00), null)

            assertEquals(1, channel.sent.size)
            assertEquals(NfcUnavailable(NfcUnavailable.Reason.CLOSED), errorOf { transport.transceive(byteArrayOf(3)) })
            assertEquals(1, channel.sent.size)
        }

    @Test
    fun a_command_waiting_behind_a_cancelled_one_is_not_sent() =
        runTest {
            val channel = FakeChannel().apply { holdAnswers = true }
            val transport = CoreNfcTransport(channel)
            val first = async { transport.transceive(byteArrayOf(1)) }
            val second = async { runCatching { transport.transceive(byteArrayOf(2)) }.exceptionOrNull() }
            runCurrent()

            first.cancel()
            runCurrent()

            assertEquals(NfcUnavailable(NfcUnavailable.Reason.CLOSED), (second.await() as ApduTransportException).error)
            assertEquals(1, channel.sent.size)
        }

    @Test
    fun close_from_another_thread_during_a_command_ends_it_at_once() =
        runTest {
            val channel = FakeChannel().apply { holdAnswers = true }
            val transport = CoreNfcTransport(channel)
            val call = async { runCatching { transport.transceive(select) }.exceptionOrNull() }
            runCurrent()

            transport.close()

            assertEquals(NfcUnavailable(NfcUnavailable.Reason.CLOSED), (call.await() as ApduTransportException).error)
            channel.held.single().invoke(byteArrayOf(0x90.toByte(), 0x00), null)
            assertEquals(1, channel.sent.size)
        }

    @Test
    fun the_first_stop_reason_stays_whatever_comes_after() =
        runTest {
            val transport = CoreNfcTransport(FakeChannel())
            transport.stop(NfcTagLost)
            transport.close()
            transport.stop(NfcTimeout(null))
            assertEquals(NfcTagLost, errorOf { transport.transceive(select) })
        }

    @Test
    fun bytes_cross_the_nsdata_bridge_unchanged() {
        val bytes = ByteArray(256) { it.toByte() }
        assertContentEquals(bytes, bytes.toNSData().toByteArray())
        assertContentEquals(ByteArray(0), ByteArray(0).toNSData().toByteArray())
    }
}
