package io.lightine.tessera.emrtd.sim

import io.lightine.tessera.emrtd.ChipReadAttemptOutcome
import io.lightine.tessera.emrtd.ChipReadCoordinator
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadResult
import io.lightine.tessera.emrtd.ChipReader
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.emrtd.fakechip.FakeAttemptLink
import io.lightine.tessera.emrtd.fakechip.FakeCommandHandler
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FakeSm
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.fakechip.RecordingObserver
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.errors.ChipSecureMessagingLost
import io.lightine.tessera.types.errors.ChipSecureMessagingLost.Reason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Catalogue group B (secure messaging), card TES-346: faults in the chip's protected answers,
 * driven through [ChipReader] on BAC and PACE fakes. Sources: ICAO TR RF and Protocol Testing Part
 * 4 v3.0 (P4) unit ISO7816_D; Doc 9303 Part 11, 9.8; TR-03110 Part 3, F.6.
 */
class CatalogueGroupBTest {
    /**
     * A chip whose [n]th protected READ BINARY gets [action] (run with the flow, before secure
     * messaging handles the command); returns the flow and the index of that command.
     */
    private class AtProtectedRead(
        private val n: Int = 1,
        private val action: (FlowChip) -> ResponseApdu?,
    ) : FakeCommandHandler {
        var flow: FlowChip? = null
        var firedAt = -1
        private var seen = 0

        override fun handle(
            chip: io.lightine.tessera.emrtd.fakechip.FakeChip,
            command: io.lightine.tessera.emrtd.internal.apdu.CommandApdu,
        ): ResponseApdu? {
            if (command.cla != 0x0C || command.ins != 0xB0 || ++seen != n) return null
            firedAt = chip.received.lastIndex
            return action(flow!!)
        }
    }

    private suspend fun readWith(
        access: FlowAccess,
        trap: AtProtectedRead,
    ): Pair<FlowChip, ChipReadResult> {
        var result: Pair<FlowChip, ChipReadResult>? = null
        Sim.withChips { c, keys ->
            val flow = FakeReadFlowChip.build(c, keys, access, leadingHandlers = listOf(trap))
            trap.flow = flow
            result = flow to Sim.read(flow)
        }
        return result!!
    }

    private suspend fun assertSmLost(
        fault: FakeSm.ResponseFault,
        expected: Set<Reason>,
    ) {
        for (access in listOf(FlowAccess.BAC, FlowAccess.PACE_ONLY)) {
            val trap =
                AtProtectedRead { f ->
                    f.sm!!.nextResponseFault = fault
                    null
                }
            val (flow, result) = readWith(access, trap)
            val partial = assertIs<ChipReadResult.Partial>(result, "$access $fault: $result")
            val lost = assertIs<ChipSecureMessagingLost>(partial.stoppedBy)
            assertTrue(lost.reason in expected, "$access $fault: $lost")
            assertEquals(flow.chip.received.lastIndex, trap.firedAt, "nothing sent after the faulty answer ($access)")
        }
    }

    @Test
    fun b02_answerWithoutDo8E_isMissingChecksumAndNothingFollows() =
        runTest {
            assertSmLost(FakeSm.ResponseFault.DROP_CHECKSUM, setOf(Reason.MISSING_CHECKSUM))
        }

    /**
     * B-03 (P4 ISO7816_D_03, [STD] "failed"). The answer's checksum is valid and DO99 says 9000; only
     * the cryptogram is missing. Secure messaging has nothing to refuse (an authenticated empty
     * answer), so the file reader meets an empty first answer: a typed file error for that file
     * (C-18), and the read goes on. [JDG] The catalogue expected MALFORMED_ANSWER; the SDK reports the
     * file as malformed instead, a fact, no verdict on the chip. The session is not trusted less:
     * every answer was authenticated.
     */
    @Test
    fun b03_answerWithoutDo87WhereDataIsExpected_isAFileErrorOfThatFileAndTheReadGoesOn() =
        runTest {
            for (access in listOf(FlowAccess.BAC, FlowAccess.PACE_ONLY)) {
                val trap =
                    AtProtectedRead { f ->
                        f.sm!!.nextResponseFault = FakeSm.ResponseFault.DROP_CRYPTOGRAM
                        null
                    }
                val (_, result) = readWith(access, trap)
                val read = assertIs<ChipReadResult.Complete>(result, "$access: $result").read
                val error = read.fileErrors.single { it.fileId == 0x011E }
                assertIs<io.lightine.tessera.types.errors.ChipMalformedFile>(error.error, "$error")
            }
        }

    @Test
    fun b04_answerWithoutDo99_isNotTrustedAndNothingFollows() =
        runTest { assertSmLost(FakeSm.ResponseFault.DROP_STATUS, setOf(Reason.MALFORMED_ANSWER, Reason.UNAUTHENTICATED_STATUS)) }

    @Test
    fun b06_cryptogramOfWronglyPaddedData_isWrongPaddingAndNothingFollows() =
        runTest { assertSmLost(FakeSm.ResponseFault.BAD_PADDING, setOf(Reason.WRONG_PADDING)) }

    @Test
    fun b08_chipCounterOffByOne_failsTheChecksumAndNothingFollows() =
        runTest { assertSmLost(FakeSm.ResponseFault.COUNTER_OFF_BY_ONE, setOf(Reason.WRONG_CHECKSUM)) }

    @Test
    fun b16_bare6987Or6988ToAProtectedCommand_endsTheSessionWithNothingAfter() =
        runTest {
            for (sw in listOf(0x6987, 0x6988)) {
                for (access in listOf(FlowAccess.BAC, FlowAccess.PACE_ONLY)) {
                    val trap = AtProtectedRead { ResponseApdu(ByteArray(0), sw) }
                    val (flow, result) = readWith(access, trap)
                    val partial = assertIs<ChipReadResult.Partial>(result, "$access ${sw.toString(16)}")
                    assertEquals(ChipSecureMessagingLost(Reason.CHIP_ENDED_SESSION, sw), partial.stoppedBy)
                    assertEquals(flow.chip.received.lastIndex, trap.firedAt, "nothing after the bare answer")
                }
            }
        }

    /**
     * B-17 and N-04, OPEN DECISION (catalogue "Open decisions"; Doc 9303 Part 11, 9.8.5: a chip that
     * lost power deletes its session keys). A field reset while the platform keeps the link: the
     * chip powers up again with no session and answers the next protected command with a bare
     * status word. The SDK cannot tell this from tampering, so today the rule for any
     * secure-messaging error applies: the read ends Partial (SM lost) on that attempt, and the
     * coordinator never starts again, even with link-loss restarts left. This test pins the CURRENT
     * behaviour; the options are in the TES-346 report.
     */
    @Test
    fun b17_n04_fieldResetMidSessionThenABareStatus_OPEN_DECISION_endsPartialWithNoRestart() =
        runTest {
            Sim.withChips { c, keys ->
                for (access in listOf(FlowAccess.BAC, FlowAccess.PACE_ONLY)) {
                    // At the third protected READ BINARY the chip loses power and comes back on the same link.
                    val trap =
                        AtProtectedRead(3) { f ->
                            f.chip.powerCycle()
                            null
                        }
                    val flow = FakeReadFlowChip.build(c, keys, access, leadingHandlers = listOf(trap))
                    trap.flow = flow
                    val observer = RecordingObserver()
                    val coordinator =
                        ChipReadCoordinator(Sim.options(flow), LinkLossPolicy.Resume(2), observer, ChipReader(NoOpTelemetrySink))
                    assertTrue(coordinator.start())
                    coordinator.waiting(0)
                    coordinator.documentFound()
                    coordinator.connecting()
                    val outcome = coordinator.read(FakeAttemptLink(flow.chip))
                    assertEquals(ChipReadAttemptOutcome.ENDED, outcome, "no new attempt after a bare status ($access)")
                    val ending = assertIs<ChipReadEnding.Partial>(observer.endings.single())
                    val lost = assertIs<ChipSecureMessagingLost>(ending.stoppedBy)
                    assertEquals(Reason.CHIP_ENDED_SESSION, lost.reason, "$lost")
                    assertEquals(1, ending.attempts)
                    assertEquals(flow.chip.received.lastIndex, trap.firedAt, "nothing sent after the bare answer")
                }
            }
        }
}
