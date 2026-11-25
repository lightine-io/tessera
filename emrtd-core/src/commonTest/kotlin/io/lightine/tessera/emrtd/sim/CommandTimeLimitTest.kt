package io.lightine.tessera.emrtd.sim

import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.emrtd.ChipCommandTimeLimit
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
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcNoAnswer
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The time limit of each command by what it asks of the chip (TES-346), and the ending of a read
 * whose chip never answered, at the core. The platform tests check the same through the entry
 * points, with virtual time.
 */
class CommandTimeLimitTest {
    private fun apdu(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    private fun kind(vararg bytes: Int) = ChipReadCoordinator.timeLimitFor(apdu(*bytes))

    @Test
    fun theCommandsThatMakeTheChipDoPublicKeyWorkGetTheLongLimit() {
        // EXTERNAL AUTHENTICATE (BAC), plain.
        assertEquals(ChipCommandTimeLimit.LONG, kind(0x00, 0x82, 0x00, 0x00, 0x28, *IntArray(40), 0x28))
        // GENERAL AUTHENTICATE (PACE, chip authentication), plain, chained and under secure messaging.
        assertEquals(ChipCommandTimeLimit.LONG, kind(0x00, 0x86, 0x00, 0x00, 0x02, 0x7C, 0x00, 0x00))
        assertEquals(ChipCommandTimeLimit.LONG, kind(0x10, 0x86, 0x00, 0x00, 0x02, 0x7C, 0x00))
        assertEquals(ChipCommandTimeLimit.LONG, kind(0x0C, 0x86, 0x00, 0x00, 0x03, 0x87, 0x01, 0x01, 0x00))
        // INTERNAL AUTHENTICATE (active authentication) under secure messaging.
        assertEquals(ChipCommandTimeLimit.LONG, kind(0x0C, 0x88, 0x00, 0x00, 0x03, 0x87, 0x01, 0x01, 0x00))
        // MSE:Set KAT (chip authentication with key agreement) under secure messaging.
        assertEquals(ChipCommandTimeLimit.LONG, kind(0x0C, 0x22, 0x41, 0xA6, 0x03, 0x87, 0x01, 0x01, 0x00))
        // PERFORM SECURITY OPERATION.
        assertEquals(ChipCommandTimeLimit.LONG, kind(0x0C, 0x2A, 0x00, 0xBE, 0x03, 0x87, 0x01, 0x01, 0x00))
        // Any extended-length command, whose answer can be up to 64 KB.
        assertEquals(ChipCommandTimeLimit.LONG, kind(0x00, 0xB0, 0x00, 0x00, 0x00, 0x10, 0x00))
        assertEquals(ChipCommandTimeLimit.LONG, kind(0x0C, 0xB0, 0x00, 0x00, 0x00, 0x00, 0x04, 0x97, 0x02, 0x10, 0x00, 0x00, 0x00))
    }

    @Test
    fun everyOtherCommandGetsTheReadLimit() {
        // SELECT and READ BINARY in both instruction forms, plain and under secure messaging.
        assertEquals(ChipCommandTimeLimit.READ, kind(0x00, 0xA4, 0x02, 0x0C, 0x02, 0x01, 0x1E))
        assertEquals(ChipCommandTimeLimit.READ, kind(0x0C, 0xA4, 0x02, 0x0C, 0x03, 0x87, 0x01, 0x01, 0x00))
        assertEquals(ChipCommandTimeLimit.READ, kind(0x00, 0xB0, 0x00, 0x00, 0xDF))
        assertEquals(ChipCommandTimeLimit.READ, kind(0x0C, 0xB0, 0x00, 0xDF, 0x03, 0x97, 0x01, 0xDF, 0x00))
        assertEquals(ChipCommandTimeLimit.READ, kind(0x0C, 0xB1, 0x00, 0x00, 0x03, 0x87, 0x01, 0x01, 0x00))
        // READ BINARY with Le 00 (256 bytes) is still short.
        assertEquals(ChipCommandTimeLimit.READ, kind(0x00, 0xB0, 0x00, 0x00, 0x00))
        // MSE:Set AT for chip authentication only chooses; GET RESPONSE only fetches; GET CHALLENGE.
        assertEquals(ChipCommandTimeLimit.READ, kind(0x0C, 0x22, 0x41, 0xA4, 0x03, 0x87, 0x01, 0x01, 0x00))
        assertEquals(ChipCommandTimeLimit.READ, kind(0x00, 0xC0, 0x00, 0x00, 0x20))
        assertEquals(ChipCommandTimeLimit.READ, kind(0x00, 0x84, 0x00, 0x00, 0x08))
        // Too short to say: the short limit, as for the other ordinary commands.
        assertEquals(ChipCommandTimeLimit.READ, kind(0x00, 0xB0))
    }

    /** A link that logs each command's instruction byte and each limit change, in one list. */
    private class Logged(
        private val base: ApduTransport,
        val log: MutableList<String>,
    ) : ApduTransport by base {
        override suspend fun transceive(command: ByteArray): ByteArray {
            log += "ins=" + (command[1].toInt() and 0xFF).toString(16) + "/" + (command[3].toInt() and 0xFF).toString(16)
            return base.transceive(command)
        }
    }

    private fun coordinator(
        flow: FlowChip,
        recorder: SimRecorder,
        groups: Set<Int> = setOf(1, 2),
        policy: LinkLossPolicy = LinkLossPolicy.Resume(2),
    ) = ChipReadCoordinator(options(flow, groups = groups), policy, recorder, ChipReader(NoOpTelemetrySink), clearKeyAtEnding = true)

    /** Runs one complete read of [flow] and returns its commands and limit changes, in order. */
    private suspend fun loggedRead(
        flow: FlowChip,
        groups: Set<Int>,
    ): List<String> {
        val r = SimRecorder { 0L }
        val co = coordinator(flow, r, groups = groups)
        val log = mutableListOf<String>()
        co.start()
        assertEquals(ChipReadAttemptOutcome.ENDED, co.read(Logged(FakeAttemptLink(flow.chip), log), useTimeLimit = { log += "limit=$it" }))
        assertIs<ChipReadEnding.Complete>(r.ending, "${r.ending}")
        val limits = log.filter { it.startsWith("limit=") }
        assertTrue(limits.zipWithNext().none { (a, b) -> a == b }, "the same limit twice in a row: $limits")
        // Nothing before EXTERNAL AUTHENTICATE asked for a limit: the link opens with the one before access.
        assertTrue(log.takeWhile { it != "limit=LONG" }.none { it.startsWith("limit=") })
        return log
    }

    private fun List<String>.afterLong() = indices.filter { this[it] == "limit=LONG" }.map { this[it + 1] }

    /**
     * BAC and chip authentication in the middle of the reads: the limit is asked for only when the
     * kind changes (4 times in the read), never twice the same in a row, and each long limit comes
     * right before a command that makes the chip do public-key work.
     */
    @Test
    fun theLimitIsAskedForOnlyWhenTheKindChanges_chipAuthentication() =
        runTest {
            withChips { c, keys ->
                val log = loggedRead(FakeReadFlowChip.build(c, keys, FlowAccess.BAC, caInApplication = true), setOf(1, 2, 14))
                assertEquals(listOf("LONG", "READ", "LONG", "READ").map { "limit=$it" }, log.filter { it.startsWith("limit=") }, "$log")
                // EXTERNAL AUTHENTICATE, then chip authentication's GENERAL AUTHENTICATE after its MSE:Set AT (a READ command).
                assertEquals(listOf("ins=82/0", "ins=86/0"), log.afterLong(), "$log")
                assertEquals("ins=22/a4", log[log.lastIndexOf("limit=LONG") - 1])
            }
        }

    /** BAC and active authentication: INTERNAL AUTHENTICATE gets the long limit, the reads around it the read limit. */
    @Test
    fun theLimitIsAskedForOnlyWhenTheKindChanges_activeAuthentication() =
        runTest {
            withChips { c, keys ->
                val log = loggedRead(FakeReadFlowChip.build(c, keys, FlowAccess.BAC, activeAuthentication = true), setOf(1, 2, 15))
                val limits = log.filter { it.startsWith("limit=") }
                assertEquals(listOf("ins=82/0", "ins=88/0"), log.afterLong(), "$log")
                assertTrue(limits.size in 3..4, "$limits")
            }
        }

    /** The hook is not called at all while an attempt stays before access. */
    @Test
    fun noLimitIsAskedForBeforeAccess() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val co = coordinator(flow, SimRecorder { 0L })
                val limits = mutableListOf<ChipCommandTimeLimit>()
                co.start()
                assertEquals(
                    ChipReadAttemptOutcome.NO_ANSWER_YET,
                    co.read(SilentAt(FakeAttemptLink(flow.chip), 0x84, intArrayOf(1), NfcTagLost), useTimeLimit = { limits += it }),
                )
                assertEquals(emptyList(), limits)
            }
        }

    /** A link that loses each command with [ins] while `times[0]` is above 0, with [error]. */
    private class SilentAt(
        private val base: ApduTransport,
        private val ins: Int,
        private val times: IntArray,
        private val error: NfcError,
    ) : ApduTransport by base {
        override suspend fun transceive(command: ByteArray): ByteArray {
            if ((command[1].toInt() and 0xFF) == ins && times[0] > 0) {
                times[0]--
                throw ApduTransportException(error)
            }
            return base.transceive(command)
        }
    }

    /**
     * A silent READ BINARY in the middle of a file after access is still a lost link: a restart is
     * spent, and the next attempt resumes and completes. Only the wait changes (platform tests).
     */
    @Test
    fun aSilentReadAfterAccessSpendsARestartAsBefore() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val r = SimRecorder { 0L }
                val co = coordinator(flow, r)
                val silent = intArrayOf(1)
                co.start()
                val limits = mutableListOf<ChipCommandTimeLimit>()
                assertEquals(
                    ChipReadAttemptOutcome.LINK_LOST,
                    co.read(SilentAt(FakeAttemptLink(flow.chip), 0xB0, silent, NfcTagLost), useTimeLimit = { limits += it }),
                )
                assertEquals(ChipCommandTimeLimit.READ, limits.last(), "the silent read ran under the read limit")
                assertEquals(2, assertIs<ChipReadState.LinkLost>(r.states.last()).restartsLeft)
                assertEquals(ChipReadAttemptOutcome.ENDED, co.read(FakeAttemptLink(flow.chip)))
                assertEquals(2, assertIs<ChipReadEnding.Complete>(r.ending, "${r.ending}").attempts)
            }
        }

    /**
     * The chip never answered: the same error whatever the transport reported (Android reports a
     * silent chip as a lost tag, the iPhone transport as its own time limit), when the platform
     * gives up and when it allows no other try.
     */
    @Test
    fun neverAnswered_endsWithNoAnswer_whateverTheTransportReported() =
        runTest {
            withChips { c, keys ->
                for (error in listOf<NfcError>(NfcTagLost, NfcTimeout(1_500, NfcTimeout.Limit.COMMAND))) {
                    val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                    val r = SimRecorder { 0L }
                    val co = coordinator(flow, r)
                    co.start()
                    repeat(3) {
                        assertEquals(
                            ChipReadAttemptOutcome.NO_ANSWER_YET,
                            co.read(SilentAt(FakeAttemptLink(flow.chip), 0xA4, intArrayOf(1), error)),
                        )
                    }
                    assertTrue(co.documentNotReturned())
                    assertEquals(NfcNoAnswer, assertIs<ChipReadEnding.Failed>(r.ending).error, "$error")

                    val r2 = SimRecorder { 0L }
                    val co2 = coordinator(flow, r2)
                    co2.start()
                    assertEquals(
                        ChipReadAttemptOutcome.NO_ANSWER_YET,
                        co2.read(SilentAt(FakeAttemptLink(flow.chip), 0xA4, intArrayOf(1), error)),
                    )
                    assertEquals(
                        ChipReadAttemptOutcome.ENDED,
                        co2.read(SilentAt(FakeAttemptLink(flow.chip), 0xA4, intArrayOf(1), error), readAgainAllowed = false),
                    )
                    assertEquals(NfcNoAnswer, assertIs<ChipReadEnding.Failed>(r2.ending).error, "$error")
                }
            }
        }

    /**
     * An earlier try got past the commands before access and lost the link in a file; the next
     * tries are silent before access until the platform gives up: the read ends with the loss
     * (here [ChipReadEnding.Partial], since access was granted), not [NfcNoAnswer].
     */
    @Test
    fun accessReachedEarlier_thenSilentBeforeAccess_endsWithTheLossAsBefore() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val r = SimRecorder { 0L }
                val co = coordinator(flow, r)
                co.start()
                assertEquals(
                    ChipReadAttemptOutcome.LINK_LOST,
                    co.read(SilentAt(FakeAttemptLink(flow.chip), 0xB0, intArrayOf(1), NfcTagLost)),
                )
                assertEquals(ChipReadAttemptOutcome.NO_ANSWER_YET, co.read(FakeAttemptLink(flow.chip, dropFirst = 1)))
                assertTrue(co.documentNotReturned())
                assertEquals(NfcTagLost, assertIs<ChipReadEnding.Partial>(r.ending, "${r.ending}").stoppedBy)
            }
        }
}
