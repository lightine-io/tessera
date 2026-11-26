package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.fakechip.FakeAttemptLink
import io.lightine.tessera.emrtd.fakechip.FakeChip
import io.lightine.tessera.emrtd.fakechip.FakeCommandHandler
import io.lightine.tessera.emrtd.fakechip.FakeLinkLoss
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.fakechip.RecordingObserver
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.pa.withKeys
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.errors.NfcTagLost
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A restart counts against the limit only when the attempt before the loss read no new file bytes
 * (TES-369), and the total time limit ends a read that keeps making progress. A big DG2 gives
 * enough places for a loss to make progress each time.
 */
class ChipReadRestartProgressTest {
    private val checkTime = Instant.parse("2026-06-01T12:00:00Z")

    /** What one attempt does: lose the link after new DG2 bytes ([PROGRESS]), at the access protocol ([NO_PROGRESS]), or read on ([NONE]). */
    private enum class Loss { PROGRESS, NO_PROGRESS, NONE }

    /** Loses the link as [script] says for the current attempt, once per attempt. */
    private class Script(
        private val script: List<Loss>,
    ) : FakeCommandHandler {
        var attempt = 1
        private var firedIn = 0
        private var best = 0

        override fun handle(
            chip: FakeChip,
            command: CommandApdu,
        ): ResponseApdu? {
            if (firedIn == attempt) return null
            val what = script.getOrElse(attempt - 1) { Loss.NONE }
            val hit =
                when (what) {
                    Loss.NONE -> {
                        false
                    }

                    Loss.NO_PROGRESS -> {
                        command.ins == 0x82
                    }

                    Loss.PROGRESS -> {
                        val offset = FakeLinkLoss.offset(command)
                        (FakeLinkLoss.isRead(command) && chip.currentEf == DG2 && offset > best).also { if (it) best = offset }
                    }
                }
            if (!hit) return null
            firedIn = attempt
            throw ApduTransportException(NfcTagLost)
        }
    }

    private inline fun chipFor(
        script: Script,
        block: (FlowChip) -> Unit,
    ) = withCrypto { c ->
        withKeys { keys ->
            block(FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraHandlers = listOf(script), dg2Bytes = 3000))
        }
    }

    private class Clock(
        var millis: Long = 0,
    )

    /** Drives a read as a platform does; every attempt takes [perAttemptMillis] on [clock]. */
    private suspend fun drive(
        flow: FlowChip,
        script: Script,
        policy: LinkLossPolicy,
        totalTimeLimitMillis: Long = ChipReadCoordinator.NO_TIME_LIMIT,
        perAttemptMillis: Long = 0,
        clock: Clock = Clock(),
    ): Pair<ChipReadEnding, RecordingObserver> {
        val observer = RecordingObserver()
        val options = ChipReadOptions(FakeReadFlowChip.mrzKey(), setOf(1, 2), listOf(flow.anchor), checkTime)
        val coordinator =
            ChipReadCoordinator(
                options,
                policy,
                observer,
                ChipReader(NoOpTelemetrySink),
                totalTimeLimitMillis = totalTimeLimitMillis,
                now = { clock.millis },
            )
        assertTrue(coordinator.start())
        var attempt = 1
        while (true) {
            coordinator.documentFound()
            coordinator.connecting()
            script.attempt = attempt
            clock.millis += perAttemptMillis
            if (coordinator.read(FakeAttemptLink(flow.chip)) == ChipReadAttemptOutcome.ENDED) break
            attempt++
            assertTrue(attempt <= 12, "too many attempts")
        }
        return observer.endings.single() to observer
    }

    private fun restartsLeft(observer: RecordingObserver) =
        observer.updates
            .mapNotNull {
                it.state as? ChipReadState.LinkLost
            }.map { it.restartsLeft }

    @Test
    fun fourLossesThatEachReadMoreBytesDoNotEndTheRead() =
        runTest {
            val script = Script(List(4) { Loss.PROGRESS })
            chipFor(script) { flow ->
                val (ending, observer) = drive(flow, script, LinkLossPolicy.Resume(2))
                val complete = assertIs<ChipReadEnding.Complete>(ending, "$ending")
                assertEquals(5, ending.attempts)
                assertTrue(
                    complete.read
                        .dataGroup(2)!!
                        .bytes()
                        .contentEquals(flow.dataGroups.getValue(2)),
                )
                // No loss used a restart: the count stays.
                assertEquals(listOf(2, 2, 2, 2), restartsLeft(observer))
                // The updates never show an attempt past their own maximum.
                assertTrue(observer.updates.all { it.attempt <= it.maxAttempts })
            }
        }

    @Test
    fun lossesWithNoNewBytesEndTheReadAfterMaxRestarts() =
        runTest {
            val script = Script(List(6) { Loss.NO_PROGRESS })
            chipFor(script) { flow ->
                val (ending, observer) = drive(flow, script, LinkLossPolicy.Resume(2))
                assertIs<ChipReadEnding.Failed>(ending, "$ending")
                assertEquals(3, ending.attempts)
                assertEquals(listOf(2, 1), restartsLeft(observer))
            }
        }

    @Test
    fun aLossWithNoNewBytesBetweenTwoThatReadMoreUsesOneRestart() =
        runTest {
            // Progress, none, progress: only the middle one counts.
            val script = Script(listOf(Loss.PROGRESS, Loss.NO_PROGRESS, Loss.PROGRESS))
            chipFor(script) { flow ->
                val (ending, observer) = drive(flow, script, LinkLossPolicy.Resume(2))
                assertIs<ChipReadEnding.Complete>(ending, "$ending")
                assertEquals(4, ending.attempts)
                assertEquals(listOf(2, 2, 1), restartsLeft(observer))
            }
            // With one restart, the counted loss uses it up: the next loss with no new bytes ends the read.
            val again = Script(listOf(Loss.PROGRESS, Loss.NO_PROGRESS, Loss.NO_PROGRESS, Loss.PROGRESS))
            chipFor(again) { flow ->
                val (ending, observer) = drive(flow, again, LinkLossPolicy.Resume(1))
                assertIs<ChipReadEnding.Partial>(ending, "$ending")
                assertEquals(3, ending.attempts)
                assertEquals(listOf(1, 1), restartsLeft(observer))
            }
        }

    @Test
    fun readAgainCountsEveryLossBecauseItKeepsNothing() =
        runTest {
            val script = Script(List(4) { Loss.PROGRESS })
            chipFor(script) { flow ->
                val (ending, _) = drive(flow, script, LinkLossPolicy.ReadAgain(2))
                assertTrue(ending !is ChipReadEnding.Complete, "$ending")
                assertEquals(3, ending.attempts)
            }
        }

    @Test
    fun theTotalTimeLimitEndsAReadThatKeepsMakingProgress() =
        runTest {
            val script = Script(List(8) { Loss.PROGRESS })
            chipFor(script) { flow ->
                // Each attempt takes 30 s on the test clock: the third loss comes at 90 s, past 70 s.
                val (ending, observer) =
                    drive(
                        flow,
                        script,
                        LinkLossPolicy.Resume(2),
                        totalTimeLimitMillis = 70_000,
                        perAttemptMillis = 30_000,
                    )
                assertIs<ChipReadEnding.Partial>(ending, "$ending")
                assertEquals(3, ending.attempts)
                assertEquals(listOf(2, 2), restartsLeft(observer))
            }
        }

    @Test
    fun theTimeLimitBeforeTheLossLeavesTheRestartsAlone() =
        runTest {
            val script = Script(List(2) { Loss.NO_PROGRESS })
            chipFor(script) { flow ->
                val (ending, _) = drive(flow, script, LinkLossPolicy.Resume(2), totalTimeLimitMillis = 70_000, perAttemptMillis = 10_000)
                assertIs<ChipReadEnding.Complete>(ending, "$ending")
                assertEquals(3, ending.attempts)
            }
        }

    private companion object {
        const val DG2 = 0x0102
    }
}
