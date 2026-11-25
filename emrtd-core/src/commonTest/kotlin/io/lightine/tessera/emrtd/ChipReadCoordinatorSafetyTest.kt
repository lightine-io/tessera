package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.fakechip.FakeAttemptLink
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.RecordingObserver
import io.lightine.tessera.emrtd.internal.crypto.chipCrypto
import io.lightine.tessera.emrtd.internal.flow.FlowReporter
import io.lightine.tessera.emrtd.internal.flow.ProgressTracker
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.errors.ChipAccessDenied
import io.lightine.tessera.types.errors.ChipUnexpectedAnswer
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcUnavailable
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The coordinator's safety rules that the branch review (TES-346) found were held only by the
 * platform entry points, or not at all: a fault outside the flow, the restart budget under misuse,
 * the order of a platform's system end and a person's cancel, the progress after a resume, and the
 * two meanings of the attempt number.
 */
class ChipReadCoordinatorSafetyTest {
    private val checkTime = Instant.parse("2026-06-01T12:00:00Z")

    /** An [Error] (not an Exception) from the transport, as a stack overflow on a hostile answer would be. */
    private class Fatal : Error("simulated fatal error")

    /** Passes commands to [base] and throws [Fatal] at command [at] (from 0). */
    private class FatalAt(
        private val base: ApduTransport,
        private val at: Int,
    ) : ApduTransport by base {
        var sent = 0

        override suspend fun transceive(command: ByteArray): ByteArray {
            if (sent++ == at) throw Fatal()
            return base.transceive(command)
        }
    }

    // Answers the plain selections before access, then fails: the first failing command is BAC's
    // GET CHALLENGE, after the access stage began, so a loss here spends a restart.
    private class Failing(
        private val error: NfcError,
    ) : ApduTransport {
        override val maxCommandLength: Int = 261
        override val supportsExtendedLength: Boolean = false

        override suspend fun transceive(command: ByteArray): ByteArray {
            val ins = command[1].toInt() and 0xFF
            val p1 = command[2].toInt() and 0xFF
            if (ins == 0xA4) return if (p1 == 0x04) byteArrayOf(0x90.toByte(), 0x00) else byteArrayOf(0x6A, 0x82.toByte())
            throw ApduTransportException(error)
        }
    }

    private fun coordinator(
        observer: RecordingObserver,
        policy: LinkLossPolicy = LinkLossPolicy.Resume(2),
        key: ChipAccessKey = FakeReadFlowChip.mrzKey(),
    ) = ChipReadCoordinator(ChipReadOptions(key, setOf(1, 2), checkTime = checkTime), policy, observer, ChipReader(NoOpTelemetrySink))

    private fun ChipReadCoordinator.ready() {
        assertTrue(start())
        waiting(0)
        documentFound()
        connecting()
    }

    @Test
    fun aFatalErrorBeforeAccess_typedFailedEndingNeverCancelled() =
        runTest {
            val flow = FakeReadFlowChip.build(chipCrypto(), TestKeys.get(), FlowAccess.BAC)
            val observer = RecordingObserver()
            val c = coordinator(observer)
            c.ready()
            assertEquals(ChipReadAttemptOutcome.ENDED, c.read(FatalAt(FakeAttemptLink(flow.chip), at = 2)))
            val ending = assertIs<ChipReadEnding.Failed>(observer.endings.single())
            assertEquals(ChipAccessDenied.Reason.UNEXPECTED_CHIP_ANSWER, assertIs<ChipAccessDenied>(ending.error).reason)
        }

    @Test
    fun aFatalErrorAfterAccess_partialWithAnUnusableAnswerNeverCancelled() =
        runTest {
            val flow = FakeReadFlowChip.build(chipCrypto(), TestKeys.get(), FlowAccess.BAC)
            val observer = RecordingObserver()
            val c = coordinator(observer)
            c.ready()
            c.read(FatalAt(FakeAttemptLink(flow.chip), at = 12))
            val ending = assertIs<ChipReadEnding.Partial>(observer.endings.single())
            assertEquals(ChipUnexpectedAnswer.Reason.UNUSABLE_FILE_ANSWER, assertIs<ChipUnexpectedAnswer>(ending.stoppedBy).reason)
            assertEquals(ChipAccessProtocol.BAC, ending.read.access.protocol)
        }

    @Test
    fun theRestartBudgetHoldsUnderConcurrentMisuse() =
        runTest {
            val observer = RecordingObserver()
            val c = coordinator(observer, LinkLossPolicy.Resume(1), FakeReadFlowChip.mrzKey())
            c.ready()
            val outcomes =
                withContext(Dispatchers.Default) {
                    (1..16).map { async { c.read(Failing(NfcTagLost)) } }.awaitAll()
                }
            assertTrue(outcomes.count { it == ChipReadAttemptOutcome.LINK_LOST } <= 1, "$outcomes")
            assertEquals(1, observer.updates.count { it.state is ChipReadState.LinkLost })
        }

    @Test
    fun aPersonsCancelKeepsItsMeaningWhateverThePlatformSays() =
        runTest {
            val observer = RecordingObserver()
            val c = coordinator(observer, key = FakeReadFlowChip.mrzKey())
            c.ready()
            var asked = 0
            c.read(Failing(NfcUnavailable(NfcUnavailable.Reason.CANCELLED_BY_USER)), systemEnd = {
                asked++
                NfcUnavailable(NfcUnavailable.Reason.DISABLED)
            })
            assertEquals(ChipReadEnding.Cancelled.By.PERSON, assertIs<ChipReadEnding.Cancelled>(observer.endings.single()).by)
            assertEquals(0, asked, "the platform is asked only about a lost link")
        }

    @Test
    fun aLostLinkWithNfcOffIsTheSystemsEnding() =
        runTest {
            val observer = RecordingObserver()
            val c = coordinator(observer, key = FakeReadFlowChip.mrzKey())
            c.ready()
            c.read(Failing(NfcTagLost), systemEnd = { NfcUnavailable(NfcUnavailable.Reason.DISABLED) })
            val ending = assertIs<ChipReadEnding.EndedBySystem>(observer.endings.single())
            assertEquals(NfcUnavailable(NfcUnavailable.Reason.DISABLED), ending.reason)
        }

    @Test
    fun keptFilesAreNotPlannedAgainAfterAResume() {
        val tracker = ProgressTracker()
        val reporter = FlowReporter(tracker) { _, _ -> }
        reporter.beginAttempt(setOf(1, 2))
        reporter.fileKept(0x0101)
        reporter.planFiles(setOf(0x0101, 0x0102))
        assertFalse(tracker.has(ProgressTracker.fileKey(0x0101, false)), "a kept file is done, not planned again")
        assertTrue(tracker.has(ProgressTracker.fileKey(0x0102, false)))
    }

    /**
     * The two meanings, pinned (review item 7): an update names the attempt it belongs to, so after
     * a lost link the updates already name the next one; the ending counts the attempts that sent
     * commands. A read that ends while it waits for the document reports one less.
     */
    @Test
    fun theEndingCountsAttemptsThatRanTheUpdatesNameTheNextOne() =
        runTest {
            val observer = RecordingObserver()
            val c = coordinator(observer, key = FakeReadFlowChip.mrzKey())
            c.ready()
            assertEquals(ChipReadAttemptOutcome.LINK_LOST, c.read(Failing(NfcTagLost)))
            c.documentFound()
            c.connecting()
            c.connectionFailed()
            assertTrue(c.documentNotReturned())
            assertEquals(2, observer.updates.single { it.state is ChipReadState.Connecting && it.attempt > 1 }.attempt)
            assertEquals(1, observer.endings.single().attempts)
        }
}
