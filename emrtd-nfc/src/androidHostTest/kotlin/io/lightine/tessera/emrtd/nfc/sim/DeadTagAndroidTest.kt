package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.fakechip.ChipFaultAction
import io.lightine.tessera.emrtd.fakechip.DeviceChipProfile
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.fakechip.ScriptedChip
import io.lightine.tessera.emrtd.fakechip.SimRecorder
import io.lightine.tessera.emrtd.internal.crypto.chipCrypto
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.types.errors.NfcNoAnswer
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private val CHECK_TIME: Instant = Instant.parse("2026-06-01T12:00:00Z")

private fun deviceOptions(flow: FlowChip) = ChipReadOptions(DeviceChipProfile.key(), setOf(1, 2, 14, 15), listOf(flow.anchor), CHECK_TIME)

/** How long the platform takes from noticing a lost tag to handing over the document found again. */
private const val DISCOVERY_MILLIS = 100L

/**
 * A tag the platform deactivated between tries (TES-346), as a device's NFC service log showed: the
 * first command timed out, the reconnect inside the close got no answer to its select, and from
 * then on every connect on that tag failed with "tag already deactivated". Each of those calls put
 * the platform's presence check off by a whole delay, so the platform never noticed the tag was
 * gone and never searched again: a person who moved the document to a good place got nothing.
 *
 * The platform here: each call on a tag's handle postpones its presence check by
 * [NfcReadSettings.presenceCheckDelayMillis]; a check on a deactivated handle fails, and the platform
 * then searches again and, when the document is there, hands over a new Tag after
 * [DISCOVERY_MILLIS].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeadTagAndroidTest {
    private fun simTest(block: suspend AndroidSim.(FlowChip) -> Unit) =
        runTest {
            val flow = DeviceChipProfile.build(chipCrypto(), TestKeys.get())
            AndroidSim(this, ScriptedChip(flow.chip) { DeviceChipProfile.latency(it) }).block(flow)
        }

    /**
     * Runs the read with the platform's presence check and search, until the ending or [limitMillis].
     * [documentThere] says whether a search finds the document. [during] runs after each step.
     */
    private fun AndroidSim.runWithPlatform(
        read: NfcChipRead,
        recorder: SimRecorder,
        documentThere: Boolean,
        limitMillis: Long = 60_000,
        presenceCheckDelayMillis: Long = NfcReadSettings.DEFAULT_PRESENCE_CHECK_DELAY_MILLIS,
        during: () -> Unit = {},
    ) {
        val until = now() + limitMillis
        var discoverAt: Long? = null
        while (recorder.endings.isEmpty() && now() < until) {
            step(AndroidSim.STEP_MILLIS)
            for (handle in handles.toList()) {
                if (handle.dead && handle.lostAt == null && now() - handle.lastCallAt >= presenceCheckDelayMillis) {
                    handle.lostAt = now()
                    if (documentThere) discoverAt = now() + DISCOVERY_MILLIS
                }
            }
            if (discoverAt != null && now() >= discoverAt!!) {
                discoverAt = null
                read.onTagDiscovered(AndroidSim.aTag())
            }
            during()
        }
        step(AndroidSim.STEP_MILLIS)
    }

    /** The largest gap between two updates the app heard, from the first NoAnswerYet or LinkLost to the ending. */
    private fun SimRecorder.largestGapAfter(first: Long): Long {
        val times = updates.map { it.atMillis }.filter { it >= first } + endings.single().atMillis
        return times.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0
    }

    /**
     * (a) The device case. The first command is never answered and the reconnect inside the close
     * deactivates the tag. The read tries the tag once more: that connect fails, and the read lets
     * the tag go, making no further call on it. A second later the platform's presence check finds
     * it gone and searches again; the document, now in a good place, is found as a new Tag, and the
     * read completes on it. Before, the read tried the dead tag every 300 ms (a connect and a close
     * each time), the presence check never ran, no new Tag came, and the read ended with NfcNoAnswer
     * 15 s later.
     */
    @Test
    fun a_reconnectDeactivatesTheTag_oneFailedConnectThenTheTagIsLetGoAndTheNewTagCompletes() =
        simTest { flow ->
            script.fault(ChipFaultAction.Silence, times = 1) { it.indexInLink == 0 }
            reconnectFailures = 1
            val (read, r) = start(deviceOptions(flow))
            step(1_000)
            read.onTagDiscovered(AndroidSim.aTag())
            runWithPlatform(read, r, documentThere = true)
            assertNull(r.checkPromises())

            val ending = assertIs<ChipReadEnding.Complete>(r.ending, "${r.stateNames}")
            assertEquals(1, ending.attempts, "a try before access costs no attempt")
            assertEquals(2, handles.size, "the first tag and the one found again")
            val dead = handles.first()
            assertTrue(dead.dead)
            assertEquals(listOf("connect", "transceive", "close", "connect"), dead.calls.toList())
            assertEquals(1, dead.callsWhenDead, "one failed connect, then nothing more on the dead tag")
            val silentAt = assertNotNull(r.firstAt<ChipReadState.NoAnswerYet>())
            val lostAt = assertNotNull(dead.lostAt)
            // The failed connect 300 ms after the unanswered try, then a whole presence-check delay.
            val expectedLoss = silentAt + NfcChipRead.NO_ANSWER_RETRY_MILLIS + NfcReadSettings.DEFAULT_PRESENCE_CHECK_DELAY_MILLIS
            assertTrue(lostAt in expectedLoss until expectedLoss + AndroidSim.STEP_MILLIS, "lost at $lostAt, expected $expectedLoss")
            assertEquals(
                listOf(1),
                r.states
                    .filterIsInstance<ChipReadState.NoAnswerYet>()
                    .map { it.tries }
                    .distinct(),
            )
            assertTrue(r.largestGapAfter(silentAt) <= NfcChipRead.WAITING_REPORT_MILLIS, "a gap with no news: ${r.updates}")
        }

    /**
     * (b) The same, but the document is not found again: one failed connect on the dead tag and
     * nothing more, NoAnswerYet again every 2 seconds, and the read ends with NfcNoAnswer when the
     * time bound for unanswered tries passes, 15 s after the first.
     */
    @Test
    fun b_reconnectDeactivatesTheTagAndTheDocumentIsNotFoundAgain_endsWithNoAnswerAtTheBound() =
        simTest { flow ->
            script.fault(ChipFaultAction.Silence, times = 1) { it.indexInLink == 0 }
            reconnectFailures = 1
            val (read, r) = start(deviceOptions(flow))
            step(1_000)
            read.onTagDiscovered(AndroidSim.aTag())
            runWithPlatform(read, r, documentThere = false)

            val ending = assertIs<ChipReadEnding.Failed>(r.ending)
            assertEquals(NfcNoAnswer, ending.error)
            val handle = handles.single()
            assertEquals(listOf("connect", "transceive", "close", "connect"), handle.calls.toList())
            assertEquals(1, handle.callsWhenDead)
            val silentAt = assertNotNull(r.firstAt<ChipReadState.NoAnswerYet>())
            assertEquals(silentAt + NfcReadSettings.DEFAULT_WAIT_FOR_RETURN_MILLIS, r.endings.single().atMillis)
            // NoAnswerYet(1), then the same state again every 2 seconds until the ending.
            val noAnswer = r.updates.filter { it.value.state is ChipReadState.NoAnswerYet }
            assertTrue(noAnswer.all { (it.value.state as ChipReadState.NoAnswerYet).tries == 1 })
            assertEquals((0L..7L).map { silentAt + it * NfcChipRead.WAITING_REPORT_MILLIS }, noAnswer.map { it.atMillis })
            assertTrue(r.largestGapAfter(silentAt) <= NfcChipRead.WAITING_REPORT_MILLIS)
        }

    /**
     * (c) The link keeps working and the chip stays silent: the designed tries, on the same tag,
     * one about every 2.2 s; the platform never loses the tag and never searches again, and the
     * read ends with NfcNoAnswer at the bound (8 tries, as in the catalogue's noAnswer_a).
     */
    @Test
    fun c_theConnectKeepsWorkingAndTheChipStaysSilent_theTriesGoOnOnTheSameTag() =
        simTest { flow ->
            script.fault(ChipFaultAction.Silence, times = 99) { it.indexInLink == 0 }
            val (read, r) = start(deviceOptions(flow))
            step(1_000)
            read.onTagDiscovered(AndroidSim.aTag())
            runWithPlatform(read, r, documentThere = true)

            assertEquals(NfcNoAnswer, assertIs<ChipReadEnding.Failed>(r.ending).error)
            val handle = handles.single()
            assertTrue(!handle.dead && handle.lostAt == null)
            assertEquals(8, script.links)
            assertEquals((1..8).toList(), r.states.filterIsInstance<ChipReadState.NoAnswerYet>().map { it.tries })
            assertEquals(List(8) { listOf("connect", "transceive", "close") }.flatten(), handle.calls.toList())
        }

    /**
     * (d) The link is lost in the middle of DG2 and the close deactivates the tag. The read tries
     * the earlier tag once (a second later), lets it go when the connect fails, and resumes on the
     * new Tag the platform finds: the same resume as before, with one restart spent, and no call on
     * the dead tag after that one connect.
     */
    @Test
    fun d_linkLostMidReadAndTheTagDeactivated_oneFailedConnectThenTheNewTagResumes() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(10)) { it.secured && it.ins == 0xB0 && it.chip.currentEf == 0x0102 && it.index > 60 }
            reconnectFailures = 1
            val (read, r) = start(deviceOptions(flow))
            step(1_000)
            read.onTagDiscovered(AndroidSim.aTag())
            runWithPlatform(read, r, documentThere = true)
            assertNull(r.checkPromises())

            val ending = assertIs<ChipReadEnding.Complete>(r.ending, "${r.stateNames}")
            assertEquals(2, ending.attempts)
            assertEquals(2, script.links)
            val lost = r.updates.filter { it.value.state is ChipReadState.LinkLost }
            assertTrue(lost.isNotEmpty() && lost.all { (it.value.state as ChipReadState.LinkLost).restartsLeft == 2 })
            val dead = handles.first()
            assertEquals(listOf("close", "connect"), dead.calls.toList().takeLast(2))
            assertEquals(1, dead.callsWhenDead)
            val lostAt = lost.first().atMillis
            val expectedLoss = lostAt + NfcChipRead.SAME_TAG_RETRY_MILLIS + NfcReadSettings.DEFAULT_PRESENCE_CHECK_DELAY_MILLIS
            val deadAt = assertNotNull(dead.lostAt)
            assertTrue(deadAt in expectedLoss until expectedLoss + AndroidSim.STEP_MILLIS, "lost at $deadAt, expected $expectedLoss")
            assertTrue(r.largestGapAfter(lostAt) <= NfcChipRead.WAITING_REPORT_MILLIS)
        }

    /** (e) A cancel while the read waits for the document to be found again ends it at once, with no call on the dead tag. */
    @Test
    fun e_cancelWhileWaitingForANewTag_endsAtOnceWithNoFurtherCall() =
        simTest { flow ->
            script.fault(ChipFaultAction.Silence, times = 1) { it.indexInLink == 0 }
            reconnectFailures = 1
            val (read, r) = start(deviceOptions(flow))
            step(1_000)
            read.onTagDiscovered(AndroidSim.aTag())
            var cancelledAt: Long? = null
            runWithPlatform(read, r, documentThere = false) {
                val handle = handles.single()
                if (cancelledAt == null && handle.lostAt != null && now() >= handle.lostAt!! + 1_500) {
                    cancelledAt = now()
                    read.cancel()
                }
            }
            assertIs<ChipReadEnding.Cancelled>(r.ending)
            assertEquals(cancelledAt, r.endings.single().atMillis)
            assertEquals(1, handles.single().callsWhenDead)
        }
}
