package io.lightine.tessera.emrtd.sim

import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.ChipReadAttemptOutcome
import io.lightine.tessera.emrtd.ChipReadCoordinator
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadObserver
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadResult
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipReadUpdate
import io.lightine.tessera.emrtd.ChipReader
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.emrtd.fakechip.FakeAttemptLink
import io.lightine.tessera.emrtd.fakechip.FakeCommandHandler
import io.lightine.tessera.emrtd.fakechip.FakeLinkLoss
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.sim.SimDrive.options
import io.lightine.tessera.emrtd.sim.SimDrive.withChips
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.errors.ChipAccessDenied
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The key's lifetime through a whole read (TES-346): a read that owns its key, as the platform entry
 * points do, clears it at its one ending, whatever the ending, and never between the attempts of the
 * read; the low-level [ChipReader.read] leaves the key to its caller.
 *
 * Every update is checked: the key is still usable when the app hears it (only the
 * [ChipReadState.Stopping] that goes out with an app's cancel comes after the ending), and already
 * cleared when the app hears the ending.
 */
class KeyLifetimeScenarioTest {
    private val dg2 = 0x0102

    /** Records, at each callback, whether the key was already cleared. */
    private class KeyWatch(
        private val key: ChipAccessKey,
    ) : ChipReadObserver {
        val updates = mutableListOf<Pair<ChipReadState, Boolean>>()
        val endings = mutableListOf<Pair<ChipReadEnding, Boolean>>()

        override fun onUpdate(update: ChipReadUpdate) {
            updates += update.state to key.isCleared
        }

        override fun onEnding(ending: ChipReadEnding) {
            endings += ending to key.isCleared
        }

        /** The one ending, after checking the key was cleared exactly at it. */
        fun ending(): ChipReadEnding {
            val (ending, cleared) = endings.single()
            assertTrue(cleared, "$ending: the key is cleared before the app hears the ending")
            for ((state, clearedThen) in updates) {
                if (state != ChipReadState.Stopping) assertFalse(clearedThen, "$state: the key was cleared before the ending")
            }
            return ending
        }
    }

    private suspend fun run(
        options: ChipReadOptions,
        policy: LinkLossPolicy = LinkLossPolicy.Resume(2),
        hook: (ChipReadCoordinator) -> Unit = {},
        systemEnd: () -> io.lightine.tessera.types.errors.NfcError? = { null },
        afterLoss: (ChipReadCoordinator) -> Boolean = { true },
        linkFor: (Int) -> ApduTransport,
    ): KeyWatch {
        val watch = KeyWatch(options.accessKey)
        val coordinator =
            ChipReadCoordinator(options, policy, watch, ChipReader(NoOpTelemetrySink), clearKeyAtEnding = true)
        hook(coordinator)
        if (coordinator.start()) {
            coordinator.waiting(0)
            var link = 1
            while (true) {
                coordinator.documentFound()
                coordinator.connecting()
                if (coordinator.read(linkFor(link), systemEnd = systemEnd) == ChipReadAttemptOutcome.ENDED) break
                assertFalse(options.accessKey.isCleared, "between attempts the key stays usable")
                if (!afterLoss(coordinator)) break
                link++
                assertTrue(link <= 6, "more links than the policy allows")
            }
        }
        return watch
    }

    private fun bac(
        c: io.lightine.tessera.emrtd.internal.crypto.ChipCrypto,
        keys: io.lightine.tessera.emrtd.internal.pa.TestKeys,
        vararg handlers: FakeCommandHandler,
        blank: Boolean = false,
    ): FlowChip = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraHandlers = handlers.toList(), blank = blank)

    private fun midDg2(times: Int = 1) =
        FakeLinkLoss(times = times) { chip, cmd ->
            SimDrive.readsAppFile(chip, cmd, dg2) &&
                SimDrive.offset(cmd) > 0
        }

    @Test
    fun keyLifetime_complete_clearedAtTheEnding() =
        runTest {
            withChips { c, keys ->
                val flow = bac(c, keys)
                val ending = run(options(flow)) { FakeAttemptLink(flow.chip) }.ending()
                assertIs<ChipReadEnding.Complete>(ending)
            }
        }

    @Test
    fun keyLifetime_resumeAfterALoss_keyUsableForTheSecondAttemptAndClearedOnceAtTheEnding() =
        runTest {
            withChips { c, keys ->
                for (policy in listOf(LinkLossPolicy.Resume(2), LinkLossPolicy.ReadAgain(2))) {
                    val flow = bac(c, keys, midDg2())
                    val watch = run(options(flow), policy) { FakeAttemptLink(flow.chip) }
                    val ending = assertIs<ChipReadEnding.Complete>(watch.ending(), "$policy")
                    assertEquals(2, ending.attempts, "$policy: the second attempt opened the chip with the same key")
                    assertTrue(watch.updates.any { it.first is ChipReadState.LinkLost && !it.second }, "$policy")
                }
            }
        }

    @Test
    fun keyLifetime_partial_clearedAtTheEnding() =
        runTest {
            withChips { c, keys ->
                val flow = bac(c, keys, midDg2(times = 10))
                assertIs<ChipReadEnding.Partial>(run(options(flow)) { FakeAttemptLink(flow.chip) }.ending())
            }
        }

    @Test
    fun keyLifetime_failed_clearedAtTheEnding() =
        runTest {
            withChips { c, keys ->
                val flow = bac(c, keys, blank = true)
                assertIs<ChipReadEnding.Failed>(run(options(flow)) { FakeAttemptLink(flow.chip) }.ending())
            }
        }

    @Test
    fun keyLifetime_keyNotAccepted_clearedAtTheEnding() =
        runTest {
            withChips { c, keys ->
                val flow = bac(c, keys)
                val ending = run(options(flow, key = FakeReadFlowChip.wrongMrzKey())) { FakeAttemptLink(flow.chip) }.ending()
                assertIs<ChipReadEnding.KeyNotAccepted>(ending)
            }
        }

    @Test
    fun keyLifetime_keyRejectedForItsCheckDigits_clearedBeforeAnyCommand() =
        runTest {
            withChips { c, keys ->
                val flow = bac(c, keys)
                val m = FakeReadFlowChip.mrzInformation
                val wrongDigit = if (m[9] == '0') '1' else '0'
                val key = ChipAccessKey.Mrz(m.substring(0, 9), wrongDigit, m.substring(10, 16), m[16], m.substring(17, 23), m[23])
                assertIs<ChipReadEnding.KeyRejected>(run(options(flow, key = key)) { FakeAttemptLink(flow.chip) }.ending())
                assertTrue(flow.chip.received.isEmpty())
            }
        }

    @Test
    fun keyLifetime_cancelledByTheAppMidAttempt_clearedAndTheGatedAttemptSendsNothingMore() =
        runTest {
            withChips { c, keys ->
                lateinit var coordinator: ChipReadCoordinator
                var cancelledAt = -1
                val cancelMidDg2 =
                    FakeCommandHandler { chip, cmd ->
                        if (cancelledAt < 0 && SimDrive.readsAppFile(chip, cmd, dg2) && SimDrive.offset(cmd) > 0) {
                            assertTrue(coordinator.cancel(ChipReadEnding.Cancelled.By.APP))
                            cancelledAt = chip.received.size
                        }
                        null
                    }
                val flow = bac(c, keys, cancelMidDg2)
                val watch = run(options(flow), hook = { coordinator = it }) { FakeAttemptLink(flow.chip) }
                val ending = assertIs<ChipReadEnding.Cancelled>(watch.ending())
                assertEquals(ChipReadEnding.Cancelled.By.APP, ending.by)
                assertEquals(cancelledAt, flow.chip.received.size, "no command passes the gate after the ending")
            }
        }

    @Test
    fun keyLifetime_cancelledByThePersonOrEndedBySystemWhileWaiting_cleared() =
        runTest {
            withChips { c, keys ->
                val ends =
                    listOf<(ChipReadCoordinator) -> Unit>(
                        { it.cancel(ChipReadEnding.Cancelled.By.PERSON) },
                        { it.endedBySystem(NfcUnavailable(NfcUnavailable.Reason.ENDED_BY_SYSTEM)) },
                        { it.documentNotReturned() },
                    )
                val kinds =
                    ends.map { end ->
                        val flow = bac(c, keys, midDg2())
                        run(options(flow), afterLoss = { co ->
                            end(co)
                            false
                        }) { FakeAttemptLink(flow.chip) }.ending()::class.simpleName
                    }
                assertEquals(listOf("Cancelled", "EndedBySystem", "Partial"), kinds)
            }
        }

    @Test
    fun keyLifetime_endedBySystemAtALoss_cleared() =
        runTest {
            withChips { c, keys ->
                val flow = bac(c, keys, midDg2())
                val watch =
                    run(options(flow), systemEnd = { NfcUnavailable(NfcUnavailable.Reason.DISABLED) }) { FakeAttemptLink(flow.chip) }
                assertIs<ChipReadEnding.EndedBySystem>(watch.ending())
            }
        }

    @Test
    fun keyLifetime_notStarted_cleared() =
        runTest {
            withChips { c, keys ->
                val flow = bac(c, keys)
                val options = options(flow)
                val watch = KeyWatch(options.accessKey)
                val coordinator =
                    ChipReadCoordinator(options, LinkLossPolicy.Resume(2), watch, ChipReader(NoOpTelemetrySink), clearKeyAtEnding = true)
                assertTrue(coordinator.start())
                assertTrue(coordinator.notStarted(NfcUnavailable.Reason.DISABLED))
                assertIs<ChipReadEnding.NotStarted>(watch.ending())
            }
        }

    @Test
    fun keyLifetime_aFaultOutsideTheFlowEndsTypedAndStillClearsTheKey() =
        runTest {
            withChips { c, keys ->
                for (at in listOf(2, 14)) {
                    val flow = bac(c, keys)
                    val watch =
                        run(options(flow)) {
                            val link = FakeAttemptLink(flow.chip)
                            object : ApduTransport by link {
                                var sent = 0

                                override suspend fun transceive(command: ByteArray): ByteArray {
                                    if (sent++ == at) throw Fault()
                                    return link.transceive(command)
                                }
                            }
                        }
                    val ending = watch.ending()
                    assertTrue(ending is ChipReadEnding.Failed || ending is ChipReadEnding.Partial, "fault at $at: $ending")
                }
            }
        }

    @Test
    fun keyLifetime_aKeyUsedForASecondReadEndsWithKeyClearedAndSendsNothing() =
        runTest {
            withChips { c, keys ->
                val first = bac(c, keys)
                val key = FakeReadFlowChip.mrzKey()
                assertIs<ChipReadEnding.Complete>(run(options(first, key = key)) { FakeAttemptLink(first.chip) }.ending())
                val second = bac(c, keys)
                // The key arrives cleared, so the watch's "usable until the ending" does not apply.
                val watch = run(options(second, key = key)) { FakeAttemptLink(second.chip) }
                val ending = assertIs<ChipReadEnding.Failed>(watch.endings.single().first)
                assertEquals(ChipAccessDenied.Reason.KEY_CLEARED, assertIs<ChipAccessDenied>(ending.error).reason)
                assertTrue(second.chip.received.isEmpty(), "a cleared key costs the chip nothing")
            }
        }

    @Test
    fun keyLifetime_theLowLevelReaderAndACoordinatorThatDoesNotOwnTheKeyLeaveItToTheCaller() =
        runTest {
            withChips { c, keys ->
                val flow = bac(c, keys)
                val key = FakeReadFlowChip.mrzKey()
                assertIs<ChipReadResult.Complete>(Sim.read(flow, key))
                assertFalse(key.isCleared, "ChipReader.read: the caller owns the key")

                val again = bac(c, keys)
                val driven =
                    SimDrive.drive(
                        options(again, key = key),
                        LinkLossPolicy.Resume(2),
                        clearKeyAtEnding = false,
                    ) { FakeAttemptLink(again.chip) }
                assertIs<ChipReadEnding.Complete>(driven.ending)
                assertFalse(key.isCleared, "a coordinator with clearKeyAtEnding = false leaves the key")
                key.clear()
            }
        }

    private class Fault : Error("simulated fault outside the read flow")
}
