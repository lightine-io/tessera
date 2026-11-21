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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreNfcSessionTest {
    private var port: FakeSessionPort? = null
    private var onBegin: (FakeSessionPort) -> Unit = {}

    private fun session(
        alert: String = "Hold the iPhone on the document",
        readingAvailable: Boolean = true,
    ) = CoreNfcSession(alert, { readingAvailable }) { events ->
        FakeSessionPort(events).also {
            it.onBegin = onBegin
            port = it
        }
    }

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
    fun a_failed_connect_is_thrown_as_its_typed_error() =
        runTest {
            onBegin = { it.events.onTagsDetected(listOf(FakeTag(FakeChannel(), connectError = NfcTagLost))) }
            assertEquals(NfcTagLost, errorOf { session().connect() })
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
            onBegin = { it.events.onInvalidated(NfcTimeout(null)) }
            assertEquals(NfcTimeout(null), errorOf { session().connect() })
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
            onBegin = { it.events.onInvalidated(nfcErrorFor(platform.CoreNFC.NFCErrorDomain, CoreNfcErrorCode.SECURITY_VIOLATION)) }
            assertEquals(NfcUnavailable(Reason.NOT_PERMITTED), errorOf { session().connect() })
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

            assertTrue(port!!.invalidated)
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
