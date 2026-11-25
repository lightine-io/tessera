package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.fakechip.FakeAttemptLink
import io.lightine.tessera.emrtd.fakechip.FakeLinkLoss
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.fakechip.RecordingObserver
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.pa.withKeys
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.errors.NfcTagLost
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A planned handover (TES-346): the platform stops an attempt itself, between two commands, to get
 * a fresh connection to the same document, and the coordinator goes on from what was read with no
 * restart used and no new state for the app ([ChipReadAttemptOutcome.HANDED_OVER]).
 */
class ChipReadHandoverTest {
    private val checkTime = Instant.parse("2026-06-01T12:00:00Z")
    private val dg2 = 0x0102

    /**
     * A new link that the platform hands over, as a lost link, right before the first READ BINARY
     * of DG2 past offset [atOffset]; [handed] says it did.
     */
    private class HandingOverLink(
        private val flow: FlowChip,
        private val atOffset: Int,
        private val base: ApduTransport = FakeAttemptLink(flow.chip),
    ) : ApduTransport by base {
        var handed = false
            private set

        override suspend fun transceive(command: ByteArray): ByteArray {
            val c = CommandApdu.decode(command)
            if (!handed && c != null && FakeLinkLoss.isRead(c) && flow.chip.currentEf == 0x0102 && FakeLinkLoss.offset(c) >= atOffset) {
                handed = true
                throw ApduTransportException(NfcTagLost)
            }
            return base.transceive(command)
        }
    }

    private fun coordinator(
        flow: FlowChip,
        policy: LinkLossPolicy,
        observer: RecordingObserver,
    ): ChipReadCoordinator =
        ChipReadCoordinator(
            ChipReadOptions(FakeReadFlowChip.mrzKey(), setOf(1, 2), listOf(flow.anchor), checkTime),
            policy,
            observer,
            ChipReader(NoOpTelemetrySink),
        ).also { assertTrue(it.start()) }

    private fun handoverTest(block: suspend (FlowChip) -> Unit) =
        runTest {
            withCrypto { c ->
                withKeys { keys -> block(FakeReadFlowChip.build(c, keys, FlowAccess.BAC, caInApplication = true)) }
            }
        }

    @Test
    fun aHandoverUsesNoRestart_showsNoNewState_andTheNextLinkContinuesTheCutFile() =
        handoverTest { flow ->
            val observer = RecordingObserver()
            val k = coordinator(flow, LinkLossPolicy.Resume(2), observer)
            val first = HandingOverLink(flow, atOffset = 1)
            assertEquals(ChipReadAttemptOutcome.HANDED_OVER, k.read(first, handedOver = { first.handed }))
            val before = observer.updates.size
            val filesBefore =
                observer.updates
                    .last()
                    .progress.filesDone
            // The platform reports the document again: nothing new for the app until the read is past where it was.
            k.waiting(500)
            k.documentFound()
            k.connecting()
            assertEquals(ChipReadAttemptOutcome.ENDED, k.read(FakeAttemptLink(flow.chip)))

            val ending = assertIs<ChipReadEnding.Complete>(observer.endings.single())
            assertEquals(1, ending.attempts, "a handover is no new attempt")
            assertEquals(ChipResumeOutcome.SAME_SECURITY_OBJECT, ending.read.resume)
            assertNotNull(ending.read.dataGroup(2)!!.continuedAt, "DG2 continued from its offset")
            val after = observer.updates.drop(before)
            val firstAfter = after.first().state
            assertIs<ChipReadState.ReadingFile>(firstAfter, "$after")
            assertEquals(dg2, firstAfter.file.fileId)
            assertTrue(after.none { it.state is ChipReadState.OpeningAccess || it.state is ChipReadState.LinkLost }, "$after")
            assertTrue(after.none { it.state is ChipReadState.AuthenticatingChip }, "chip authentication was shown before: $after")
            assertTrue(after.all { it.progress.filesDone >= filesBefore }, "$after")
            assertTrue(after.all { it.attempt == 1 && it.maxAttempts == 3 })
        }

    @Test
    fun aRealLossAfterAHandoverStillHasTheWholeBudget() =
        handoverTest { flow ->
            val observer = RecordingObserver()
            val k = coordinator(flow, LinkLossPolicy.Resume(2), observer)
            val first = HandingOverLink(flow, atOffset = 1)
            assertEquals(ChipReadAttemptOutcome.HANDED_OVER, k.read(first, handedOver = { first.handed }))
            // A real loss on the new link, further into DG2.
            val second = HandingOverLink(flow, atOffset = 600)
            assertEquals(ChipReadAttemptOutcome.LINK_LOST, k.read(second))
            assertEquals(
                2,
                observer.updates
                    .map { it.state }
                    .filterIsInstance<ChipReadState.LinkLost>()
                    .single()
                    .restartsLeft,
            )
            assertEquals(ChipReadAttemptOutcome.ENDED, k.read(FakeAttemptLink(flow.chip)))
            val ending = assertIs<ChipReadEnding.Complete>(observer.endings.single())
            assertEquals(2, ending.attempts)
        }

    @Test
    fun theDocumentNotBackAfterAHandover_isALoss_aRestartIsUsedAndTheReadResumes() =
        handoverTest { flow ->
            val observer = RecordingObserver()
            val k = coordinator(flow, LinkLossPolicy.Resume(2), observer)
            val first = HandingOverLink(flow, atOffset = 1)
            assertEquals(ChipReadAttemptOutcome.HANDED_OVER, k.read(first, handedOver = { first.handed }))
            assertTrue(k.handoverFailed())
            assertIs<ChipReadState.LinkLost>(observer.updates.last().state)
            k.documentFound()
            assertIs<ChipReadState.DocumentFound>(observer.updates.last().state, "after a loss the states come again")
            assertEquals(ChipReadAttemptOutcome.ENDED, k.read(FakeAttemptLink(flow.chip)))
            val ending = assertIs<ChipReadEnding.Complete>(observer.endings.single())
            assertEquals(2, ending.attempts)
            assertNotNull(ending.read.dataGroup(2)!!.continuedAt)
        }

    @Test
    fun theDocumentNotBackAfterAHandover_noRestartLeft_endsPartialWithTheLoss() =
        handoverTest { flow ->
            val observer = RecordingObserver()
            val k = coordinator(flow, LinkLossPolicy.Resume(1), observer)
            assertEquals(ChipReadAttemptOutcome.LINK_LOST, k.read(HandingOverLink(flow, atOffset = 1)))
            val second = HandingOverLink(flow, atOffset = 600)
            assertEquals(ChipReadAttemptOutcome.HANDED_OVER, k.read(second, handedOver = { second.handed }))
            assertFalse(k.handoverFailed())
            val ending = assertIs<ChipReadEnding.Partial>(observer.endings.single())
            assertEquals(NfcTagLost, ending.stoppedBy)
        }

    @Test
    fun underAnotherPolicyAHandoverIsAnOrdinaryLoss() =
        handoverTest { flow ->
            for (policy in listOf(LinkLossPolicy.ReadAgain(1), LinkLossPolicy.End)) {
                val observer = RecordingObserver()
                val k = coordinator(flow, policy, observer)
                val first = HandingOverLink(flow, atOffset = 1)
                val outcome = k.read(first, handedOver = { first.handed })
                assertTrue(outcome != ChipReadAttemptOutcome.HANDED_OVER, "$policy: $outcome")
            }
        }

    @Test
    fun aHookThatThrowsCountsAsNoHandover() =
        handoverTest { flow ->
            val observer = RecordingObserver()
            val k = coordinator(flow, LinkLossPolicy.Resume(2), observer)
            assertEquals(ChipReadAttemptOutcome.LINK_LOST, k.read(HandingOverLink(flow, atOffset = 1), handedOver = { error("boom") }))
        }
}
