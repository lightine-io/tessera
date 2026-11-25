@file:OptIn(ExperimentalNativeApi::class)

package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import io.lightine.tessera.types.errors.NfcUnavailable.Reason
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreNfcSessionTest {
    private companion object {
        const val FAILURE = "Reading failed"
    }

    private var port: FakeSessionPort? = null
    private var onBegin: (FakeSessionPort) -> Unit = {}

    private fun session(
        alert: String = "Hold your iPhone near the document",
        readingAvailable: Boolean = true,
    ) = CoreNfcSession(alert, FAILURE, { readingAvailable }) { events ->
        FakeSessionPort(events).also {
            it.onBegin = onBegin
            port = it
        }
    }

    private fun sessionEnd(code: Long) = nfcErrorFor(platform.CoreNFC.NFCErrorDomain, code, CoreNfcErrorSource.SESSION_END)

    private suspend fun errorOf(block: suspend () -> Unit): NfcError = assertFailsWith<ApduTransportException> { block() }.error

    @Test
    fun connects_to_the_one_iso7816_tag_and_returns_a_working_transport() =
        runTest {
            val channel = FakeChannel(supportsPace = true)
            val tag = FakeTag(channel)
            onBegin = { it.events.onTagsDetected(listOf(tag)) }
            val session = session(alert = "Hold it")

            val transport = session.connect()

            assertEquals(listOf("Hold it"), port!!.alerts)
            assertTrue(port!!.began)
            assertEquals(1, tag.connectCalls)
            assertEquals(true, transport.supportsPace)
            assertContentEquals(byteArrayOf(0x90.toByte(), 0x00), transport.transceive(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x01)))
        }

    @Test
    fun more_than_one_tag_or_a_tag_that_is_not_iso7816_keeps_polling() =
        runTest {
            val chip = FakeTag(FakeChannel())
            val session = session()
            val connected = async { session.connect() }
            runCurrent()
            val events = port!!.events

            events.onTagsDetected(listOf(FakeTag(FakeChannel()), FakeTag(FakeChannel())))
            events.onTagsDetected(listOf(FakeTag(iso7816 = null)))
            assertEquals(2, port!!.restarts)
            assertFalse(connected.isCompleted)

            events.onTagsDetected(listOf(chip))
            connected.await()
            assertEquals(1, chip.connectCalls)
        }

    @Test
    fun a_lost_link_or_no_answer_while_connecting_restarts_polling_and_keeps_waiting() =
        runTest {
            val chip = FakeTag(FakeChannel())
            onBegin = {
                it.events.onTagsDetected(listOf(FakeTag(FakeChannel(), connectError = NfcTagLost)))
                it.events.onTagsDetected(listOf(FakeTag(FakeChannel(), connectError = NfcTimeout(null, NfcTimeout.Limit.COMMAND), retryable = true)))
                assertEquals(2, it.restarts)
                assertFalse(it.invalidated)
                it.events.onTagsDetected(listOf(chip))
            }
            session().connect()
            assertEquals(1, chip.connectCalls)
        }

    @Test
    fun another_connect_failure_is_thrown_and_ends_the_system_session() =
        runTest {
            val refused = NfcUnavailable(Reason.NOT_PERMITTED)
            onBegin = { it.events.onTagsDetected(listOf(FakeTag(FakeChannel(), connectError = refused))) }
            val session = session()

            assertEquals(refused, errorOf { session.connect() })

            // Ended once, with the app's failure text: never the plain ending that means success.
            assertEquals(listOf<String?>(FAILURE), port!!.endings)
            assertEquals(0, port!!.restarts)
            session.end(errorMessage = "Too late")
            assertEquals(listOf<String?>(FAILURE), port!!.endings)
        }

    @Test
    fun a_lost_tag_during_the_read_stays_a_lost_tag() =
        runTest {
            val channel = FakeChannel()
            onBegin = { it.events.onTagsDetected(listOf(FakeTag(channel))) }
            val transport = session().connect()
            val read = byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x01)

            channel.error = NfcTagLost
            assertEquals(NfcTagLost, errorOf { transport.transceive(read) })

            port!!.events.onInvalidated(sessionEnd(CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY))
            assertEquals(NfcUnavailable(Reason.ENDED_BY_SYSTEM), errorOf { transport.transceive(read) })
        }

    /** Connects a session whose port only a weak reference sees; the test keeps what it returns. */
    private suspend fun connectedWithWeakPort(): Triple<CoreNfcSession, CoreNfcTransport, WeakReference<FakeSessionPort>> {
        var weak: WeakReference<FakeSessionPort>? = null
        val session =
            CoreNfcSession("Hold your iPhone near the document", FAILURE, { true }) { events ->
                FakeSessionPort(events).also {
                    it.onBegin = { port -> port.events.onTagsDetected(listOf(FakeTag(FakeChannel()))) }
                    weak = WeakReference(it)
                }
            }
        val transport = session.connect()
        return Triple(session, transport, weak!!)
    }

    // A separate function, so no stack slot of the test keeps the object it looks at.
    private fun isAlive(weak: WeakReference<FakeSessionPort>): Boolean = weak.value != null

    private suspend fun transportOnlyWithWeakPort(): Pair<CoreNfcTransport, WeakReference<FakeSessionPort>> {
        val (_, transport, weak) = connectedWithWeakPort()
        return transport to weak
    }

    @OptIn(NativeRuntimeApi::class)
    @Test
    fun an_ended_session_lets_go_of_the_system_session() =
        runTest {
            val (session, _, weak) = connectedWithWeakPort()
            session.end()
            GC.collect()
            assertFalse(isAlive(weak))
        }

    @OptIn(NativeRuntimeApi::class)
    @Test
    fun the_transport_keeps_the_system_session_alive_until_it_stops() =
        runTest {
            val (transport, weak) = transportOnlyWithWeakPort()
            // The app kept only the transport.
            GC.collect()
            assertTrue(isAlive(weak))

            transport.close()
            // The system session has not ended: the SDK still keeps it, so its end is not lost.
            GC.collect()
            assertTrue(isAlive(weak))

            reportSystemEnd(weak)
            GC.collect()
            assertFalse(isAlive(weak))
        }

    @OptIn(NativeRuntimeApi::class)
    @Test
    fun the_app_dropping_every_reference_mid_read_keeps_the_session_until_the_system_ends_it() =
        runTest {
            val weak = droppedRead()
            // Nothing of the app's is left; the system session is still running.
            GC.collect()
            assertTrue(isAlive(weak), "the delegate must outlive the app's references while the system session runs")

            reportSystemEnd(weak)
            GC.collect()
            assertFalse(isAlive(weak))
        }

    // A read the app started and then dropped entirely: only the weak reference is returned.
    private suspend fun droppedRead(): WeakReference<FakeSessionPort> = transportOnlyWithWeakPort().second

    // The system's time limit ends the session the app forgot. A separate function, as isAlive.
    private fun reportSystemEnd(weak: WeakReference<FakeSessionPort>) {
        weak.value!!.events.onInvalidated(NfcTimeout(null, NfcTimeout.Limit.SESSION))
    }

    @Test
    fun the_person_cancelling_is_cancelled_by_user() =
        runTest {
            onBegin = { it.events.onInvalidated(NfcUnavailable(Reason.CANCELLED_BY_USER)) }
            assertEquals(NfcUnavailable(Reason.CANCELLED_BY_USER), errorOf { session().connect() })
        }

    @Test
    fun the_session_time_limit_is_a_timeout() =
        runTest {
            onBegin = { it.events.onInvalidated(NfcTimeout(null, NfcTimeout.Limit.SESSION)) }
            assertEquals(NfcTimeout(null, NfcTimeout.Limit.SESSION), errorOf { session().connect() })
        }

    @Test
    fun a_device_without_nfc_fails_without_opening_a_session() =
        runTest {
            assertEquals(NfcUnavailable(Reason.NOT_SUPPORTED), errorOf { session(readingAvailable = false).connect() })
            assertNull(port)
        }

    @Test
    fun a_missing_entitlement_is_not_permitted() =
        runTest {
            onBegin = { it.events.onInvalidated(sessionEnd(CoreNfcErrorCode.SECURITY_VIOLATION)) }
            assertEquals(NfcUnavailable(Reason.NOT_PERMITTED), errorOf { session().connect() })
        }

    @Test
    fun the_system_ending_the_session_before_any_tag_is_ended_by_system_not_a_lost_tag() =
        runTest {
            onBegin = { it.events.onInvalidated(sessionEnd(CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY)) }
            val error = errorOf { session().connect() }
            assertEquals(NfcUnavailable(Reason.ENDED_BY_SYSTEM), error)
            assertNotEquals<NfcError>(NfcTagLost, error)
            assertEquals(0, port!!.restarts)
        }

    @Test
    fun the_system_ending_the_session_during_the_read_stops_the_transport_with_that_reason() =
        runTest {
            onBegin = { it.events.onTagsDetected(listOf(FakeTag(FakeChannel()))) }
            val transport = session().connect()

            port!!.events.onInvalidated(sessionEnd(CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY))

            assertEquals(
                NfcUnavailable(Reason.ENDED_BY_SYSTEM),
                errorOf { transport.transceive(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x01)) },
            )
        }

    @Test
    fun a_busy_reader_is_busy() =
        runTest {
            onBegin = { it.events.onInvalidated(sessionEnd(CoreNfcErrorCode.SYSTEM_IS_BUSY)) }
            assertEquals(NfcUnavailable(Reason.BUSY), errorOf { session().connect() })
        }

    @Test
    fun the_app_ending_the_session_stays_closed_even_when_the_system_reports_another_code() =
        runTest {
            val session = session()
            val connected = async { runCatching { session.connect() }.exceptionOrNull() }
            runCurrent()

            session.end()
            port!!.events.onInvalidated(sessionEnd(CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY))

            assertEquals(NfcUnavailable(Reason.CLOSED), (connected.await() as ApduTransportException).error)
        }

    @Test
    fun ending_the_session_closes_the_transport_and_the_sheet() =
        runTest {
            onBegin = { it.events.onTagsDetected(listOf(FakeTag(FakeChannel()))) }
            val session = session()
            val transport = session.connect()

            session.end()
            // Core NFC then reports "user canceled" for the app's own ending; it stays CLOSED.
            port!!.events.onInvalidated(NfcUnavailable(Reason.CANCELLED_BY_USER))
            session.end()

            assertTrue(port!!.invalidated)
            assertNull(port!!.invalidatedWith)
            assertEquals(NfcUnavailable(Reason.CLOSED), errorOf { transport.transceive(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x0C)) })
        }

    @Test
    fun ending_with_an_error_message_shows_the_callers_text() =
        runTest {
            onBegin = { it.events.onTagsDetected(listOf(FakeTag(FakeChannel()))) }
            val session = session()
            session.connect()

            session.end(errorMessage = "Reading failed")

            assertEquals("Reading failed", port!!.invalidatedWith)
        }

    @Test
    fun a_cancel_during_the_read_stops_the_transport_with_that_reason() =
        runTest {
            onBegin = { it.events.onTagsDetected(listOf(FakeTag(FakeChannel()))) }
            val session = session()
            val transport = session.connect()

            port!!.events.onInvalidated(NfcUnavailable(Reason.CANCELLED_BY_USER))

            assertEquals(
                NfcUnavailable(Reason.CANCELLED_BY_USER),
                errorOf { transport.transceive(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x01)) },
            )
        }

    @Test
    fun the_alert_message_can_be_changed_while_reading() =
        runTest {
            onBegin = { it.events.onTagsDetected(listOf(FakeTag(FakeChannel()))) }
            val session = session(alert = "First")
            session.connect()

            session.alertMessage = "Second"

            assertEquals("Second", session.alertMessage)
            assertEquals(listOf("First", "Second"), port!!.alerts)
        }

    @Test
    fun cancelling_the_coroutine_ends_the_session() =
        runTest {
            val session = session()
            val connected = async { session.connect() }
            runCurrent()
            assertTrue(port!!.began)

            connected.cancel()
            runCurrent()

            assertEquals(listOf<String?>(FAILURE), port!!.endings)
        }

    @Test
    fun connect_works_once_per_session() =
        runTest {
            onBegin = { it.events.onTagsDetected(listOf(FakeTag(FakeChannel()))) }
            val session = session()
            session.connect()
            assertFailsWith<IllegalStateException> { session.connect() }
        }

    @Test
    fun a_session_ended_before_connect_never_opens() =
        runTest {
            val session = session()
            session.end()
            assertEquals(NfcUnavailable(Reason.CLOSED), errorOf { session.connect() })
            assertNull(port)
        }

    @Test
    fun tags_after_the_chip_is_connected_are_ignored() =
        runTest {
            val first = FakeTag(FakeChannel())
            onBegin = { it.events.onTagsDetected(listOf(first)) }
            val session = session()
            session.connect()
            val late = FakeTag(FakeChannel())

            port!!.events.onTagsDetected(listOf(late))

            assertEquals(0, late.connectCalls)
            assertEquals(1, first.connectCalls)
            assertEquals(0, port!!.restarts)
        }

    @Test
    fun the_emrtd_application_id_is_the_one_icao_registers() {
        // ICAO Doc 9303 Part 10, section 3.1: the eMRTD application, AID A0 00 00 02 47 10 01.
        assertEquals("A0000002471001", CoreNfcSession.EMRTD_APPLICATION_ID)
    }
}
