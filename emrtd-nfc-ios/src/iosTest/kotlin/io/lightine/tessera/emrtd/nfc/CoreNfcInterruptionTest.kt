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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TES-345: every interruption at every stage of an iPhone read ends cleanly. Each test is one cell
 * of the matrix "stage x interruption", named `stage__interruption__what_must_hold`. Clean means:
 * a typed error at once; the system session ended exactly once and with the right kind of ending
 * (the plain one, which may show the success mark, only after a complete read; the failure text
 * otherwise; nothing from the SDK when the system ended it); no command after the interruption; a
 * new session right after behaves like a first one; nothing keeps the old session (see
 * [CoreNfcSessionTest] for the collection tests).
 */
class CoreNfcInterruptionTest {
    private companion object {
        const val FAILURE = "Reading failed"
        val READ = byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x01)
    }

    private val ports = mutableListOf<FakeSessionPort>()
    private val port: FakeSessionPort get() = ports.last()
    private var onBegin: (FakeSessionPort) -> Unit = {}

    private fun session(open: (CoreNfcSession) -> Unit = {}): CoreNfcSession {
        lateinit var created: CoreNfcSession
        created =
            CoreNfcSession("Hold it", FAILURE, { true }) { events ->
                open(created)
                FakeSessionPort(events).also {
                    it.onBegin = onBegin
                    ports += it
                }
            }
        return created
    }

    private fun sessionEnd(code: Long) = nfcErrorFor(platform.CoreNFC.NFCErrorDomain, code, CoreNfcErrorSource.SESSION_END)

    private suspend fun errorOf(block: suspend () -> Unit): NfcError = assertFailsWith<ApduTransportException> { block() }.error

    /** Point 4: a new session right after reads like a first one. */
    private suspend fun aNewSessionReadsLikeAFirstOne() {
        val channel = FakeChannel()
        onBegin = { it.events.onTagsDetected(listOf(FakeTag(channel))) }
        val next = session()
        val transport = next.connect()
        transport.transceive(READ)
        next.end()
        assertEquals(1, channel.sent.size)
        assertEquals(listOf<String?>(null), port.endings)
        assertFalse(LiveSessions.contains(next))
    }

    private suspend fun connected(channel: FakeChannel = FakeChannel()): Pair<CoreNfcSession, CoreNfcTransport> {
        onBegin = { it.events.onTagsDetected(listOf(FakeTag(channel))) }
        val session = session()
        return session to session.connect()
    }

    // --- Before any tag is found ----------------------------------------------------------------

    @Test
    fun before_a_tag__the_person_cancels__cancelled_by_user_and_the_sdk_sends_no_ending() =
        runTest {
            onBegin = { it.events.onInvalidated(NfcUnavailable(Reason.CANCELLED_BY_USER)) }
            val session = session()
            assertEquals(NfcUnavailable(Reason.CANCELLED_BY_USER), errorOf { session.connect() })
            session.end(FAILURE)
            assertTrue(port.endings.isEmpty())
            assertFalse(LiveSessions.contains(session))
            aNewSessionReadsLikeAFirstOne()
        }

    @Test
    fun before_a_tag__the_app_cancels_the_coroutine__ended_once_with_the_failure_text() =
        runTest {
            val session = session()
            val connecting = async { session.connect() }
            runCurrent()
            connecting.cancel()
            assertFailsWith<CancellationException> { connecting.await() }
            assertEquals(listOf<String?>(FAILURE), port.endings)
            assertFalse(LiveSessions.contains(session))
            aNewSessionReadsLikeAFirstOne()
        }

    @Test
    fun before_a_tag__the_app_calls_end_from_another_thread__closed_and_ended_once() =
        runTest {
            val session = session()
            val connecting = async { runCatching { session.connect() }.exceptionOrNull() }
            runCurrent()
            session.end(FAILURE)
            session.end()
            assertEquals(NfcUnavailable(Reason.CLOSED), (connecting.await() as ApduTransportException).error)
            assertEquals(listOf<String?>(FAILURE), port.endings)
            aNewSessionReadsLikeAFirstOne()
        }

    @Test
    fun before_a_tag__the_system_ends_the_session_background_or_lock__ended_by_system_and_no_ending_sent() =
        runTest {
            onBegin = { it.events.onInvalidated(sessionEnd(CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY)) }
            val session = session()
            assertEquals(NfcUnavailable(Reason.ENDED_BY_SYSTEM), errorOf { session.connect() })
            session.end()
            assertTrue(port.endings.isEmpty())
            aNewSessionReadsLikeAFirstOne()
        }

    @Test
    fun before_a_tag__the_system_refuses_a_new_session__busy_and_no_ending_sent() =
        runTest {
            onBegin = { it.events.onInvalidated(sessionEnd(CoreNfcErrorCode.SYSTEM_IS_BUSY)) }
            val session = session()
            assertEquals(NfcUnavailable(Reason.BUSY), errorOf { session.connect() })
            assertTrue(port.endings.isEmpty())
            aNewSessionReadsLikeAFirstOne()
        }

    @Test
    fun before_a_tag__the_session_time_limit__timeout_and_no_ending_sent() =
        runTest {
            onBegin = { it.events.onInvalidated(sessionEnd(CoreNfcErrorCode.SESSION_TIMEOUT)) }
            assertEquals(NfcTimeout(null), errorOf { session().connect() })
            assertTrue(port.endings.isEmpty())
            aNewSessionReadsLikeAFirstOne()
        }

    @Test
    fun before_a_tag__the_app_ends_before_the_sheet_opens__no_system_session_begins() =
        runTest {
            val session = session(open = { it.end(FAILURE) })
            assertEquals(NfcUnavailable(Reason.CLOSED), errorOf { session.connect() })
            assertFalse(port.began)
            assertTrue(port.endings.isEmpty())
            assertFalse(LiveSessions.contains(session))
            aNewSessionReadsLikeAFirstOne()
        }

    @Test
    fun before_a_tag__the_app_ends_while_the_sheet_opens__the_ending_waits_for_begin_and_is_sent_once() =
        runTest {
            lateinit var session: CoreNfcSession
            onBegin = {
                session.end(FAILURE)
                assertTrue(it.endings.isEmpty(), "no ending before begin returns")
            }
            session = session()
            assertEquals(NfcUnavailable(Reason.CLOSED), errorOf { session.connect() })
            assertEquals(listOf<String?>(FAILURE), port.endings)
            aNewSessionReadsLikeAFirstOne()
        }

    @Test
    fun before_a_tag__two_tags_or_a_tag_that_is_not_iso7816__polling_restarts_and_a_cancel_still_ends_it() =
        runTest {
            val session = session()
            val connecting = async { runCatching { session.connect() }.exceptionOrNull() }
            runCurrent()
            repeat(5) { port.events.onTagsDetected(listOf(FakeTag(FakeChannel()), FakeTag(FakeChannel()))) }
            port.events.onTagsDetected(listOf(FakeTag(iso7816 = null)))
            assertEquals(6, port.restarts)
            port.events.onInvalidated(NfcUnavailable(Reason.CANCELLED_BY_USER))
            assertEquals(NfcUnavailable(Reason.CANCELLED_BY_USER), (connecting.await() as ApduTransportException).error)
            assertTrue(port.endings.isEmpty())
        }

    // --- A tag found, connect not finished ------------------------------------------------------

    @Test
    fun connecting__the_link_keeps_failing__polling_restarts_at_most_twice_then_tag_lost_with_the_failure_text() =
        runTest {
            onBegin = { p -> repeat(3) { p.events.onTagsDetected(listOf(FakeTag(FakeChannel(), connectError = NfcTagLost))) } }
            val session = session()
            assertEquals(NfcTagLost, errorOf { session.connect() })
            assertEquals(2, port.restarts)
            assertEquals(listOf<String?>(FAILURE), port.endings)
            aNewSessionReadsLikeAFirstOne()
        }

    @Test
    fun connecting__a_lost_link_then_the_card_comes_back__connects_on_the_next_discovery() =
        runTest {
            val chip = FakeTag(FakeChannel())
            onBegin = {
                it.events.onTagsDetected(listOf(FakeTag(FakeChannel(), connectError = NfcTagLost)))
                it.events.onTagsDetected(listOf(FakeTag(FakeChannel(), connectError = NfcTagLost)))
                it.events.onTagsDetected(listOf(chip))
            }
            val session = session()
            session.connect().transceive(READ)
            session.end()
            assertEquals(2, port.restarts)
            assertEquals(listOf<String?>(null), port.endings)
        }

    @Test
    fun connecting__the_session_time_limit_passes__timeout_without_restarting_polling() =
        runTest {
            onBegin = { it.events.onTagsDetected(listOf(FakeTag(FakeChannel(), connectError = NfcTimeout(null), retryable = false))) }
            assertEquals(NfcTimeout(null), errorOf { session().connect() })
            assertEquals(0, port.restarts)
            assertEquals(listOf<String?>(FAILURE), port.endings)
        }

    @Test
    fun connecting__the_app_cancels_and_the_connect_result_comes_late__no_transport_and_ended_once() =
        runTest {
            val tag = FakeTag(FakeChannel()).apply { holdConnect = true }
            onBegin = { it.events.onTagsDetected(listOf(tag)) }
            val session = session()
            val connecting = async { session.connect() }
            runCurrent()
            connecting.cancel()
            runCurrent()

            tag.heldConnect!!.invoke(null)

            assertFailsWith<CancellationException> { connecting.await() }
            assertEquals(listOf<String?>(FAILURE), port.endings)
            assertEquals(0, port.restarts)
            aNewSessionReadsLikeAFirstOne()
        }

    @Test
    fun connecting__the_system_ends_the_session_before_the_connect_result__the_system_reason_and_no_restart() =
        runTest {
            val tag = FakeTag(FakeChannel()).apply { holdConnect = true }
            onBegin = { it.events.onTagsDetected(listOf(tag)) }
            val session = session()
            val connecting = async { runCatching { session.connect() }.exceptionOrNull() }
            runCurrent()

            port.events.onInvalidated(sessionEnd(CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY))
            tag.heldConnect!!.invoke(ConnectFailure(NfcTagLost, retryable = true))

            assertEquals(NfcUnavailable(Reason.ENDED_BY_SYSTEM), (connecting.await() as ApduTransportException).error)
            assertEquals(0, port.restarts)
            assertTrue(port.endings.isEmpty())
        }

    @Test
    fun connecting__connected_then_the_system_ends_before_the_app_resumes__one_outcome_the_transport_fails_with_the_reason() =
        runTest {
            val channel = FakeChannel()
            val tag = FakeTag(channel).apply { holdConnect = true }
            onBegin = { it.events.onTagsDetected(listOf(tag)) }
            val session = session()
            val connecting = async { session.connect() }
            runCurrent()

            tag.heldConnect!!.invoke(null)
            port.events.onInvalidated(sessionEnd(CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY))

            val transport = connecting.await()
            assertEquals(NfcUnavailable(Reason.ENDED_BY_SYSTEM), errorOf { transport.transceive(READ) })
            assertTrue(channel.sent.isEmpty())
            assertTrue(port.endings.isEmpty())
        }

    @Test
    fun connecting__a_second_tag_enters_the_field__ignored_while_the_first_connects() =
        runTest {
            val first = FakeTag(FakeChannel()).apply { holdConnect = true }
            val second = FakeTag(FakeChannel())
            onBegin = { it.events.onTagsDetected(listOf(first)) }
            val session = session()
            val connecting = async { session.connect() }
            runCurrent()

            port.events.onTagsDetected(listOf(second))
            first.heldConnect!!.invoke(null)

            connecting.await()
            assertEquals(0, second.connectCalls)
            assertEquals(0, port.restarts)
            session.end()
        }

    // --- During access, a file read or chip authentication: one command is out ------------------

    @Test
    fun during_a_command__the_card_moves_away__tag_lost_and_nothing_more_is_sent() =
        runTest {
            val channel = FakeChannel().apply { error = NfcTagLost }
            val (session, transport) = connected(channel)
            assertEquals(NfcTagLost, errorOf { transport.transceive(READ) })
            session.end(FAILURE)
            assertEquals(NfcUnavailable(Reason.CLOSED), errorOf { transport.transceive(READ) })
            assertEquals(1, channel.sent.size)
            assertEquals(listOf<String?>(FAILURE), port.endings)
            aNewSessionReadsLikeAFirstOne()
        }

    @Test
    fun during_a_command__the_app_ends_from_another_thread__closed_at_once_and_a_late_answer_changes_nothing() =
        runTest {
            val channel = FakeChannel().apply { holdAnswers = true }
            val (session, transport) = connected(channel)
            val call = async { runCatching { transport.transceive(READ) }.exceptionOrNull() }
            runCurrent()

            session.end(FAILURE)
            assertEquals(NfcUnavailable(Reason.CLOSED), (call.await() as ApduTransportException).error)
            channel.held.single().invoke(byteArrayOf(0x90.toByte(), 0x00), null)

            assertEquals(NfcUnavailable(Reason.CLOSED), errorOf { transport.transceive(READ) })
            assertEquals(1, channel.sent.size)
            assertEquals(listOf<String?>(FAILURE), port.endings)
            aNewSessionReadsLikeAFirstOne()
        }

    @Test
    fun during_a_command__the_person_cancels__cancelled_by_user_and_the_app_ending_sends_nothing() =
        runTest {
            val channel = FakeChannel().apply { holdAnswers = true }
            val (session, transport) = connected(channel)
            val call = async { runCatching { transport.transceive(READ) }.exceptionOrNull() }
            runCurrent()

            port.events.onInvalidated(NfcUnavailable(Reason.CANCELLED_BY_USER))
            session.end(FAILURE)

            assertEquals(NfcUnavailable(Reason.CANCELLED_BY_USER), (call.await() as ApduTransportException).error)
            assertTrue(port.endings.isEmpty())
            assertEquals(1, channel.sent.size)
            assertFalse(LiveSessions.contains(session))
            aNewSessionReadsLikeAFirstOne()
        }

    @Test
    fun during_a_command__the_app_goes_to_the_background__ended_by_system_and_nothing_more_is_sent() =
        runTest {
            val channel = FakeChannel().apply { holdAnswers = true }
            val (session, transport) = connected(channel)
            val call = async { runCatching { transport.transceive(READ) }.exceptionOrNull() }
            runCurrent()

            port.events.onInvalidated(sessionEnd(CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY))

            assertEquals(NfcUnavailable(Reason.ENDED_BY_SYSTEM), (call.await() as ApduTransportException).error)
            assertEquals(NfcUnavailable(Reason.ENDED_BY_SYSTEM), errorOf { transport.transceive(READ) })
            session.end(FAILURE)
            assertTrue(port.endings.isEmpty())
            assertEquals(1, channel.sent.size)
            aNewSessionReadsLikeAFirstOne()
        }

    @Test
    fun during_a_command__the_session_time_limit__timeout_and_nothing_more_is_sent() =
        runTest {
            val channel = FakeChannel().apply { holdAnswers = true }
            val (_, transport) = connected(channel)
            val call = async { runCatching { transport.transceive(READ) }.exceptionOrNull() }
            runCurrent()

            port.events.onInvalidated(sessionEnd(CoreNfcErrorCode.SESSION_TIMEOUT))

            assertEquals(NfcTimeout(null), (call.await() as ApduTransportException).error)
            assertEquals(1, channel.sent.size)
            aNewSessionReadsLikeAFirstOne()
        }

    @Test
    fun during_a_command__the_connected_tag_time_limit__the_chip_error_and_nothing_more_is_sent() =
        runTest {
            val channel = FakeChannel().apply { error = NfcTimeout(null) }
            val (session, transport) = connected(channel)
            assertEquals(NfcTimeout(null), errorOf { transport.transceive(READ) })
            session.end(FAILURE)
            assertEquals(1, channel.sent.size)
            assertEquals(listOf<String?>(FAILURE), port.endings)
        }

    @Test
    fun during_a_command__the_app_cancels_the_coroutine__the_transport_stops_and_the_session_still_ends_once() =
        runTest {
            val channel = FakeChannel().apply { holdAnswers = true }
            val (session, transport) = connected(channel)
            val call = async { transport.transceive(READ) }
            runCurrent()

            call.cancel()
            runCurrent()
            channel.held.single().invoke(byteArrayOf(0x90.toByte(), 0x00), null)

            assertEquals(NfcUnavailable(Reason.CLOSED), errorOf { transport.transceive(READ) })
            assertEquals(1, channel.sent.size)
            session.end(FAILURE)
            assertEquals(listOf<String?>(FAILURE), port.endings)
            aNewSessionReadsLikeAFirstOne()
        }

    // --- Between two reads ----------------------------------------------------------------------

    @Test
    fun between_reads__a_second_session_while_the_first_still_runs__the_system_refusal_is_busy_and_the_first_is_untouched() =
        runTest {
            val (first, transport) = connected()
            val firstPort = port
            onBegin = { it.events.onInvalidated(sessionEnd(CoreNfcErrorCode.SYSTEM_IS_BUSY)) }
            assertEquals(NfcUnavailable(Reason.BUSY), errorOf { session().connect() })

            transport.transceive(READ)
            first.end()
            assertEquals(listOf<String?>(null), firstPort.endings)
            aNewSessionReadsLikeAFirstOne()
        }

    @Test
    fun between_reads__a_session_is_used_once__a_second_connect_is_refused_without_a_new_system_session() =
        runTest {
            val (session, _) = connected()
            assertFailsWith<IllegalStateException> { session.connect() }
            assertEquals(1, ports.size)
            session.end()
        }
}
