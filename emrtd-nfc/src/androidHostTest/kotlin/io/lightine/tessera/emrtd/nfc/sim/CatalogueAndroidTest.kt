package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipCheckKind
import io.lightine.tessera.emrtd.ChipFileContext
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipResumeOutcome
import io.lightine.tessera.emrtd.ChipSignedFile
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.emrtd.fakechip.ChipCommand
import io.lightine.tessera.emrtd.fakechip.ChipFaultAction
import io.lightine.tessera.emrtd.fakechip.ChipReply
import io.lightine.tessera.emrtd.fakechip.DeviceChipProfile
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.fakechip.ScriptedChip
import io.lightine.tessera.emrtd.fakechip.SimRecorder
import io.lightine.tessera.emrtd.internal.crypto.chipCrypto
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.types.errors.ChipAccessDenied
import io.lightine.tessera.types.errors.ChipSecureMessagingLost
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.vocabulary.CheckOutcome
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private val CHECK_TIME: Instant = Instant.parse("2026-06-01T12:00:00Z")

private fun deviceOptions(flow: FlowChip) = ChipReadOptions(DeviceChipProfile.key(), setOf(1, 2, 14, 15), listOf(flow.anchor), CHECK_TIME)

private object Shapes {
    val externalAuthenticate: (ChipCommand) -> Boolean = { it.ins == 0x82 }
    val plainSelectOfTheApplication: (ChipCommand) -> Boolean = { !it.secured && it.ins == 0xA4 && it.apdu?.p1 == 0x04 }
    val firstProtectedSelect: (ChipCommand) -> Boolean = { it.secured && it.ins == 0xA4 }
    val mseSetKat: (ChipCommand) -> Boolean = { it.ins == 0x22 && it.apdu?.p2 == 0xA6 }
    val midDg2: (ChipCommand) -> Boolean = { it.secured && it.ins == 0xB0 && it.chip.currentEf == 0x0102 && it.index > 60 }
    const val LOSS_AFTER_MILLIS = 640L
}

private fun assertDeviceChecksPass(ending: ChipReadEnding) {
    val read = assertIs<ChipReadEnding.Complete>(ending, "$ending").read
    assertEquals(ChipAccessProtocol.BAC, read.access.protocol)
    assertTrue(read.checks.none { it.outcome is CheckOutcome.Failed }, "${read.checks}")
    assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.SignatureVerifies(ChipSignedFile.SECURITY_OBJECT)))
    assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.ChipAuthentication(ChipFileContext.EMRTD_APPLICATION)))
    for (n in listOf(1, 2, 14, 15)) assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.DataGroupHashMatches(n)), "DG$n")
}

/**
 * The scenario catalogue (TES-346) through the Android entry point [NfcChipRead]: a fake platform
 * whose every connection is a new link to the scripted chip, in virtual time. Group K replays on
 * the device-shaped chip, the Android platform rows of group G, and the refusals after which no
 * new attempt may start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogueAndroidTest {
    private fun simTest(
        latencyFactor: Long = 1,
        build: (TestKeys) -> FlowChip = { keys -> DeviceChipProfile.build(chipCrypto(), keys) },
        block: suspend AndroidSim.(FlowChip) -> Unit,
    ) = runTest {
        val flow = build(TestKeys.get())
        AndroidSim(this, ScriptedChip(flow.chip) { DeviceChipProfile.latency(it) * latencyFactor }).block(flow)
    }

    private fun AndroidSim.read(
        options: ChipReadOptions,
        settings: NfcReadSettings = NfcReadSettings(),
    ): SimRecorder {
        val (read, recorder) = start(options, settings)
        step(1_000)
        read.onTagDiscovered(AndroidSim.aTag())
        runUntilEnded(recorder)
        assertNull(recorder.checkPromises())
        assertTrue(channels.all { it.closeCalls >= 1 || !it.isConnected }, "every link closed")
        return recorder
    }

    private fun AndroidSim.commandSpan(link: Int): Long {
        val ex = script.exchangesOf(link)
        return ex.last().sentAtMillis + ex.last().reply.delayMillis - ex.first().sentAtMillis
    }

    // --- Group K ----------------------------------------------------------------------------------

    @Test
    fun K_01_completeRead_masterFileCurrent_121CommandsInTheRecordedTime() =
        simTest { flow ->
            val r = read(deviceOptions(flow))
            assertDeviceChecksPass(r.ending)
            assertEquals(1, r.ending.attempts)
            // The recorded 122-123 are iPhone counts; Android starts in the master file and needs
            // neither the SELECT that answers 6982 nor SELECT MF: 121 (see the iOS group K tests).
            assertEquals(121, script.exchanges.size)
            assertEquals(listOf("Starting", "WaitingForDocument", "DocumentFound", "Connecting", "OpeningAccess", "ReadingFile"), r.stateNames.take(6))
            assertTrue("AuthenticatingChip" in r.stateNames)
            assertEquals("CheckingFiles", r.stateNames.last())
            assertTrue(commandSpan(1) in 4_000..5_800, "span ${commandSpan(1)}")
            assertEquals(listOf(NfcReadSettings.DEFAULT_COMMAND_TIMEOUT_MILLIS.toInt()), channels.single().timeouts, "the 5 s limit set once")
        }

    @Test
    fun K_02_cardAccessProbe_threePlainCommandsFromTheMasterFile() =
        simTest { flow ->
            read(deviceOptions(flow))
            val probe = script.exchanges.takeWhile { it.command.ins != 0x84 }.map { (it.reply as ChipReply.Answer).sw }
            assertEquals(listOf(0x9000, 0x6986, 0x9000), probe)
        }

    private fun AndroidSim.recovered(r: SimRecorder) {
        assertDeviceChecksPass(r.ending)
        assertEquals(2, r.ending.attempts)
        assertEquals(2, script.links)
        val lost = r.updates.single { it.value.state is ChipReadState.LinkLost }
        assertEquals(2, (lost.value.state as ChipReadState.LinkLost).restartsLeft)
    }

    @Test
    fun K_03_externalAuthenticateUnansweredInFirstSecond_resumesAndCompletes() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(Shapes.LOSS_AFTER_MILLIS), match = Shapes.externalAuthenticate)
            recovered(read(deviceOptions(flow)))
            assertEquals(2, script.exchanges.count { it.command.ins == 0x82 })
        }

    @Test
    fun K_03_externalAuthenticateUnansweredInFirstSecond_endPolicyFailsCleanly() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(Shapes.LOSS_AFTER_MILLIS), match = Shapes.externalAuthenticate)
            val r = read(deviceOptions(flow), NfcReadSettings(linkLoss = LinkLossPolicy.End))
            assertEquals(NfcTagLost, assertIs<ChipReadEnding.Failed>(r.ending).error)
            assertEquals(1, script.links)
        }

    @Test
    fun K_04_plainSelectUnansweredInFirstSecond_resumesAndCompletes() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(Shapes.LOSS_AFTER_MILLIS), match = Shapes.plainSelectOfTheApplication)
            recovered(read(deviceOptions(flow)))
        }

    @Test
    fun K_04_plainSelectUnansweredInFirstSecond_endPolicyFailsCleanly() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(Shapes.LOSS_AFTER_MILLIS), match = Shapes.plainSelectOfTheApplication)
            val r = read(deviceOptions(flow), NfcReadSettings(linkLoss = LinkLossPolicy.End))
            assertEquals(NfcTagLost, assertIs<ChipReadEnding.Failed>(r.ending).error)
        }

    @Test
    fun K_05_firstProtectedSelectUnansweredInFirstSecond_resumesAndCompletes() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(Shapes.LOSS_AFTER_MILLIS), match = Shapes.firstProtectedSelect)
            recovered(read(deviceOptions(flow)))
        }

    @Test
    fun K_05_firstProtectedSelectUnansweredInFirstSecond_endPolicyIsPartialAfterAccess() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(Shapes.LOSS_AFTER_MILLIS), match = Shapes.firstProtectedSelect)
            val r = read(deviceOptions(flow), NfcReadSettings(linkLoss = LinkLossPolicy.End))
            assertEquals(NfcTagLost, assertIs<ChipReadEnding.Partial>(r.ending).stoppedBy)
        }

    @Test
    fun K_06_mseSetKatAnsweredOnlyAfter18s_thePlatformLimitOfFiveSecondsFiresAndTheReadRecovers() =
        simTest { flow ->
            script.fault(ChipFaultAction.Delay(18_300), match = Shapes.mseSetKat)
            val r = read(deviceOptions(flow))
            recovered(r)
            val katSent = script.exchanges.first { Shapes.mseSetKat(it.command) }.sentAtMillis
            val lost = r.updates.single { it.value.state is ChipReadState.LinkLost }
            assertEquals(katSent + 5_000, lost.atMillis, "the limit the transport set: 5 s, not 18.3 s")
            assertEquals(2, script.exchanges.count { Shapes.mseSetKat(it.command) })
        }

    @Test
    fun K_06_mseSetKatNeverAnswered_thePlatformLimitFiresAndTheReadRecovers() =
        simTest { flow ->
            script.fault(ChipFaultAction.Silence, match = Shapes.mseSetKat)
            recovered(read(deviceOptions(flow)))
        }

    @Test
    fun K_09_everyNewLinkLosesItsFirstCommand_budgetUsedUpEndsFailedWithTheLoss() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(Shapes.LOSS_AFTER_MILLIS), times = 99) { it.indexInLink == 0 }
            val r = read(deviceOptions(flow))
            val ending = assertIs<ChipReadEnding.Failed>(r.ending)
            assertEquals(NfcTagLost, ending.error)
            assertEquals(3, ending.attempts)
            assertEquals(3, script.links)
            assertEquals(listOf(2, 1), r.states.filterIsInstance<ChipReadState.LinkLost>().map { it.restartsLeft })
        }

    @Test
    fun K_10_recordedTimings_theReadInFourToSixSeconds() =
        simTest { flow ->
            read(deviceOptions(flow))
            assertTrue(commandSpan(1) in 4_000..5_800)
        }

    @Test
    fun K_12_dg1Of95Bytes_parses() =
        simTest(build = { keys -> DeviceChipProfile.build(chipCrypto(), keys, td1 = true) }) { flow ->
            val read = assertIs<ChipReadEnding.Complete>(read(deviceOptions(flow)).ending).read
            assertTrue(read.dg1 != null)
        }

    // --- Group I ----------------------------------------------------------------------------------

    @Test
    fun I_01_chipThreeTimesSlower_noLossAndTheSameResult() =
        simTest(latencyFactor = 3) { flow ->
            val r = read(deviceOptions(flow))
            assertDeviceChecksPass(r.ending)
            assertEquals(1, r.ending.attempts)
            assertTrue(commandSpan(1) in 12_000..17_400)
        }

    @Test
    fun I_02_oneCommandTakes5100ms_thePlatformTimesOutAndTheReadRecovers() =
        simTest { flow ->
            script.fault(ChipFaultAction.Delay(5_100), match = Shapes.firstProtectedSelect)
            recovered(read(deviceOptions(flow)))
        }

    // --- Group H through the Android entry point ---------------------------------------------------

    @Test
    fun H_06_lossMidDg2_theSameChipBack_keepsEarlierFilesAndContinuesDg2() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(10), match = Shapes.midDg2)
            val ending = assertIs<ChipReadEnding.Complete>(read(deviceOptions(flow)).ending)
            assertDeviceChecksPass(ending)
            assertEquals(ChipResumeOutcome.SAME_SECURITY_OBJECT, ending.read.resume)
            assertTrue(ending.read.files.single { it.dataGroup == 2 }.continuedAt != null)
        }

    // --- Refusals: never a new attempt (DES 5.1 rule 2), review item 17 ---------------------------

    @Test
    fun G_29_wrongKey_keyNotAcceptedNoRestartAndTheLinkClosed() =
        simTest { flow ->
            val r = read(ChipReadOptions(FakeReadFlowChip.wrongMrzKey(), setOf(1, 2), listOf(flow.anchor), CHECK_TIME))
            val ending = assertIs<ChipReadEnding.KeyNotAccepted>(r.ending)
            assertEquals(ChipAccessDenied.Reason.KEY_NOT_MATCHED, ending.error.reason)
            assertEquals(1, script.links)
            assertEquals(1, script.exchanges.count { it.command.ins == 0x82 })
            assertEquals(1, channels.single().closeCalls)
        }

    @Test
    fun H_18_secureMessagingErrorMidRead_partialAndNoNewAttempt() =
        simTest { flow ->
            script.fault(ChipFaultAction.Status(0x6988), match = Shapes.midDg2)
            val r = read(deviceOptions(flow))
            assertIs<ChipSecureMessagingLost>(assertIs<ChipReadEnding.Partial>(r.ending).stoppedBy)
            assertEquals(1, script.links)
            assertTrue(r.states.none { it is ChipReadState.LinkLost })
        }

    @Test
    fun H_18_badMacMidRead_partialAndNoNewAttempt() =
        simTest { flow ->
            script.fault(ChipFaultAction.Arm { flow.sm!!.corruptNextResponseMac = true }, match = Shapes.midDg2)
            val r = read(deviceOptions(flow))
            assertIs<ChipSecureMessagingLost>(assertIs<ChipReadEnding.Partial>(r.ending).stoppedBy)
            assertEquals(1, script.links)
        }

    @Test
    fun H_18_malformedFileAfterALoss_noFurtherAttempt() =
        simTest(build = { keys -> FakeReadFlowChip.build(chipCrypto(), keys, FlowAccess.BAC, extraApplicationFiles = mapOf(0x0101 to byteArrayOf(0x61, 0x7F, 0x01))) }) { flow ->
            // A loss first (a new attempt follows), then a malformed DG1 in the new attempt: the read
            // goes on to its end and the malformed file is a file error, never a reason to restart.
            script.fault(ChipFaultAction.LoseLink(10)) { it.secured && it.ins == 0xA4 }
            val r = read(ChipReadOptions(FakeReadFlowChip.mrzKey(), setOf(1, 2), listOf(flow.anchor), CHECK_TIME))
            val ending = assertIs<ChipReadEnding.Complete>(r.ending)
            assertEquals(2, ending.attempts)
            assertTrue(ending.read.fileErrors.isNotEmpty())
            assertEquals(2, script.links)
        }

    // --- Waits: review item 14 ---------------------------------------------------------------------

    @Test
    fun G_19_waitForReturnZero_aLossEndsTheReadAtOnce() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(10), match = Shapes.midDg2)
            val r = read(deviceOptions(flow), NfcReadSettings(waitForReturnMillis = 0))
            assertEquals(NfcTagLost, assertIs<ChipReadEnding.Partial>(r.ending).stoppedBy)
            assertEquals(1, script.links)
        }

    @Test
    fun G_23_waitForDocumentLongMaxValue_meansNoLimitAndDoesNotOverflow() =
        simTest { flow ->
            val (read, recorder) = start(deviceOptions(flow), NfcReadSettings(waitForDocumentMillis = Long.MAX_VALUE))
            step(30_000)
            assertTrue(recorder.endings.isEmpty(), "the read still waits: ${recorder.endings}")
            read.onTagDiscovered(AndroidSim.aTag())
            runUntilEnded(recorder)
            assertDeviceChecksPass(recorder.ending)
        }

    @Test
    fun G_19_waitForReturnLongMaxValue_doesNotOverflow() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(10), match = Shapes.midDg2)
            val r = read(deviceOptions(flow), NfcReadSettings(waitForReturnMillis = Long.MAX_VALUE))
            assertDeviceChecksPass(r.ending)
            assertEquals(2, r.ending.attempts)
        }

    // --- Pause, screen lock, reader option: OPEN DECISION G-07 ------------------------------------

    /**
     * G-07 / G-20, OPEN DECISION. The design (section 4, "Activity paused", "Screen locked") says the
     * read ends as EndedBySystem; the code treats the platform turning reader mode off as a lost
     * link and goes on when the document is found again within waitForReturnMillis. This pins the
     * CURRENT behaviour: a pause of 10 s mid-read, then the screen back, ends Complete in attempt 2.
     */
    @Test
    fun G_07_openDecision_activityPausedTenSecondsMidRead_currentlyResumesAndCompletes() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(0)) { c -> Shapes.midDg2(c).also { if (it) paused = true } }
            val (read, recorder) = start(deviceOptions(flow))
            step(1_000)
            read.onTagDiscovered(AndroidSim.aTag())
            val pausedUntil = now() + 12_000
            while (now() < pausedUntil) step(50)
            paused = false
            runUntilEnded(recorder)
            assertNull(recorder.checkPromises())
            assertDeviceChecksPass(recorder.ending)
            assertEquals(2, recorder.ending.attempts)
        }

    @Test
    fun G_20_openDecision_screenLockedLongerThanTheReturnWait_currentlyPartialWithTheLoss() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(0)) { c -> Shapes.midDg2(c).also { if (it) paused = true } }
            val (read, recorder) = start(deviceOptions(flow))
            step(1_000)
            read.onTagDiscovered(AndroidSim.aTag())
            runUntilEnded(recorder)
            assertNull(recorder.checkPromises())
            assertEquals(NfcTagLost, assertIs<ChipReadEnding.Partial>(recorder.ending).stoppedBy)
        }

    /**
     * G-04, OPEN DECISION: the reader option switched off (a flagged platform API the SDK does not
     * read) shows only as failing commands and connects. The design expects EndedBySystem(DISABLED);
     * the code cannot tell it from a lost link, so it waits waitForReturnMillis and ends with the loss.
     */
    @Test
    fun G_04_openDecision_readerOptionOffMidRead_currentlyEndsWithTheLossAfterTheWait() =
        simTest { flow ->
            script.fault(ChipFaultAction.Arm { paused = true }, match = Shapes.firstProtectedSelect)
            val r = read(deviceOptions(flow))
            assertEquals(NfcTagLost, assertIs<ChipReadEnding.Partial>(r.ending).stoppedBy)
        }
}
