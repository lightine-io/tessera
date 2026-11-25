package io.lightine.tessera.emrtd.sim

import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.emrtd.ChipReadAttemptOutcome
import io.lightine.tessera.emrtd.ChipReadCoordinator
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipReader
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.emrtd.fakechip.FakeAttemptLink
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.fakechip.SimRecorder
import io.lightine.tessera.emrtd.sim.SimDrive.options
import io.lightine.tessera.emrtd.sim.SimDrive.withChips
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A chip that does not answer before access (TES-346), at the core: such a try uses no restart and
 * no attempt, the read says [ChipReadState.NoAnswerYet] and keeps saying it through the steps of the
 * next tries, and the platform's hook for the general time limit runs right before the first
 * command of BAC or PACE, never earlier.
 */
class NoAnswerBeforeAccessTest {
    /** A link that records each command and when the access-stage hook ran, in one list. */
    private class Logged(
        private val base: ApduTransport,
        val log: MutableList<String>,
    ) : ApduTransport by base {
        override suspend fun transceive(command: ByteArray): ByteArray {
            log += "ins=" + (command[1].toInt() and 0xFF).toString(16)
            return base.transceive(command)
        }
    }

    private fun coordinator(
        flow: FlowChip,
        recorder: SimRecorder,
        policy: LinkLossPolicy = LinkLossPolicy.Resume(2),
    ) = ChipReadCoordinator(options(flow), policy, recorder, ChipReader(NoOpTelemetrySink), clearKeyAtEnding = true)

    @Test
    fun unansweredTriesBeforeAccessUseNoRestartAndRepeatTheAttempt() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val r = SimRecorder { 0L }
                val co = coordinator(flow, r, LinkLossPolicy.End)
                co.start()
                co.waiting(0)
                co.documentFound()
                co.connecting()
                // Five tries in a row lose their first command: more than any policy's restarts.
                repeat(5) {
                    assertEquals(ChipReadAttemptOutcome.NO_ANSWER_YET, co.read(FakeAttemptLink(flow.chip, dropFirst = 1)))
                    // The platform's steps of the next try are not news while the chip has not answered.
                    co.waiting(2_000)
                    co.documentFound()
                    co.connecting()
                    co.connectionFailed()
                }
                assertEquals(ChipReadAttemptOutcome.ENDED, co.read(FakeAttemptLink(flow.chip)))
                val ending = assertIs<ChipReadEnding.Complete>(r.ending, "${r.ending}")
                assertEquals(1, ending.attempts)
                // Each try: NoAnswerYet, then again for the failed connect; nothing else until the
                // chip answers and the protocol is known.
                assertEquals(
                    listOf("Starting", "WaitingForDocument", "DocumentFound", "Connecting", "OpeningAccess") +
                        List(10) { "NoAnswerYet" } + "OpeningAccess",
                    r.states.take(16).map { it::class.simpleName },
                )
                assertEquals(listOf(1, 1, 2, 2, 3, 3, 4, 4, 5, 5), r.states.filterIsInstance<ChipReadState.NoAnswerYet>().map { it.tries })
                assertEquals(ChipAccessProtocol.BAC, (r.states[15] as ChipReadState.OpeningAccess).protocol)
                assertTrue(r.updates.all { it.value.attempt == 1 && it.value.maxAttempts == 1 })
                assertTrue(r.states.none { it is ChipReadState.LinkLost })
            }
        }

    @Test
    fun whenThePlatformGivesUpTheReadEndsFailedWithTheLoss() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val r = SimRecorder { 0L }
                val co = coordinator(flow, r)
                co.start()
                repeat(3) { assertEquals(ChipReadAttemptOutcome.NO_ANSWER_YET, co.read(FakeAttemptLink(flow.chip, dropFirst = 1))) }
                assertTrue(co.documentNotReturned())
                val ending = assertIs<ChipReadEnding.Failed>(r.ending)
                assertEquals(NfcTagLost, ending.error)
                assertEquals(1, ending.attempts)
                assertEquals(0, ending.progress.percent)
                assertEquals(0, flow.bac!!.refused)
            }
        }

    @Test
    fun aPlatformThatCannotTryAgainEndsAtTheFirstUnansweredTry() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val r = SimRecorder { 0L }
                val co = coordinator(flow, r)
                co.start()
                assertEquals(ChipReadAttemptOutcome.ENDED, co.read(FakeAttemptLink(flow.chip, dropFirst = 1), readAgainAllowed = false))
                assertEquals(NfcTagLost, assertIs<ChipReadEnding.Failed>(r.ending).error)
                assertTrue(r.states.none { it is ChipReadState.NoAnswerYet })
            }
        }

    @Test
    fun theAccessStageHookRunsOnceRightBeforeTheFirstBacCommand() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val log = mutableListOf<String>()
                val co = coordinator(flow, SimRecorder { 0L })
                co.start()
                co.read(Logged(FakeAttemptLink(flow.chip), log), accessStage = { log += "hook" })
                assertEquals(1, log.count { it == "hook" })
                assertEquals("ins=84", log[log.indexOf("hook") + 1], "GET CHALLENGE comes right after: $log")
                assertTrue(log.take(log.indexOf("hook")).none { it == "ins=84" || it == "ins=82" })
            }
        }

    @Test
    fun theAccessStageHookRunsOnceRightBeforeTheFirstPaceCommand() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY)
                val log = mutableListOf<String>()
                val co = coordinator(flow, SimRecorder { 0L })
                co.start()
                co.read(Logged(FakeAttemptLink(flow.chip), log), accessStage = { log += "hook" })
                assertEquals(1, log.count { it == "hook" })
                assertEquals("ins=22", log[log.indexOf("hook") + 1], "MSE:Set AT comes right after: $log")
                assertTrue(log.take(log.indexOf("hook")).none { it == "ins=22" || it == "ins=86" })
            }
        }

    @Test
    fun aLossRightAfterTheHookSpendsARestart() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val r = SimRecorder { 0L }
                val co = coordinator(flow, r)
                co.start()
                // The plain commands are answered; the link is lost at BAC's first command.
                val link = FakeAttemptLink(flow.chip)
                val plain =
                    object : ApduTransport by link {
                        override suspend fun transceive(command: ByteArray): ByteArray {
                            if ((command[1].toInt() and 0xFF) == 0x84) throw ApduTransportException(NfcTagLost)
                            return link.transceive(command)
                        }
                    }
                assertEquals(ChipReadAttemptOutcome.LINK_LOST, co.read(plain))
                assertEquals(2, assertIs<ChipReadState.LinkLost>(r.states.last()).restartsLeft)
            }
        }
}
