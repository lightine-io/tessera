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
import io.lightine.tessera.types.errors.ChipAccessDenied
import io.lightine.tessera.types.errors.NfcNoAnswer
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
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
 * command that uses the key (BAC's EXTERNAL AUTHENTICATE, PACE's first GENERAL AUTHENTICATE), never
 * earlier: BAC's GET CHALLENGE and PACE's MSE:Set AT are still before access.
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

    /** A link that loses each command with [ins] (no answer) while `times[0]` is above 0, counted over all links. */
    private class SilentAt(
        private val base: ApduTransport,
        private val ins: Int,
        private val times: IntArray,
    ) : ApduTransport by base {
        override suspend fun transceive(command: ByteArray): ByteArray {
            if ((command[1].toInt() and 0xFF) == ins && times[0] > 0) {
                times[0]--
                throw ApduTransportException(NfcTagLost)
            }
            return base.transceive(command)
        }
    }

    private fun SimRecorder.names() =
        states.map { s -> if (s is ChipReadState.OpeningAccess) "OpeningAccess(${s.protocol})" else s::class.simpleName }

    private fun SimRecorder.assertProgressNeverGoesBack() {
        val fractions = updates.map { it.value.progress.fraction }
        assertEquals(fractions.sorted(), fractions, "progress went back")
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
    fun whenThePlatformGivesUpTheReadEndsFailedWithNoAnswer() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val r = SimRecorder { 0L }
                val co = coordinator(flow, r)
                co.start()
                repeat(3) { assertEquals(ChipReadAttemptOutcome.NO_ANSWER_YET, co.read(FakeAttemptLink(flow.chip, dropFirst = 1))) }
                assertTrue(co.documentNotReturned())
                val ending = assertIs<ChipReadEnding.Failed>(r.ending)
                assertEquals(NfcNoAnswer, ending.error)
                assertEquals("no-answer", ending.error.code)
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
                assertEquals(NfcNoAnswer, assertIs<ChipReadEnding.Failed>(r.ending).error)
                assertTrue(r.states.none { it is ChipReadState.NoAnswerYet })
            }
        }

    @Test
    fun theAccessStageHookRunsOnceRightBeforeExternalAuthenticate() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val log = mutableListOf<String>()
                val co = coordinator(flow, SimRecorder { 0L })
                co.start()
                co.read(Logged(FakeAttemptLink(flow.chip), log), useTimeLimit = { log += "hook=$it" })
                val first = log.indexOfFirst { it.startsWith("hook") }
                assertEquals("hook=LONG", log[first])
                assertEquals("ins=82", log[first + 1], "EXTERNAL AUTHENTICATE comes right after: $log")
                assertEquals("ins=84", log[first - 1], "GET CHALLENGE comes before, under the short limit: $log")
                assertTrue(log.take(first).none { it == "ins=82" })
            }
        }

    @Test
    fun theAccessStageHookRunsOnceRightBeforeTheFirstGeneralAuthenticate() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY)
                val log = mutableListOf<String>()
                val co = coordinator(flow, SimRecorder { 0L })
                co.start()
                co.read(Logged(FakeAttemptLink(flow.chip), log), useTimeLimit = { log += "hook=$it" })
                val first = log.indexOfFirst { it.startsWith("hook") }
                assertEquals("hook=LONG", log[first])
                assertEquals("ins=86", log[first + 1], "the first GENERAL AUTHENTICATE comes right after: $log")
                assertEquals("ins=22", log[first - 1], "MSE:Set AT comes before, under the short limit: $log")
                assertTrue(log.take(first).none { it == "ins=86" })
            }
        }

    @Test
    fun aLossAtExternalAuthenticateSpendsARestart() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val r = SimRecorder { 0L }
                val co = coordinator(flow, r)
                co.start()
                // The commands before access are answered; the link is lost at EXTERNAL AUTHENTICATE.
                val link = FakeAttemptLink(flow.chip)
                val plain =
                    object : ApduTransport by link {
                        override suspend fun transceive(command: ByteArray): ByteArray {
                            if ((command[1].toInt() and 0xFF) == 0x82) throw ApduTransportException(NfcTagLost)
                            return link.transceive(command)
                        }
                    }
                assertEquals(ChipReadAttemptOutcome.LINK_LOST, co.read(plain))
                assertEquals(2, assertIs<ChipReadState.LinkLost>(r.states.last()).restartsLeft)
            }
        }

    /**
     * The device case (TES-346): the chip answers the plain commands, then has no answer for GET
     * CHALLENGE. That costs nothing (GET CHALLENGE only asks for a random number): the read says
     * NoAnswerYet, the next try of the same attempt completes, and both restarts are left.
     */
    @Test
    fun aSilentGetChallengeAfterAnsweredPlainCommands_isNoAnswerYet_andTheNextTryCompletes() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val r = SimRecorder { 0L }
                val co = coordinator(flow, r)
                val silent = intArrayOf(1)
                val hooks = mutableListOf<Int>()
                co.start()
                co.documentFound()
                co.connecting()
                var link = 0
                assertEquals(
                    ChipReadAttemptOutcome.NO_ANSWER_YET,
                    co.read(SilentAt(FakeAttemptLink(flow.chip), 0x84, silent), useTimeLimit = { hooks += link }),
                )
                assertEquals(1, assertIs<ChipReadState.NoAnswerYet>(r.states.last()).tries)
                co.documentFound()
                co.connecting()
                link = 1
                assertEquals(
                    ChipReadAttemptOutcome.ENDED,
                    co.read(SilentAt(FakeAttemptLink(flow.chip), 0x84, silent), useTimeLimit = { hooks += link }),
                )
                val ending = assertIs<ChipReadEnding.Complete>(r.ending, "${r.ending}")
                assertEquals(1, ending.attempts)
                assertEquals(listOf(1), hooks.distinct(), "a limit after access was set only on the try that went past GET CHALLENGE")
                assertEquals(0, flow.bac!!.refused)
                assertTrue(r.states.none { it is ChipReadState.LinkLost })
                assertTrue(r.updates.all { it.value.attempt == 1 })
                // The protocol shows once per try that answered the plain commands; while the read says
                // NoAnswerYet, the next try's found, connecting and opening steps are held back until
                // access is reached.
                assertEquals(
                    listOf(
                        "Starting",
                        "DocumentFound",
                        "Connecting",
                        "OpeningAccess(null)",
                        "OpeningAccess(BAC)",
                        "NoAnswerYet",
                        "OpeningAccess(BAC)",
                        "ReadingFile",
                    ),
                    r.names().take(8),
                )
                r.assertProgressNeverGoesBack()
            }
        }

    /** Tries keep counting when one try is silent at its first command and the next at GET CHALLENGE. */
    @Test
    fun triesKeepCountingAcrossSilencesAtDifferentCommandsBeforeAccess() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val r = SimRecorder { 0L }
                val co = coordinator(flow, r)
                val silent = intArrayOf(2)
                co.start()
                assertEquals(ChipReadAttemptOutcome.NO_ANSWER_YET, co.read(FakeAttemptLink(flow.chip, dropFirst = 1)))
                assertEquals(ChipReadAttemptOutcome.NO_ANSWER_YET, co.read(SilentAt(FakeAttemptLink(flow.chip), 0x84, silent)))
                assertEquals(ChipReadAttemptOutcome.NO_ANSWER_YET, co.read(SilentAt(FakeAttemptLink(flow.chip), 0x84, silent)))
                assertEquals(listOf(1, 2, 3), r.states.filterIsInstance<ChipReadState.NoAnswerYet>().map { it.tries })
                // No OpeningAccess between the NoAnswerYet states: nothing flickers while the chip is silent.
                assertEquals(listOf("Starting", "OpeningAccess(null)", "NoAnswerYet", "NoAnswerYet", "NoAnswerYet"), r.names())
                assertEquals(ChipReadAttemptOutcome.ENDED, co.read(FakeAttemptLink(flow.chip)))
                assertEquals(1, assertIs<ChipReadEnding.Complete>(r.ending).attempts)
                r.assertProgressNeverGoesBack()
            }
        }

    /** GET CHALLENGE silent on every try: the read ends as a document that never answered, no restart used. */
    @Test
    fun getChallengeSilentUntilThePlatformGivesUp_endsFailedWithNoAnswer() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val r = SimRecorder { 0L }
                val co = coordinator(flow, r)
                val silent = intArrayOf(99)
                co.start()
                repeat(
                    4,
                ) { assertEquals(ChipReadAttemptOutcome.NO_ANSWER_YET, co.read(SilentAt(FakeAttemptLink(flow.chip), 0x84, silent))) }
                assertTrue(co.documentNotReturned())
                val ending = assertIs<ChipReadEnding.Failed>(r.ending)
                // The chip answered the plain commands of each try, never GET CHALLENGE: no try got
                // past the commands before access, so the read ends the same as a chip that never
                // answered at all.
                assertEquals(NfcNoAnswer, ending.error)
                assertEquals(1, ending.attempts)
                assertEquals(listOf(1, 2, 3, 4), r.states.filterIsInstance<ChipReadState.NoAnswerYet>().map { it.tries })
                assertTrue(r.states.none { it is ChipReadState.LinkLost })
                assertEquals(0, flow.bac!!.refused)
                assertEquals(0, flow.chip.received.count { it[1] == 0x82.toByte() }, "EXTERNAL AUTHENTICATE was never sent")
            }
        }

    /** A GET CHALLENGE answered with an error status word is an answer, not silence: as before. */
    @Test
    fun getChallengeAnsweredWithAnErrorStatusWord_isARefusalAsBefore() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraHandlers = listOf(Sim.answer(0x00, 0x84, 0x6982)))
                val r = SimRecorder { 0L }
                val co = coordinator(flow, r)
                co.start()
                assertEquals(ChipReadAttemptOutcome.ENDED, co.read(FakeAttemptLink(flow.chip)))
                val error = assertIs<ChipAccessDenied>(assertIs<ChipReadEnding.KeyNotAccepted>(r.ending, "${r.ending}").error)
                assertEquals(ChipAccessProtocol.BAC, error.protocol)
                assertEquals(0x6982, error.statusWord)
                assertEquals(0, flow.chip.received.count { it[1] == 0x82.toByte() })
                assertTrue(r.states.none { it is ChipReadState.NoAnswerYet || it is ChipReadState.LinkLost })
            }
        }

    /** PACE: a silent MSE:Set AT costs nothing, like a silent plain command; the next try completes. */
    @Test
    fun aSilentMseSetAt_isNoAnswerYet_andTheNextTryCompletes() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY)
                val r = SimRecorder { 0L }
                val co = coordinator(flow, r)
                val silent = intArrayOf(1)
                co.start()
                assertEquals(ChipReadAttemptOutcome.NO_ANSWER_YET, co.read(SilentAt(FakeAttemptLink(flow.chip), 0x22, silent)))
                assertEquals(ChipReadAttemptOutcome.ENDED, co.read(SilentAt(FakeAttemptLink(flow.chip), 0x22, silent)))
                val ending = assertIs<ChipReadEnding.Complete>(r.ending, "${r.ending}")
                assertEquals(1, ending.attempts)
                assertEquals(ChipAccessProtocol.PACE, ending.read.access.protocol)
                assertEquals(listOf(1), r.states.filterIsInstance<ChipReadState.NoAnswerYet>().map { it.tries })
                assertTrue(r.states.none { it is ChipReadState.LinkLost })
                r.assertProgressNeverGoesBack()
            }
        }

    /** PACE: a silent first GENERAL AUTHENTICATE is past the boundary: a restart is spent, as before. */
    @Test
    fun aSilentFirstGeneralAuthenticate_spendsARestart() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY)
                val r = SimRecorder { 0L }
                val co = coordinator(flow, r)
                co.start()
                assertEquals(ChipReadAttemptOutcome.LINK_LOST, co.read(SilentAt(FakeAttemptLink(flow.chip), 0x86, intArrayOf(1))))
                assertEquals(2, assertIs<ChipReadState.LinkLost>(r.states.last()).restartsLeft)
                assertTrue(r.states.none { it is ChipReadState.NoAnswerYet })
            }
        }
}
