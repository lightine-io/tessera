package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadReadiness
import io.lightine.tessera.emrtd.ChipReadState
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * TES-346, device finding: a start called 5.3 seconds after readiness said `Ready` showed nothing
 * for 13 seconds. The previous read had renewed its session after a 202, found the document and
 * ended with `KeyNotAccepted` 0.6 seconds after the renewed session began. The start must return at
 * once and publish `Starting` at once; any wait for the system happens inside the read, and the
 * readiness says when the session begins.
 *
 * The system's question whether reading is available is made to take 0.5 s of real time here, as a
 * busy system's answer may: before the fix the start asked it on the caller's thread, through the
 * readiness listener of the registry change.
 */
class CoreNfcStartLatencyTest {
    private fun ChipReadHarness.end202() {
        port.systemEnd(
            nfcErrorFor(platform.CoreNFC.NFCErrorDomain, CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY, CoreNfcErrorSource.SESSION_END),
            CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY,
        )
        step()
    }

    /** The device's previous read: a 202, a renewal, the document, a refused key; the renewed session's end never reported. */
    private fun ChipReadHarness.previousRead(): Long {
        val (_, rec) = start(options = ChipReadHarness.mrzOptions())
        step(3_000)
        reportEnd = false
        end202()
        step(1_000)
        assertEquals(2, ports.size, "renewed")
        val renewedAt = now()
        present(FakeChipTag(FakeChip(bacRefusesKey = true)))
        step(400)
        assertIs<ChipReadEnding.KeyNotAccepted>(rec.ending)
        assertTrue(now() - renewedAt <= 600, "ended 0.6 s after the renewed session began, as on the device")
        assertTrue(registry.isReleasing(), "the renewed session's end was never reported")
        return now()
    }

    @Test
    fun a_start_returns_at_once_publishes_starting_at_once_and_begins_when_readiness_said() =
        chipReadTest {
            val heard = mutableListOf<ChipReadReadiness>()
            env.readiness.add { heard += it }
            val endedAt = previousRead()
            val said = assertIs<ChipReadReadiness.NotYet>(env.readiness.current())
            assertEquals(ChipReadReadiness.NotYet::class, heard.last()::class)
            // From here on the system's answer takes 0.5 s of real time.
            onReadingAvailable = { usleep(500_000u) }
            val asked = readingAvailableAsked
            val rec = Recorder().also { it.clock = now }
            val mark = TimeSource.Monotonic.markNow()
            val read = CoreNfcChipRead.start(ChipReadHarness.canOptions(), rec, CoreNfcDefaultSheetTexts, CoreNfcReadSettings(), env)
            val took = mark.elapsedNow().inWholeMilliseconds
            assertTrue(took < 200, "the start returned after $took ms of real time")
            assertEquals(asked, readingAvailableAsked, "no question to the system on the caller's thread")
            assertEquals(listOf("Starting"), rec.names)
            assertEquals(listOf(now()), rec.updateTimes, "Starting at once")
            assertEquals(ChipReadReadiness.ReadInProgress, heard.last())
            // The read waits inside Starting for the previous session, at most until the readiness's time.
            step(said.remainingMillis - 100)
            assertEquals(2, ports.size)
            assertEquals(listOf("Starting"), rec.names)
            step(100 + 25)
            assertEquals(3, ports.size, "the session begins when the readiness said, ${now() - endedAt} ms after the previous read ended")
            assertIs<ChipReadState.WaitingForDocument>(rec.states.last())
            read.cancel()
            step()
        }

    @Test
    fun readiness_ready_means_the_next_start_begins_its_session_with_no_wait() =
        chipReadTest {
            val heard = mutableListOf<ChipReadReadiness>()
            env.readiness.add { heard += it }
            previousRead()
            val said = assertIs<ChipReadReadiness.NotYet>(env.readiness.current())
            step(said.remainingMillis)
            assertEquals(ChipReadReadiness.Ready, heard.last())
            assertEquals(ChipReadReadiness.Ready, env.readiness.current())
            // 5.3 s after Ready, as on the device.
            step(5_300)
            val (read, rec) = start(recorder = Recorder().also { it.clock = now })
            assertEquals(3, ports.size, "the session began at once")
            assertEquals(listOf("Starting", "WaitingForDocument"), rec.names)
            assertEquals(rec.updateTimes.first(), rec.updateTimes.last())
            read.cancel()
            step()
        }

    @Test
    fun a_readiness_change_never_asks_the_system() =
        chipReadTest {
            env.readiness.add { }
            val asked = readingAvailableAsked
            registry.claim(this)
            registry.ended(this, unreleased = true, now = now())
            registry.released(this)
            registry.systemEnded(now(), 1_000)
            step(1_000)
            assertEquals(asked, readingAvailableAsked)
        }
}
