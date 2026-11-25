package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The one system session that [CoreNfcSession] and [CoreNfcChipRead] share: begun once, ended once. */
class OwnedReaderSessionTest {
    private val events: ReaderSessionEvents =
        object : ReaderSessionEvents {
            override fun onTagsDetected(tags: List<DetectedTag>) = Unit

            override fun onInvalidated(
                error: NfcError,
                systemCode: Long?,
            ) {
                owned.systemEnd()
            }
        }
    private val port = FakeSessionPort(events)
    private var released = 0
    private val keeper = Any()
    private val owned: OwnedReaderSession = OwnedReaderSession(port, keeper) { released++ }

    @Test
    fun an_ending_before_begin_means_no_system_session_at_all() {
        assertTrue(owned.end("text"))
        assertFalse(owned.begin())
        assertFalse(port.began)
        assertTrue(port.endings.isEmpty())
        assertEquals(1, released)
        assertFalse(LiveSessions.contains(keeper))
    }

    @Test
    fun an_ending_during_begin_waits_for_begin_then_ends_once() {
        port.onBegin = { owned.end("during") }
        assertTrue(owned.begin())
        assertEquals(listOf<String?>("during"), port.endings)
        assertEquals(1, released)
        assertFalse(LiveSessions.contains(keeper))
    }

    @Test
    fun the_first_ending_wins_and_the_system_end_releases_once() {
        port.reportEnd = false
        owned.begin()
        assertTrue(LiveSessions.contains(keeper))
        assertTrue(owned.end(null))
        assertFalse(owned.end("second"))
        assertEquals(listOf<String?>(null), port.endings)
        assertEquals(0, released)
        assertTrue(owned.endedBySdk)
        assertTrue(owned.systemEnd())
        assertFalse(owned.systemEnd())
        assertEquals(1, released)
        assertFalse(LiveSessions.contains(keeper))
    }

    @Test
    fun after_the_system_ended_it_the_sdk_ends_nothing_and_changes_nothing() {
        owned.begin()
        port.systemEnd(NfcUnavailable(NfcUnavailable.Reason.ENDED_BY_SYSTEM))
        assertFalse(owned.end("late"))
        owned.setAlert("late")
        owned.restartPolling()
        assertTrue(port.endings.isEmpty())
        assertTrue(port.alerts.isEmpty())
        assertEquals(0, port.restarts)
        assertFalse(owned.endedBySdk)
        assertEquals(1, released)
    }
}
