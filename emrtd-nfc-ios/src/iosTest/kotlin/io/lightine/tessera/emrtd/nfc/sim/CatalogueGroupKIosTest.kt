package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipCheckKind
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadEvent
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipReadStep
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.emrtd.fakechip.ChipCommand
import io.lightine.tessera.emrtd.fakechip.ChipFaultAction
import io.lightine.tessera.emrtd.fakechip.DeviceChipProfile
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.fakechip.ScriptedChip
import io.lightine.tessera.emrtd.fakechip.SimRecorder
import io.lightine.tessera.emrtd.internal.crypto.chipCrypto
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcUnavailable
import io.lightine.tessera.types.vocabulary.CheckOutcome
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The recorded command count of a whole read of the device-shaped chip, by start (device evidence, K-01). */
internal object DeviceCounts {
    /** The system selected the eMRTD application and says so: the reader skips the SELECT bound to fail (J-01). */
    const val IOS_APPLICATION_SELECTED = 122

    /** The application is current but the platform does not say so: the old order, one SELECT more. */
    const val IOS_NOT_SAID = 123

    /** Android: the master file is current at activation, so the EF.CardAccess probe needs neither. */
    const val ANDROID = 121
}

internal val CHECK_TIME: Instant = Instant.parse("2026-06-01T12:00:00Z")

internal fun deviceOptions(flow: FlowChip): ChipReadOptions =
    ChipReadOptions(DeviceChipProfile.key(), setOf(1, 2, 14, 15), listOf(flow.anchor), CHECK_TIME)

/** Commands of the profile's failure shapes (device evidence, K-03 to K-05). */
internal object Shapes {
    val externalAuthenticate: (ChipCommand) -> Boolean = { it.ins == 0x82 }
    val plainSelectOfTheApplication: (ChipCommand) -> Boolean = { !it.secured && it.ins == 0xA4 && it.apdu?.p1 == 0x04 }
    val firstProtectedSelect: (ChipCommand) -> Boolean = { it.secured && it.ins == 0xA4 }
    val mseSetKat: (ChipCommand) -> Boolean = { it.ins == 0x22 && it.apdu?.p2 == 0xA6 }

    /** The first-second loss recorded on devices: no answer, the platform reports "no response" after about 640 ms. */
    const val LOSS_AFTER_MILLIS = 640L
}

/** The checks a complete read of the device-shaped chip must pass (nothing failed; the protocol checks ran). */
internal fun assertDeviceChecksPass(ending: ChipReadEnding) {
    val read = assertIs<ChipReadEnding.Complete>(ending, "$ending").read
    assertEquals(ChipAccessProtocol.BAC, read.access.protocol)
    assertTrue(read.checks.none { it.outcome is CheckOutcome.Failed }, "${read.checks}")
    assertEquals(
        CheckOutcome.Passed,
        read.outcomeOf(ChipCheckKind.SignatureVerifies(io.lightine.tessera.emrtd.ChipSignedFile.SECURITY_OBJECT)),
    )
    assertEquals(
        CheckOutcome.Passed,
        read.outcomeOf(ChipCheckKind.ChipAuthentication(io.lightine.tessera.emrtd.ChipFileContext.EMRTD_APPLICATION)),
    )
    for (n in listOf(1, 2, 14, 15)) assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.DataGroupHashMatches(n)), "DG$n")
}

/**
 * Group K of the scenario catalogue (TES-346), device-evidence replays, through the iPhone entry
 * point [CoreNfcChipRead]: fake Core NFC sessions, the device-shaped scripted chip
 * ([DeviceChipProfile]) with the recorded sizes and timings, in virtual time.
 */
class CatalogueGroupKIosTest {
    private fun simTest(
        initialSelectedAid: String? = EMRTD_AID,
        td1: Boolean = false,
        block: suspend IosSim.(FlowChip) -> Unit,
    ) = runTest {
        val flow = DeviceChipProfile.build(chipCrypto(), TestKeys.get(), td1 = td1, startInApplication = true)
        val sim = IosSim(this, ScriptedChip(flow.chip, DeviceChipProfile.latency), initialSelectedAid)
        try {
            sim.block(flow)
        } finally {
            sim.close()
        }
    }

    private fun IosSim.read(
        flow: FlowChip,
        settings: CoreNfcReadSettings = CoreNfcReadSettings(),
    ): SimRecorder {
        val (_, recorder) = start(deviceOptions(flow), settings)
        runUntilEnded(recorder)
        assertNull(recorder.checkPromises())
        return recorder
    }

    /** Virtual time from the first command to the last answer of [link]. */
    private fun IosSim.commandSpan(link: Int): Long {
        val ex = script.exchangesOf(link)
        val last = ex.last()
        return last.sentAtMillis + last.reply.delayMillis - ex.first().sentAtMillis
    }

    @Test
    fun k01_completeRead_applicationSelectedBySystem_122CommandsInTheRecordedTime() =
        simTest { flow ->
            val r = read(flow)

            assertDeviceChecksPass(r.ending)
            assertEquals(1, r.ending.attempts)
            assertEquals(DeviceCounts.IOS_APPLICATION_SELECTED, script.exchanges.size)
            assertEquals(1, script.links)
            assertEquals(
                listOf("Starting", "WaitingForDocument", "DocumentFound", "Connecting", "OpeningAccess", "ReadingFile"),
                r.stateNames.take(6),
            )
            assertTrue("AuthenticatingChip" in r.stateNames, "${r.stateNames}")
            assertEquals("CheckingFiles", r.stateNames.last())
            assertTrue(r.states.none { it is ChipReadState.LinkLost })
            // Recorded on devices: a whole read of 122 or 123 commands in 4.0 to 5.8 s.
            assertTrue(commandSpan(1) in 4_000..5_800, "span ${commandSpan(1)}")
            // The sheet: the last text is the one for a complete read.
            assertEquals(CoreNfcDefaultSheetTexts.textForEnding(r.ending), alerts.last())
        }

    @Test
    fun k01_completeRead_applicationCurrentButNotSaid_123Commands() =
        simTest(initialSelectedAid = null) { flow ->
            val r = read(flow)

            assertDeviceChecksPass(r.ending)
            assertEquals(DeviceCounts.IOS_NOT_SAID, script.exchanges.size)
        }

    @Test
    fun k02_cardAccessProbe_fourPlainCommandsWhenTheSystemSaysAndFiveWhenNot() =
        simTest { flow ->
            read(flow)
            // SELECT MF, SELECT 011C (9000 although absent), READ BINARY (6986), SELECT application.
            val probe =
                script.exchanges.takeWhile { it.command.ins != 0x84 }.map {
                    it.command.ins to
                        (it.reply as io.lightine.tessera.emrtd.fakechip.ChipReply.Answer).sw
                }
            assertEquals(listOf(0xA4 to 0x9000, 0xA4 to 0x9000, 0xB0 to 0x6986, 0xA4 to 0x9000), probe)
        }

    @Test
    fun k02_cardAccessProbe_oldOrderStartsWithTheSelectThatAnswers6982() =
        simTest(initialSelectedAid = null) { flow ->
            read(flow)
            val probe =
                script.exchanges
                    .takeWhile {
                        it.command.ins != 0x84
                    }.map { (it.reply as io.lightine.tessera.emrtd.fakechip.ChipReply.Answer).sw }
            assertEquals(listOf(0x6982, 0x9000, 0x9000, 0x6986, 0x9000), probe)
        }

    // --- K-03 to K-05: one command unanswered in the first second, the platform reports 102 -------

    private fun IosSim.firstSecondLoss(
        flow: FlowChip,
        at: (ChipCommand) -> Boolean,
        settings: CoreNfcReadSettings = CoreNfcReadSettings(),
    ): SimRecorder {
        script.fault(ChipFaultAction.LoseLink(Shapes.LOSS_AFTER_MILLIS), match = at)
        return read(flow, settings)
    }

    private fun IosSim.assertRecoveredInTheSameSession(r: SimRecorder) {
        assertDeviceChecksPass(r.ending)
        assertEquals(2, r.ending.attempts)
        assertEquals(1, ports.size, "the same system session")
        assertEquals(1, port.restarts)
        val lost = r.updates.single { it.value.state is ChipReadState.LinkLost }
        assertEquals(2, (lost.value.state as ChipReadState.LinkLost).restartsLeft)
        val lossSent = script.exchanges.first { it.reply is io.lightine.tessera.emrtd.fakechip.ChipReply.LinkLost }.sentAtMillis
        assertEquals(lossSent + Shapes.LOSS_AFTER_MILLIS, lost.atMillis, "the loss is seen when the platform reports it")
        val after =
            r.updates
                .dropWhile { it.value.state !is ChipReadState.LinkLost }
                .drop(1)
                .map { it.value.state::class.simpleName }
        assertEquals(listOf("DocumentFound", "Connecting"), after.take(2), "document found again, then a new link")
        assertTrue(alerts.isNotEmpty())
    }

    @Test
    fun k03_externalAuthenticateUnansweredInFirstSecond_resumesAndCompletes() =
        simTest { flow ->
            val r = firstSecondLoss(flow, Shapes.externalAuthenticate)
            assertRecoveredInTheSameSession(r)
            // The same key is accepted on the second try: BAC is not blocked by tries (T1 2.3).
            assertEquals(2, script.exchanges.count { it.command.ins == 0x82 })
        }

    @Test
    fun k03_externalAuthenticateUnansweredInFirstSecond_endPolicyFailsCleanly() =
        simTest { flow ->
            val r = firstSecondLoss(flow, Shapes.externalAuthenticate, CoreNfcReadSettings(linkLoss = LinkLossPolicy.End))
            val ending = assertIs<ChipReadEnding.Failed>(r.ending)
            assertEquals(NfcTagLost, ending.error)
            assertEquals(1, ending.attempts)
            assertEquals(1, script.links)
        }

    /** A loss before access: one more try of the same attempt in the same session, no restart used. */
    private fun IosSim.assertTriedAgainBeforeAccess(r: SimRecorder) {
        assertDeviceChecksPass(r.ending)
        assertEquals(1, r.ending.attempts)
        assertEquals(1, ports.size, "the same system session")
        assertEquals(1, port.restarts)
        assertTrue(r.states.none { it is ChipReadState.LinkLost })
        assertEquals(listOf(1), r.states.filterIsInstance<ChipReadState.NoAnswerYet>().map { it.tries })
    }

    @Test
    fun k04_plainSelectUnansweredInFirstSecond_triesAgainWithNoRestartAndCompletes() =
        simTest { flow ->
            assertTriedAgainBeforeAccess(firstSecondLoss(flow, Shapes.plainSelectOfTheApplication))
        }

    @Test
    fun k04_plainSelectUnansweredInFirstSecond_endPolicyAlsoTriesAgainBeforeAccess() =
        simTest { flow ->
            assertTriedAgainBeforeAccess(
                firstSecondLoss(flow, Shapes.plainSelectOfTheApplication, CoreNfcReadSettings(linkLoss = LinkLossPolicy.End)),
            )
        }

    @Test
    fun k05_firstProtectedSelectUnansweredInFirstSecond_resumesAndCompletes() =
        simTest { flow ->
            assertRecoveredInTheSameSession(firstSecondLoss(flow, Shapes.firstProtectedSelect))
        }

    @Test
    fun k05_firstProtectedSelectUnansweredInFirstSecond_endPolicyIsPartialAfterAccess() =
        simTest { flow ->
            val r = firstSecondLoss(flow, Shapes.firstProtectedSelect, CoreNfcReadSettings(linkLoss = LinkLossPolicy.End))
            val ending = assertIs<ChipReadEnding.Partial>(r.ending)
            assertEquals(NfcTagLost, ending.stoppedBy)
            assertEquals(ChipAccessProtocol.BAC, ending.read.access.protocol)
        }

    // --- K-06 / D-15 / I-09: MSE:Set KAT unanswered for 18.3 s -----------------------------------

    @Test
    fun k06_mseSetKatAnsweredOnlyAfter18s_ourFiveSecondLimitFiresAndTheReadRecovers() =
        simTest { flow ->
            script.fault(ChipFaultAction.Delay(18_300), match = Shapes.mseSetKat)
            val r = read(flow)

            assertRecoveredAfterTheCommandLimit(r)
        }

    @Test
    fun k06_mseSetKatNeverAnswered_ourFiveSecondLimitFiresAndTheReadRecovers() =
        simTest { flow ->
            script.fault(ChipFaultAction.Silence, match = Shapes.mseSetKat)
            val r = read(flow)

            assertRecoveredAfterTheCommandLimit(r)
        }

    private fun IosSim.assertRecoveredAfterTheCommandLimit(r: SimRecorder) {
        assertDeviceChecksPass(r.ending)
        assertEquals(2, r.ending.attempts)
        val katSent = script.exchanges.first { Shapes.mseSetKat(it.command) }.sentAtMillis
        val lost = r.updates.single { it.value.state is ChipReadState.LinkLost }
        assertEquals(katSent + CoreNfcReadSettings.DEFAULT_COMMAND_TIMEOUT_MILLIS, lost.atMillis, "5 s, not 18.3 s")
        // Chip authentication runs again in the new session (DES 5.2).
        assertEquals(2, script.exchanges.count { Shapes.mseSetKat(it.command) })
        assertTrue(r.endings.single().atMillis < CoreNfcChipRead.SESSION_LIMIT_MILLIS)
    }

    // --- K-07 / F-35: no tag reported, the system ends the session (202) ------------------------

    @Test
    fun k07_noTagReportedThenSystemEnds202_endedBySystemAndTheNextStartWaits() =
        simTest { flow ->
            documentInField = false
            val (_, first) = start(deviceOptions(flow))
            step(24_000)
            systemEnds(CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY)
            val endedAt = now()

            val ending = assertIs<ChipReadEnding.EndedBySystem>(first.ending)
            assertEquals(NfcUnavailable(NfcUnavailable.Reason.ENDED_BY_SYSTEM), ending.reason)
            assertNull(first.checkPromises())
            val waits = first.updates.filter { it.value.state is ChipReadState.WaitingForDocument }.map { it.atMillis }
            assertEquals((0L..24_000L step 2_000).toList(), waits, "the time waited every 2 s")
            assertTrue(port.endings.isEmpty(), "the SDK sends no ending text after a system end")

            // A start 300 ms later waits in Starting until waitAfterSystemEnd (1 s) has passed.
            documentInField = true
            step(300)
            val (_, second) = start(deviceOptions(flow))
            step(600)
            assertEquals(listOf("Starting"), second.stateNames)
            assertEquals(1, ports.size)
            runUntilEnded(second)
            assertTrue(ports[1].began)
            val begunAt = second.firstAt<ChipReadState.WaitingForDocument>()!!
            assertTrue(begunAt >= endedAt + CoreNfcReadSettings.DEFAULT_WAIT_AFTER_SYSTEM_END_MILLIS, "begun $begunAt, ended $endedAt")
            assertDeviceChecksPass(second.ending)
        }

    @Test
    fun k08_newSessionRefusedBusy203_notStartedAndTheNextStartWaits() =
        simTest { flow ->
            refuseBeginWith = IosSim.codeError(CoreNfcErrorCode.SYSTEM_IS_BUSY, CoreNfcErrorSource.SESSION_END)
            val (_, first) = start(deviceOptions(flow))
            step(10)
            assertEquals(NfcUnavailable.Reason.BUSY, assertIs<ChipReadEnding.NotStarted>(first.ending).reason)
            val refusedAt = first.endings.single().atMillis

            val (_, second) = start(deviceOptions(flow))
            runUntilEnded(second)
            val begunAt = second.firstAt<ChipReadState.WaitingForDocument>()!!
            assertTrue(begunAt >= refusedAt + CoreNfcReadSettings.DEFAULT_WAIT_AFTER_SYSTEM_END_MILLIS)
            assertDeviceChecksPass(second.ending)
        }

    @Test
    fun k09_everyNewLinkLosesItsFirstCommand_noRestartUsedEndsFailedWhenTheSessionRunsLow() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(Shapes.LOSS_AFTER_MILLIS), times = 99) { it.indexInLink == 0 }
            val r = read(flow)

            val ending = assertIs<ChipReadEnding.Failed>(r.ending)
            assertEquals(NfcTagLost, ending.error)
            assertEquals(1, ending.attempts)
            assertTrue(r.states.none { it is ChipReadState.LinkLost })
            // One try every 1.66 s (600 to find it again, 400 settle, 20 connect, 640 loss) until less
            // than 10 s of the 60 s session are left.
            assertEquals(30, script.links)
            assertEquals(1, ports.size)
        }

    // --- Before access: a short time limit, and tries that cost no restart (TES-346) ----------------

    /**
     * The device case on iPhone: the chip is found but never answers its first command. Before, three
     * tries of 5 s each spent the restart budget (Failed after about 18 s). Now each try waits the
     * 1.5 s limit and costs no restart; the system finds the document again 600 ms after each, so
     * one try every 2.52 s, in the same session, until less than 10 s of it are left: 20 tries, then
     * Failed with the SDK's time limit as the lost link.
     */
    @Test
    fun noAnswer_a_firstCommandNeverAnswered_shortTriesUntilTheSessionRunsLowThenFailed() =
        simTest { flow ->
            script.fault(ChipFaultAction.Silence, times = 99) { it.indexInLink == 0 }
            val r = read(flow)
            val ending = assertIs<ChipReadEnding.Failed>(r.ending)
            assertEquals(
                io.lightine.tessera.types.errors
                    .NfcTimeout(1_500),
                ending.error,
            )
            assertEquals(1, ending.attempts)
            assertEquals(0, ending.progress.percent)
            assertEquals(20, script.links)
            assertEquals(1, ports.size, "one system session")
            assertEquals(50_800, r.endings.single().atMillis)
            // (e) What the app saw: found once, then "no answer yet" with a growing count.
            assertEquals(
                listOf("Starting", "WaitingForDocument", "DocumentFound", "Connecting", "OpeningAccess") + List(20) { "NoAnswerYet" },
                r.states.map { it::class.simpleName },
            )
            assertEquals((1..20).toList(), r.states.filterIsInstance<ChipReadState.NoAnswerYet>().map { it.tries })
            val sent = script.exchanges.map { it.sentAtMillis }
            val seen = r.updates.filter { it.value.state is ChipReadState.NoAnswerYet }.map { it.atMillis }
            assertEquals(sent.map { it + 1_500 }, seen, "each try waits the 1.5 s limit before access")
            assertTrue("Found, no answer yet. Move the phone a little." in alerts)
        }

    /** Silent twice, then the chip answers: complete, with both restarts left for two later losses. */
    @Test
    fun noAnswer_b_silentTwiceThenAnswers_theFullRestartBudgetIsLeftForLaterLosses() =
        simTest { flow ->
            val midDg2: (ChipCommand) -> Boolean = { it.secured && it.ins == 0xB0 && it.chip.currentEf == 0x0102 && it.index > 60 }
            script.fault(ChipFaultAction.Silence, times = 2) { it.indexInLink == 0 }
            script.fault(ChipFaultAction.LoseLink(10), times = 2, match = midDg2)
            val r = read(flow)
            assertDeviceChecksPass(r.ending)
            assertEquals(3, r.ending.attempts)
            assertEquals(listOf(2, 1), r.states.filterIsInstance<ChipReadState.LinkLost>().map { it.restartsLeft })
            assertEquals(5, script.links)
            assertEquals(
                listOf(
                    "Starting",
                    "WaitingForDocument",
                    "DocumentFound",
                    "Connecting",
                    "OpeningAccess",
                    "NoAnswerYet",
                    "NoAnswerYet",
                    "OpeningAccess",
                ),
                r.states.take(8).map { it::class.simpleName },
            )
            assertEquals(ChipAccessProtocol.BAC, (r.states[7] as ChipReadState.OpeningAccess).protocol)
        }

    /**
     * The device case on iPhone: the plain commands are answered, then GET CHALLENGE is not. GET
     * CHALLENGE is before the key is used: the try waits 1.5 s, costs no restart, and the next try,
     * in the same session, completes.
     */
    @Test
    fun noAnswer_f_getChallengeSilentOnceAfterThePlainCommands_triesAgainFreeAndCompletes() =
        simTest { flow ->
            script.fault(ChipFaultAction.Silence) { it.ins == 0x84 }
            val r = read(flow)
            assertDeviceChecksPass(r.ending)
            assertEquals(1, r.ending.attempts)
            assertEquals(2, script.links)
            assertEquals(1, ports.size, "the same system session")
            assertTrue(r.states.none { it is ChipReadState.LinkLost })
            assertEquals(listOf(1), r.states.filterIsInstance<ChipReadState.NoAnswerYet>().map { it.tries })
            val challenge = script.exchanges.first { it.command.ins == 0x84 }
            assertTrue(challenge.command.indexInLink > 0, "plain commands were answered first")
            val noAnswer = r.updates.single { it.value.state is ChipReadState.NoAnswerYet }
            assertEquals(challenge.sentAtMillis + 1_500, noAnswer.atMillis, "GET CHALLENGE waits 1.5 s, not 5 s")
            val fractions = r.updates.map { it.value.progress.fraction }
            assertEquals(fractions.sorted(), fractions, "progress never goes back")
        }

    /** A GET CHALLENGE answered with an error status word is an answer, not silence: as before. */
    @Test
    fun noAnswer_h_getChallengeAnsweredWithAnErrorStatusWord_isARefusalAsBefore() =
        simTest { flow ->
            script.fault(ChipFaultAction.Status(0x6982)) { it.ins == 0x84 }
            val r = read(flow)
            val error = assertIs<ChipReadEnding.KeyNotAccepted>(r.ending, "${r.ending}").error
            assertEquals(0x6982, error.statusWord)
            assertEquals(1, script.links)
            assertTrue(r.states.none { it is ChipReadState.NoAnswerYet || it is ChipReadState.LinkLost })
        }

    @Test
    fun k10_recordedTimings_eachCommandInItsRangeAndTheReadInFourToSixSeconds() =
        simTest { flow ->
            read(flow)
            val reads = script.exchanges.filter { it.command.ins == 0xB0 && it.command.secured }.map { it.reply.delayMillis }
            assertTrue(reads.all { it in 31..43 })
            assertTrue(script.exchanges.filter { it.command.ins == 0x82 }.all { it.reply.delayMillis in 98..250 })
            assertTrue(script.exchanges.filter { Shapes.mseSetKat(it.command) }.all { it.reply.delayMillis in 427..454 })
            assertTrue(commandSpan(1) in 4_000..5_800)
        }

    /**
     * K-11 [JDG]: the bar against the time on the recorded profile. No band is agreed yet: this
     * pins the promises (never back, 99 until the end) and that the bar is not far ahead of the
     * time; the fractions are in the report of card TES-346.
     */
    @Test
    fun k11_progressFidelity_neverBackAndNeverFarAheadOfTheTime() =
        simTest { flow ->
            val r = read(flow)
            val ex = script.exchangesOf(1)
            val t0 = ex.first().sentAtMillis
            val total = (commandSpan(1)).toDouble()
            val reading = r.updates.filter { it.atMillis >= t0 && it.value.state is ChipReadState.ReadingFile }
            for (u in reading) {
                val time = (u.atMillis - t0) / total
                assertTrue(u.value.progress.fraction - time < 0.35, "bar ${u.value.progress.fraction} at time $time")
            }
        }

    @Test
    fun k12_dg1Of93And95Bytes_bothParse() =
        simTest(td1 = true) { flow ->
            assertEquals(95, DeviceChipProfile.sizes(flow).getValue("DG1"))
            val r = read(flow)
            val read = assertIs<ChipReadEnding.Complete>(r.ending).read
            assertTrue(read.dg1 != null, "the TD1 DG1 parses")
        }

    @Test
    fun k12_dg1Of93Bytes_parses() =
        simTest { flow ->
            assertEquals(93, DeviceChipProfile.sizes(flow).getValue("DG1"))
            val read = assertIs<ChipReadEnding.Complete>(read(flow).ending).read
            assertTrue(read.dg1 != null)
        }

    @Test
    fun d15_mseSetKatUnansweredUntilThePlatformLimit_isALinkLossAtFiveSeconds() =
        simTest { flow ->
            script.fault(ChipFaultAction.Delay(18_300), match = Shapes.mseSetKat)
            val r = read(flow)
            val lost = r.updates.single { it.value.state is ChipReadState.LinkLost }
            assertTrue(r.states.contains(lost.value.state))
            assertEquals(2, r.ending.attempts)
        }

    // --- The telemetry sink through the easy entry point --------------------------------------------

    @Test
    fun telemetry_aSinkInTheSettingsHearsEveryStepThroughTheEntryPointWithNoDocumentData() =
        simTest { flow ->
            val events = mutableListOf<TelemetryEvent>()
            val sink =
                object : TelemetrySink {
                    override fun record(event: TelemetryEvent) {
                        events += event
                    }
                }
            val (_, recorder) = start(deviceOptions(flow), telemetry = sink)
            runUntilEnded(recorder)
            assertNull(recorder.checkPromises())
            assertDeviceChecksPass(recorder.ending)
            val steps = events.map { assertIs<ChipReadEvent>(it, "only the read's step events: $it") }
            assertTrue(steps.all { it.name == ChipReadEvent.EVENT_NAME })
            val kinds = steps.map { it.step }.toSet()
            for (expected in listOf(
                ChipReadStep.BAC,
                ChipReadStep.READ_FILE,
                ChipReadStep.CHIP_AUTHENTICATION,
                ChipReadStep.PASSIVE_AUTHENTICATION,
            )) {
                assertTrue(expected in kinds, "$expected in $kinds")
            }
            assertTrue(steps.any { it.fileId == 0x0101 } && steps.any { it.fileId == 0x0102 }, "DG1 and DG2 are named by file id")
            val text = steps.joinToString()
            val (line1, line2) = FakeReadFlowChip.lines
            val values =
                listOf(line1, line2, line2.substring(0, 9), line2.substring(13, 19), line2.substring(21, 27)) +
                    line1.substring(5).split('<').filter { it.length >= 3 }
            for (value in values) assertTrue(value !in text, "telemetry carries a document value: $text")
        }
}
