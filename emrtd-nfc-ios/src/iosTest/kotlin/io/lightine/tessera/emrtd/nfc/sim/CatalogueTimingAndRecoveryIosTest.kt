package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipCheckKind
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipResumeOutcome
import io.lightine.tessera.emrtd.fakechip.ChipFaultAction
import io.lightine.tessera.emrtd.fakechip.DeviceChipProfile
import io.lightine.tessera.emrtd.fakechip.FakeFlippedByte
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.fakechip.ScriptedChip
import io.lightine.tessera.emrtd.fakechip.SimRecorder
import io.lightine.tessera.emrtd.internal.crypto.chipCrypto
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.types.errors.ChipAccessDenied
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.vocabulary.CheckOutcome
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Groups I (timing), H (a different chip after a loss, flipped bytes after a resume) and J-10 of
 * the scenario catalogue (TES-346), through the iPhone entry point in virtual time.
 */
class CatalogueTimingAndRecoveryIosTest {
    private fun simTest(
        latencyFactor: Long = 1,
        build: (TestKeys) -> FlowChip = { keys -> DeviceChipProfile.build(chipCrypto(), keys, startInApplication = true) },
        block: suspend IosSim.(FlowChip) -> Unit,
    ) = runTest {
        val flow = build(TestKeys.get())
        val sim = IosSim(this, ScriptedChip(flow.chip) { DeviceChipProfile.latency(it) * latencyFactor })
        try {
            sim.block(flow)
        } finally {
            sim.close()
        }
    }

    private fun IosSim.read(
        options: ChipReadOptions,
        settings: CoreNfcReadSettings = CoreNfcReadSettings(),
    ): SimRecorder {
        val (_, recorder) = start(options, settings)
        runUntilEnded(recorder)
        assertNull(recorder.checkPromises())
        return recorder
    }

    @Test
    fun i01_chipThreeTimesSlowerThanRecorded_noLossAndTheSameResult() =
        simTest(latencyFactor = 3) { flow ->
            val r = read(deviceOptions(flow))
            assertDeviceChecksPass(r.ending)
            assertEquals(1, r.ending.attempts)
            val ex = script.exchanges
            val span = ex.last().sentAtMillis + ex.last().reply.delayMillis - ex.first().sentAtMillis
            assertTrue(span in 12_000..17_400, "a read 3 times slower: $span ms")
        }

    @Test
    fun i01_oneCommandTakes4900ms_inTheLimit_noLoss() =
        simTest { flow ->
            script.fault(ChipFaultAction.Delay(4_900), match = Shapes.externalAuthenticate)
            val r = read(deviceOptions(flow))
            assertDeviceChecksPass(r.ending)
            assertEquals(1, r.ending.attempts)
        }

    @Test
    fun i02_oneCommandTakes5100ms_isALossAndTheLateAnswerIsDropped() =
        simTest { flow ->
            script.fault(ChipFaultAction.Delay(5_100), match = Shapes.firstProtectedSelect)
            val r = read(deviceOptions(flow))
            assertDeviceChecksPass(r.ending)
            assertEquals(2, r.ending.attempts)
            assertEquals(1, r.states.count { it is ChipReadState.LinkLost })
        }

    /**
     * I-08: a chip 5 times slower than recorded takes 20 to 29 s, past the platform's limit on one
     * connected tag (DES i33, about 20 s, modelled here). The read hands the tag over at 18 s on
     * its own terms ([CoreNfcReadSettings.tagTimeLimitMillis]) and goes on from where it stopped on
     * a fresh connection, inside the 60 s session: no lost link, no restart, the same attempt.
     */
    @Test
    fun i08_chipFiveTimesSlower_handsTheTagOverBeforeTheConnectedTagLimitAndResumes() =
        simTest(latencyFactor = 5) { flow ->
            connectedLimitMillis = 20_000
            val r = read(deviceOptions(flow))
            val ending = assertIs<ChipReadEnding.Complete>(r.ending)
            assertDeviceChecksPass(ending)
            assertEquals(1, ending.attempts)
            assertEquals(2, script.links)
            assertTrue(r.states.none { it is ChipReadState.LinkLost })
            assertEquals(ChipResumeOutcome.SAME_SECURITY_OBJECT, ending.read.resume)
            assertTrue(r.endings.single().atMillis < CoreNfcChipRead.SESSION_LIMIT_MILLIS)
        }

    /**
     * I-08 with the handover off: the platform's limit cuts the link (code 100 in this model), and
     * the read goes on from where it stopped after the lost link, inside the 60 s session.
     */
    @Test
    fun i08_handoverOff_crossesTheConnectedTagLimitAndResumesAfterTheLoss() =
        simTest(latencyFactor = 5) { flow ->
            connectedLimitMillis = 20_000
            val r = read(deviceOptions(flow), CoreNfcReadSettings(tagTimeLimitMillis = CoreNfcReadSettings.TAG_TIME_LIMIT_OFF))
            val ending = assertIs<ChipReadEnding.Complete>(r.ending)
            assertDeviceChecksPass(ending)
            assertEquals(2, ending.attempts)
            assertEquals(ChipResumeOutcome.SAME_SECURITY_OBJECT, ending.read.resume)
            assertTrue(ending.read.files.any { it.continuedAt != null }, "a file continued: ${ending.read.files}")
            assertTrue(r.endings.single().atMillis < CoreNfcChipRead.SESSION_LIMIT_MILLIS)
        }

    /**
     * I-03, OPEN DECISION (catalogue I-03, NPR#206: a PACE handshake alone can take about 10 s on
     * some chips). Current behaviour: the 5 s command limit turns every slow PACE step into a lost
     * link, so such a chip is never read; the budget ends the read with the time-out. Options: a
     * longer limit for PACE steps only, a setting, or accept.
     */
    @Test
    fun i03_openDecision_paceStepOfSevenSeconds_everyAttemptTimesOut() =
        simTest(build = { keys -> FakeReadFlowChip.build(chipCrypto(), keys, FlowAccess.PACE_ONLY, startInApplication = true) }) { flow ->
            script.latencyMillis = { 0L }
            script.fault(ChipFaultAction.Delay(7_000), times = 99) { it.ins == 0x86 }
            val r = read(ChipReadOptions(FakeReadFlowChip.mrzKey(), setOf(1, 2), listOf(flow.anchor), CHECK_TIME))
            val ending = assertIs<ChipReadEnding.Failed>(r.ending)
            assertIs<NfcTimeout>(ending.error)
            assertEquals(3, ending.attempts)
        }

    @Test
    fun h12_backAfterALossWithAnotherDocumentAndKey_keyNotAcceptedAndNothingFromEarlier() =
        simTest { flow ->
            // Another document, personalized with other MRZ data: our key does not open it.
            val other =
                DeviceChipProfile
                    .build(
                        chipCrypto(),
                        TestKeys.get(),
                        startInApplication = true,
                        keyMrzInformation = "X000000000900101830010100",
                    ).chip
            script.fault(ChipFaultAction.LoseLink(10)) { c ->
                (c.secured && c.ins == 0xB0 && c.chip.currentEf == 0x0102 && c.index > 60).also { if (it) script.nextChip = other }
            }
            val r = read(deviceOptions(flow))
            val ending = assertIs<ChipReadEnding.KeyNotAccepted>(r.ending)
            assertEquals(ChipAccessDenied.Reason.KEY_NOT_MATCHED, ending.error.reason)
            assertEquals(2, ending.attempts)
            // Never tried again with the refused key.
            assertEquals(2, script.exchanges.count { it.command.ins == 0x82 })
        }

    @Test
    fun h13_backAfterALossWithTheSameKeyButAnotherEfSod_readAfresh() =
        simTest { flow ->
            val other = DeviceChipProfile.build(chipCrypto(), TestKeys.get(), startInApplication = true, dg2ImageBytes = 19_000).chip
            script.fault(ChipFaultAction.LoseLink(10)) { c ->
                (c.secured && c.ins == 0xB0 && c.chip.currentEf == 0x0102 && c.index > 60).also { if (it) script.nextChip = other }
            }
            val r = read(deviceOptions(flow))
            val ending = assertIs<ChipReadEnding.Complete>(r.ending)
            assertEquals(ChipResumeOutcome.SECURITY_OBJECT_DIFFERS, ending.read.resume)
            assertTrue(ending.read.files.all { it.attempt == 2 }, "nothing kept from the other document")
            assertEquals(CheckOutcome.Passed, ending.read.outcomeOf(ChipCheckKind.DataGroupHashMatches(2)))
        }

    @Test
    fun h06_backAfterALossAsTheSameChip_keepsEarlierFilesAndContinuesDg2() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(10)) { c -> c.secured && c.ins == 0xB0 && c.chip.currentEf == 0x0102 && c.index > 60 }
            val r = read(deviceOptions(flow))
            val ending = assertIs<ChipReadEnding.Complete>(r.ending)
            assertDeviceChecksPass(ending)
            assertEquals(ChipResumeOutcome.SAME_SECURITY_OBJECT, ending.read.resume)
            val dg2 = ending.read.files.single { it.dataGroup == 2 }
            assertEquals(2, dg2.attempt)
            assertTrue(dg2.continuedAt != null && dg2.continuedAt!! > 0)
            assertTrue(
                ending.read.files
                    .filter { it.dataGroup == 1 }
                    .all { it.attempt == 1 },
                "DG1 kept from the first attempt",
            )
        }

    @Test
    fun h14_flippedByteInTheFirstPartOfAContinuedFile_readAgainInFullAndThenPasses() =
        simTest(build = { keys ->
            DeviceChipProfile.build(chipCrypto(), keys, startInApplication = true, extraHandlers = listOf(FakeFlippedByte(0x0102)))
        }) { flow ->
            script.fault(ChipFaultAction.LoseLink(10)) { c -> c.secured && c.ins == 0xB0 && c.chip.currentEf == 0x0102 && c.index > 60 }
            val r = read(deviceOptions(flow))
            val ending = assertIs<ChipReadEnding.Complete>(r.ending)
            assertDeviceChecksPass(ending)
            val dg2 = ending.read.files.single { it.dataGroup == 2 }
            assertNull(dg2.continuedAt, "read again in full: ${ending.read.files}")
        }

    @Test
    fun j10_platformSaysNoPaceButCardAccessListsIt_theProbeStillRunsAndPaceIsUsed() =
        simTest(build = { keys -> FakeReadFlowChip.build(chipCrypto(), keys, FlowAccess.PACE_ONLY) }) { flow ->
            // A channel that says PACE is not supported.
            val sim = this
            val noPace = ScriptedIso7816(sim, initialSelectedAid = null, supportsPace = false)
            arriveAfterMillis = Long.MAX_VALUE / 4
            val (_, recorder) = start(ChipReadOptions(FakeReadFlowChip.mrzKey(), setOf(1, 2), listOf(flow.anchor), CHECK_TIME))
            port.events.onTagsDetected(listOf(ScriptedTag(sim, noPace)))
            runUntilEnded(recorder)
            val ending = assertIs<ChipReadEnding.Complete>(recorder.ending)
            assertEquals(ChipAccessProtocol.PACE, ending.read.access.protocol)
        }
}
