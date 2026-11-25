package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipCheckKind
import io.lightine.tessera.emrtd.ChipFileContext
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadEvent
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipReadStep
import io.lightine.tessera.emrtd.ChipReadStepOutcome
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
import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.telemetry.TelemetrySink
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private val CHECK_TIME: Instant = Instant.parse("2026-06-01T12:00:00Z")

private fun deviceOptions(flow: FlowChip) = ChipReadOptions(DeviceChipProfile.key(), setOf(1, 2, 14, 15), listOf(flow.anchor), CHECK_TIME)

/** No value of the document reaches the telemetry text: no MRZ line, number, date or name. */
private fun assertNoDocumentData(text: String) {
    val (line1, line2) = FakeReadFlowChip.lines
    val values =
        listOf(line1, line2, line2.substring(0, 9), line2.substring(13, 19), line2.substring(21, 27)) +
            line1.substring(5).split('<').filter { it.length >= 3 }
    for (value in values) assertTrue(value !in text, "telemetry carries a document value: $text")
}

// Virtual time of the GET CHALLENGE scenarios on the device-shaped chip, from the document found:
// F, silent once then complete: 7.345 s (the silent try ends 1.5 s after GET CHALLENGE, about 1.9 s
// after found; under the old 5 s limit that try alone took 3.5 s longer and spent a restart). G,
// never answered:
// 8 tries of about 2.24 s (300 pause, 400 settle, the plain commands, 1500 limit) within the 15 s
// return wait counted from the first, ending 17.588 s after found, with no restart used.
private const val TOTAL_F = 7_345L
private const val TRIES_G = 8
private const val END_G = 17_588L

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
    fun k01_completeRead_masterFileCurrent_121CommandsInTheRecordedTime() =
        simTest { flow ->
            val r = read(deviceOptions(flow))
            assertDeviceChecksPass(r.ending)
            assertEquals(1, r.ending.attempts)
            // The recorded 122-123 are iPhone counts; Android starts in the master file and needs
            // neither the SELECT that answers 6982 nor SELECT MF: 121 (see the iOS group K tests).
            assertEquals(121, script.exchanges.size)
            assertEquals(
                listOf("Starting", "WaitingForDocument", "DocumentFound", "Connecting", "OpeningAccess", "ReadingFile"),
                r.stateNames.take(6),
            )
            assertTrue("AuthenticatingChip" in r.stateNames)
            assertEquals("CheckingFiles", r.stateNames.last())
            assertTrue(commandSpan(1) in 4_000..5_800, "span ${commandSpan(1)}")
            assertEquals(
                listOf(
                    NfcReadSettings.DEFAULT_COMMAND_TIMEOUT_BEFORE_ACCESS_MILLIS.toInt(),
                    NfcReadSettings.DEFAULT_COMMAND_TIMEOUT_MILLIS.toInt(),
                ),
                channels.single().timeouts,
                "1.5 s at connect, then the 5 s limit once, before access",
            )
        }

    @Test
    fun k02_cardAccessProbe_threePlainCommandsFromTheMasterFile() =
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
    fun k03_externalAuthenticateUnansweredInFirstSecond_resumesAndCompletes() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(Shapes.LOSS_AFTER_MILLIS), match = Shapes.externalAuthenticate)
            recovered(read(deviceOptions(flow)))
            assertEquals(2, script.exchanges.count { it.command.ins == 0x82 })
        }

    @Test
    fun k03_externalAuthenticateUnansweredInFirstSecond_endPolicyFailsCleanly() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(Shapes.LOSS_AFTER_MILLIS), match = Shapes.externalAuthenticate)
            val r = read(deviceOptions(flow), NfcReadSettings(linkLoss = LinkLossPolicy.End))
            assertEquals(NfcTagLost, assertIs<ChipReadEnding.Failed>(r.ending).error)
            assertEquals(1, script.links)
        }

    /** A loss before access: one more try of the same attempt, no restart used, then the read completes. */
    private fun AndroidSim.triedAgainBeforeAccess(r: SimRecorder) {
        assertDeviceChecksPass(r.ending)
        assertEquals(1, r.ending.attempts)
        assertEquals(2, script.links)
        assertTrue(r.states.none { it is ChipReadState.LinkLost }, "${r.stateNames}")
        assertEquals(listOf(1), r.states.filterIsInstance<ChipReadState.NoAnswerYet>().map { it.tries })
    }

    @Test
    fun k04_plainSelectUnansweredInFirstSecond_triesAgainWithNoRestartAndCompletes() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(Shapes.LOSS_AFTER_MILLIS), match = Shapes.plainSelectOfTheApplication)
            triedAgainBeforeAccess(read(deviceOptions(flow)))
        }

    @Test
    fun k04_plainSelectUnansweredInFirstSecond_endPolicyAlsoTriesAgainBeforeAccess() =
        simTest { flow ->
            // The End policy ends at the first lost link of an attempt; a loss before access is no
            // attempt's loss: the chip was asked nothing about the key.
            script.fault(ChipFaultAction.LoseLink(Shapes.LOSS_AFTER_MILLIS), match = Shapes.plainSelectOfTheApplication)
            triedAgainBeforeAccess(read(deviceOptions(flow), NfcReadSettings(linkLoss = LinkLossPolicy.End)))
        }

    @Test
    fun k05_firstProtectedSelectUnansweredInFirstSecond_resumesAndCompletes() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(Shapes.LOSS_AFTER_MILLIS), match = Shapes.firstProtectedSelect)
            recovered(read(deviceOptions(flow)))
        }

    @Test
    fun k05_firstProtectedSelectUnansweredInFirstSecond_endPolicyIsPartialAfterAccess() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(Shapes.LOSS_AFTER_MILLIS), match = Shapes.firstProtectedSelect)
            val r = read(deviceOptions(flow), NfcReadSettings(linkLoss = LinkLossPolicy.End))
            assertEquals(NfcTagLost, assertIs<ChipReadEnding.Partial>(r.ending).stoppedBy)
        }

    @Test
    fun k06_mseSetKatAnsweredOnlyAfter18s_thePlatformLimitOfFiveSecondsFiresAndTheReadRecovers() =
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
    fun k06_mseSetKatNeverAnswered_thePlatformLimitFiresAndTheReadRecovers() =
        simTest { flow ->
            script.fault(ChipFaultAction.Silence, match = Shapes.mseSetKat)
            recovered(read(deviceOptions(flow)))
        }

    @Test
    fun k09_everyNewLinkLosesItsFirstCommand_noRestartUsedEndsFailedWithTheLossAfterTheReturnWait() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(Shapes.LOSS_AFTER_MILLIS), times = 99) { it.indexInLink == 0 }
            val r = read(deviceOptions(flow))
            val ending = assertIs<ChipReadEnding.Failed>(r.ending)
            assertEquals(NfcTagLost, ending.error)
            assertEquals(1, ending.attempts, "no try reached access: no attempt, no restart used")
            assertTrue(r.states.none { it is ChipReadState.LinkLost })
            // One try every 1.34 s (300 pause, 400 settle, 640 loss) for 15 s from the first loss.
            assertEquals(12, script.links)
            assertEquals((1..12).toList(), r.states.filterIsInstance<ChipReadState.NoAnswerYet>().map { it.tries })
        }

    // --- Before access: a short time limit, and tries that cost no restart (TES-346) ----------------

    /** State names with the protocol or the tries, repeats folded. */
    private fun namesWithValues(r: SimRecorder): List<String> =
        r.states
            .map {
                when (it) {
                    is ChipReadState.OpeningAccess -> "OpeningAccess(${it.protocol})"
                    is ChipReadState.NoAnswerYet -> "NoAnswerYet(${it.tries})"
                    else -> it::class.simpleName ?: "?"
                }
            }.fold(mutableListOf<String>()) { acc, name -> acc.also { if (it.lastOrNull() != name) it += name } }

    private fun AndroidSim.foundAt(r: SimRecorder): Long = assertNotNull(r.firstAt<ChipReadState.DocumentFound>())

    /**
     * The device case: the chip is found but never answers its first command (EF.CardAccess). Before,
     * three tries of 5 s each spent the restart budget and the read ended after about 18 s with
     * LinkLost twice. Now each try waits 1.5 s, costs no restart, and the tries go on for the 15 s
     * return wait counted from the first one: 8 tries, one every 2.2 s (300 pause, 400 settle,
     * 1500 limit), then Failed with the lost link 17.3 s after the document was found.
     */
    @Test
    fun noAnswer_a_firstCommandNeverAnswered_eightShortTriesThenFailedAfterTheReturnWait() =
        simTest { flow ->
            script.fault(ChipFaultAction.Silence, times = 99) { it.indexInLink == 0 }
            val events = java.util.Collections.synchronizedList(mutableListOf<TelemetryEvent>())
            val sink =
                object : TelemetrySink {
                    override fun record(event: TelemetryEvent) {
                        events += event
                    }
                }
            val (read, r) = start(deviceOptions(flow), telemetry = sink)
            step(1_000)
            read.onTagDiscovered(AndroidSim.aTag())
            runUntilEnded(r)
            assertNull(r.checkPromises())
            val ending = assertIs<ChipReadEnding.Failed>(r.ending)
            assertEquals(NfcTagLost, ending.error)
            assertEquals(1, ending.attempts)
            assertEquals(0, ending.progress.percent)
            assertEquals(8, script.links, "8 tries")
            assertEquals(8, script.exchanges.size, "one unanswered command per try")
            assertTrue(script.exchanges.all { it.command.indexInLink == 0 })
            val found = foundAt(r)
            assertEquals(found + 17_300, r.endings.single().atMillis, "ended 17.3 s after the document was found")
            // Every try waited the 1.5 s limit, never the 5 s one.
            assertTrue(channels.all { it.timeouts == listOf(1_500) }, "${channels.map { it.timeouts }}")
            // (e) What the app saw, in order: found once, then "no answer yet" with a growing count.
            assertEquals(
                listOf("Starting", "WaitingForDocument", "DocumentFound", "Connecting", "OpeningAccess") + List(8) { "NoAnswerYet" },
                r.states.map { it::class.simpleName },
            )
            assertEquals((1..8).toList(), r.states.filterIsInstance<ChipReadState.NoAnswerYet>().map { it.tries })
            val noAnswerTimes = r.updates.filter { it.value.state is ChipReadState.NoAnswerYet }.map { it.atMillis - found }
            assertEquals(listOf(1_900L, 4_100, 6_300, 8_500, 10_700, 12_900, 15_100, 17_300), noAnswerTimes)
            assertTrue(r.updates.all { it.value.attempt == 1 && it.value.maxAttempts == 3 })
            // Each failed try is still reported to the sink, with the whole attempt's step event.
            val steps = events.map { it as ChipReadEvent }
            assertEquals(8, steps.count { it.step == ChipReadStep.READ && it.outcome == ChipReadStepOutcome.FAILED })
            assertTrue(steps.count { it.step != ChipReadStep.READ && it.outcome == ChipReadStepOutcome.FAILED } >= 8, "$steps")
        }

    /**
     * Silent twice, then the chip answers: the read completes, and both restarts are still there,
     * shown by two later losses in DG2 that the read still resumes from.
     */
    @Test
    fun noAnswer_b_silentTwiceThenAnswers_theFullRestartBudgetIsLeftForLaterLosses() =
        simTest { flow ->
            script.fault(ChipFaultAction.Silence, times = 2) { it.indexInLink == 0 }
            script.fault(ChipFaultAction.LoseLink(10), times = 2, match = Shapes.midDg2)
            val r = read(deviceOptions(flow))
            assertDeviceChecksPass(r.ending)
            assertEquals(3, r.ending.attempts)
            assertEquals(listOf(2, 1), r.states.filterIsInstance<ChipReadState.LinkLost>().map { it.restartsLeft })
            assertEquals(5, script.links, "2 unanswered tries, then 3 attempts")
            // (e) What the app saw: found, "no answer yet" twice, then the read goes on.
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
            val found = foundAt(r)
            val noAnswerTimes = r.updates.filter { it.value.state is ChipReadState.NoAnswerYet }.map { it.atMillis - found }
            assertEquals(listOf(1_900L, 4_100), noAnswerTimes)
            assertEquals(
                1,
                r.updates
                    .first { it.value.state is ChipReadState.LinkLost }
                    .value.attempt,
                "the first loss belongs to attempt 1",
            )
            assertTrue(r.states.drop(8).none { it is ChipReadState.NoAnswerYet })
        }

    /** Silence once the key is used (EXTERNAL AUTHENTICATE) still spends the budget, with the general 5 s limit (regression). */
    @Test
    fun noAnswer_c_silenceAtExternalAuthenticate_spendsTheBudgetWithTheFiveSecondLimit() =
        simTest { flow ->
            script.fault(ChipFaultAction.Silence, times = 99) { it.ins == 0x82 }
            val r = read(deviceOptions(flow))
            val ending = assertIs<ChipReadEnding.Failed>(r.ending)
            assertEquals(NfcTagLost, ending.error)
            assertEquals(3, ending.attempts)
            assertEquals(3, script.links)
            assertEquals(listOf(2, 1), r.states.filterIsInstance<ChipReadState.LinkLost>().map { it.restartsLeft })
            assertTrue(r.states.none { it is ChipReadState.NoAnswerYet })
            val authentications = script.exchanges.filter { it.command.ins == 0x82 }
            val losses = r.updates.filter { it.value.state is ChipReadState.LinkLost }
            assertEquals(
                authentications.take(2).map { it.sentAtMillis + 5_000 },
                losses.map { it.atMillis },
                "EXTERNAL AUTHENTICATE waits 5 s",
            )
            assertTrue(channels.all { it.timeouts == listOf(1_500, 5_000) }, "${channels.map { it.timeouts }}")
        }

    /**
     * Slow but legal PACE steps (3 s each, all four GENERAL AUTHENTICATE steps) are never cut by the
     * 1.5 s limit: the general limit is set right before the first of them. MSE:Set AT, before it,
     * answers in 1 s, within the short limit.
     */
    @Test
    fun noAnswer_d_paceStepsOfThreeSeconds_areNotCutByTheLimitBeforeAccess() =
        simTest(build = { keys -> FakeReadFlowChip.build(chipCrypto(), keys, FlowAccess.PACE_ONLY) }) { flow ->
            val setAt: (ChipCommand) -> Boolean = { it.ins == 0x22 && it.apdu?.p2 == 0xA4 }
            val generalAuthenticate: (ChipCommand) -> Boolean = { it.ins == 0x86 }
            script.fault(ChipFaultAction.Delay(1_000), match = setAt)
            script.fault(ChipFaultAction.Delay(3_000), times = 4, match = generalAuthenticate)
            val r = read(ChipReadOptions(FakeReadFlowChip.mrzKey(), setOf(1, 2), listOf(flow.anchor), CHECK_TIME))
            val read = assertIs<ChipReadEnding.Complete>(r.ending, "${r.ending}").read
            assertEquals(ChipAccessProtocol.PACE, read.access.protocol)
            assertEquals(1, r.ending.attempts)
            assertEquals(1, script.links)
            assertTrue(r.states.none { it is ChipReadState.NoAnswerYet || it is ChipReadState.LinkLost }, "${r.stateNames}")
            assertEquals(listOf(1_500, 5_000), channels.single().timeouts)
            assertEquals(4, script.exchanges.count { it.reply.delayMillis == 3_000L })
            assertEquals(1, script.exchanges.count { it.reply.delayMillis == 1_000L })
        }

    /**
     * The device case (TES-346): the chip answers the plain commands (EF.CardAccess not found,
     * EF.DIR, the application), then has no answer for BAC's GET CHALLENGE. Before, the read waited
     * 5 s and spent a restart. Now GET CHALLENGE is before the key is used: the try waits 1.5 s,
     * says NoAnswerYet, costs no restart, and the next try completes.
     */
    @Test
    fun noAnswer_f_getChallengeSilentOnceAfterThePlainCommands_triesAgainFreeAndCompletes() =
        simTest { flow ->
            script.fault(ChipFaultAction.Silence) { it.ins == 0x84 }
            val r = read(deviceOptions(flow))
            assertDeviceChecksPass(r.ending)
            assertEquals(1, r.ending.attempts)
            assertEquals(2, script.links)
            assertTrue(r.states.none { it is ChipReadState.LinkLost }, "${r.stateNames}")
            assertEquals(listOf(1), r.states.filterIsInstance<ChipReadState.NoAnswerYet>().map { it.tries })
            val challenge = script.exchanges.first { it.command.ins == 0x84 }
            assertTrue(challenge.command.indexInLink > 0, "plain commands were answered first")
            val noAnswer = r.updates.single { it.value.state is ChipReadState.NoAnswerYet }
            assertEquals(challenge.sentAtMillis + 1_500, noAnswer.atMillis, "GET CHALLENGE waits 1.5 s, not 5 s")
            // The general limit is set only on the link that went past GET CHALLENGE.
            assertEquals(listOf(listOf(1_500), listOf(1_500, 5_000)), channels.map { it.timeouts })
            assertEquals(
                listOf(
                    "Starting",
                    "WaitingForDocument",
                    "DocumentFound",
                    "Connecting",
                    "OpeningAccess(null)",
                    "OpeningAccess(BAC)",
                    "NoAnswerYet(1)",
                    "OpeningAccess(BAC)",
                    "ReadingFile",
                ),
                namesWithValues(r).take(9),
            )
            val fractions = r.updates.map { it.value.progress.fraction }
            assertEquals(fractions.sorted(), fractions, "progress never goes back")
            assertEquals(r.ending.attempts, r.updates.maxOf { it.value.attempt })
            assertEquals(TOTAL_F, r.endings.single().atMillis - foundAt(r), "virtual time from found to complete")
        }

    /** GET CHALLENGE never answered: short free tries for the return wait, then Failed, no restart used. */
    @Test
    fun noAnswer_g_getChallengeNeverAnswered_shortTriesUntilTheReturnWaitThenFailed() =
        simTest { flow ->
            script.fault(ChipFaultAction.Silence, times = 99) { it.ins == 0x84 }
            val r = read(deviceOptions(flow))
            val ending = assertIs<ChipReadEnding.Failed>(r.ending)
            assertEquals(NfcTagLost, ending.error)
            assertEquals(1, ending.attempts)
            assertTrue(r.states.none { it is ChipReadState.LinkLost })
            assertEquals(0, script.exchanges.count { it.command.ins == 0x82 }, "the key was never used")
            assertTrue(channels.all { it.timeouts == listOf(1_500) }, "${channels.map { it.timeouts }}")
            val tries = r.states.filterIsInstance<ChipReadState.NoAnswerYet>().map { it.tries }
            assertEquals((1..script.links).toList(), tries)
            assertEquals(TRIES_G, script.links)
            val challenges = script.exchanges.filter { it.command.ins == 0x84 }
            val seen = r.updates.filter { it.value.state is ChipReadState.NoAnswerYet }.map { it.atMillis }
            assertEquals(challenges.map { it.sentAtMillis + 1_500 }, seen, "each try waits the 1.5 s limit at GET CHALLENGE")
            // The protocol shows on the first try only; then NoAnswerYet holds until the end, with
            // no OpeningAccess in between.
            assertEquals(
                listOf("Starting", "WaitingForDocument", "DocumentFound", "Connecting", "OpeningAccess(null)", "OpeningAccess(BAC)") +
                    (1..TRIES_G).map { "NoAnswerYet($it)" },
                namesWithValues(r),
            )
            assertEquals(END_G, r.endings.single().atMillis - foundAt(r), "virtual time from found to the ending")
        }

    /** A GET CHALLENGE answered with an error status word is an answer, not silence: as before. */
    @Test
    fun noAnswer_h_getChallengeAnsweredWithAnErrorStatusWord_isARefusalAsBefore() =
        simTest { flow ->
            script.fault(ChipFaultAction.Status(0x6982)) { it.ins == 0x84 }
            val r = read(deviceOptions(flow))
            val error = assertIs<ChipReadEnding.KeyNotAccepted>(r.ending, "${r.ending}").error
            assertEquals(ChipAccessProtocol.BAC, error.protocol)
            assertEquals(0x6982, error.statusWord)
            assertEquals(1, script.links)
            assertTrue(r.states.none { it is ChipReadState.NoAnswerYet || it is ChipReadState.LinkLost })
            assertEquals(0, script.exchanges.count { it.command.ins == 0x82 })
        }

    /** PACE: MSE:Set AT silent once is before the key is used: 1.5 s, a free try, then complete. */
    @Test
    fun noAnswer_i_mseSetAtSilentOnce_triesAgainFreeAndCompletes() =
        simTest(build = { keys -> FakeReadFlowChip.build(chipCrypto(), keys, FlowAccess.PACE_ONLY) }) { flow ->
            script.fault(ChipFaultAction.Silence) { it.ins == 0x22 && it.apdu?.p2 == 0xA4 }
            val r = read(ChipReadOptions(FakeReadFlowChip.mrzKey(), setOf(1, 2), listOf(flow.anchor), CHECK_TIME))
            val read = assertIs<ChipReadEnding.Complete>(r.ending, "${r.ending}").read
            assertEquals(ChipAccessProtocol.PACE, read.access.protocol)
            assertEquals(1, r.ending.attempts)
            assertEquals(2, script.links)
            assertTrue(r.states.none { it is ChipReadState.LinkLost })
            assertEquals(listOf(1), r.states.filterIsInstance<ChipReadState.NoAnswerYet>().map { it.tries })
            val setAt = script.exchanges.first { it.command.ins == 0x22 }
            assertEquals(setAt.sentAtMillis + 1_500, r.updates.single { it.value.state is ChipReadState.NoAnswerYet }.atMillis)
            assertEquals(listOf(listOf(1_500), listOf(1_500, 5_000)), channels.map { it.timeouts })
        }

    /** PACE: the first GENERAL AUTHENTICATE silent is past the boundary: 5 s, and a restart is spent. */
    @Test
    fun noAnswer_j_firstGeneralAuthenticateSilentOnce_spendsARestartWithTheFiveSecondLimit() =
        simTest(build = { keys -> FakeReadFlowChip.build(chipCrypto(), keys, FlowAccess.PACE_ONLY) }) { flow ->
            script.fault(ChipFaultAction.Silence) { it.ins == 0x86 }
            val r = read(ChipReadOptions(FakeReadFlowChip.mrzKey(), setOf(1, 2), listOf(flow.anchor), CHECK_TIME))
            assertIs<ChipReadEnding.Complete>(r.ending, "${r.ending}")
            assertEquals(2, r.ending.attempts)
            assertEquals(listOf(2), r.states.filterIsInstance<ChipReadState.LinkLost>().map { it.restartsLeft })
            assertTrue(r.states.none { it is ChipReadState.NoAnswerYet })
            val first = script.exchanges.first { it.command.ins == 0x86 }
            assertEquals(first.sentAtMillis + 5_000, r.updates.first { it.value.state is ChipReadState.LinkLost }.atMillis)
        }

    /** A plain command slower than the limit before access counts as unanswered; the next try answers. */
    @Test
    fun noAnswer_d_aPlainCommandSlowerThanTheLimitBeforeAccess_isTriedAgainWithNoRestart() =
        simTest { flow ->
            script.fault(ChipFaultAction.Delay(1_600), match = Shapes.plainSelectOfTheApplication)
            triedAgainBeforeAccess(read(deviceOptions(flow)))
        }

    /** The app's own limit before access is used, and never more than its general limit. */
    @Test
    fun noAnswer_theLimitBeforeAccessFollowsTheSettings() =
        simTest { flow ->
            read(deviceOptions(flow), NfcReadSettings(commandTimeoutBeforeAccessMillis = 900))
            assertEquals(listOf(900, 5_000), channels.single().timeouts)
        }

    @Test
    fun noAnswer_aLimitBeforeAccessAboveTheGeneralLimit_givesWayToIt() =
        simTest { flow ->
            read(deviceOptions(flow), NfcReadSettings(commandTimeoutMillis = 4_000, commandTimeoutBeforeAccessMillis = 9_000))
            // Equal limits: the transport sets the value at connect and has nothing to change.
            assertEquals(listOf(4_000), channels.single().timeouts)
        }

    @Test
    fun k10_recordedTimings_theReadInFourToSixSeconds() =
        simTest { flow ->
            read(deviceOptions(flow))
            assertTrue(commandSpan(1) in 4_000..5_800)
        }

    @Test
    fun k12_dg1Of95Bytes_parses() =
        simTest(build = { keys -> DeviceChipProfile.build(chipCrypto(), keys, td1 = true) }) { flow ->
            val read = assertIs<ChipReadEnding.Complete>(read(deviceOptions(flow)).ending).read
            assertTrue(read.dg1 != null)
        }

    // --- Group I ----------------------------------------------------------------------------------

    @Test
    fun i01_chipThreeTimesSlower_noLossAndTheSameResult() =
        simTest(latencyFactor = 3) { flow ->
            val r = read(deviceOptions(flow))
            assertDeviceChecksPass(r.ending)
            assertEquals(1, r.ending.attempts)
            assertTrue(commandSpan(1) in 12_000..17_400)
        }

    @Test
    fun i02_oneCommandTakes5100ms_thePlatformTimesOutAndTheReadRecovers() =
        simTest { flow ->
            script.fault(ChipFaultAction.Delay(5_100), match = Shapes.firstProtectedSelect)
            recovered(read(deviceOptions(flow)))
        }

    // --- Group H through the Android entry point ---------------------------------------------------

    @Test
    fun h06_lossMidDg2_theSameChipBack_keepsEarlierFilesAndContinuesDg2() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(10), match = Shapes.midDg2)
            val ending = assertIs<ChipReadEnding.Complete>(read(deviceOptions(flow)).ending)
            assertDeviceChecksPass(ending)
            assertEquals(ChipResumeOutcome.SAME_SECURITY_OBJECT, ending.read.resume)
            assertTrue(
                ending.read.files
                    .single { it.dataGroup == 2 }
                    .continuedAt != null,
            )
        }

    // --- Refusals: never a new attempt (DES 5.1 rule 2), review item 17 ---------------------------

    @Test
    fun g29_wrongKey_keyNotAcceptedNoRestartAndTheLinkClosed() =
        simTest { flow ->
            val r = read(ChipReadOptions(FakeReadFlowChip.wrongMrzKey(), setOf(1, 2), listOf(flow.anchor), CHECK_TIME))
            val ending = assertIs<ChipReadEnding.KeyNotAccepted>(r.ending)
            assertEquals(ChipAccessDenied.Reason.KEY_NOT_MATCHED, ending.error.reason)
            assertEquals(1, script.links)
            assertEquals(1, script.exchanges.count { it.command.ins == 0x82 })
            assertEquals(1, channels.single().closeCalls)
        }

    @Test
    fun h18_secureMessagingErrorMidRead_partialAndNoNewAttempt() =
        simTest { flow ->
            script.fault(ChipFaultAction.Status(0x6988), match = Shapes.midDg2)
            val r = read(deviceOptions(flow))
            assertIs<ChipSecureMessagingLost>(assertIs<ChipReadEnding.Partial>(r.ending).stoppedBy)
            assertEquals(1, script.links)
            assertTrue(r.states.none { it is ChipReadState.LinkLost })
        }

    @Test
    fun h18_badMacMidRead_partialAndNoNewAttempt() =
        simTest { flow ->
            script.fault(ChipFaultAction.Arm { flow.sm!!.corruptNextResponseMac = true }, match = Shapes.midDg2)
            val r = read(deviceOptions(flow))
            assertIs<ChipSecureMessagingLost>(assertIs<ChipReadEnding.Partial>(r.ending).stoppedBy)
            assertEquals(1, script.links)
        }

    @Test
    fun h18_malformedFileAfterALoss_noFurtherAttempt() =
        simTest(build = { keys ->
            FakeReadFlowChip.build(
                chipCrypto(),
                keys,
                FlowAccess.BAC,
                extraApplicationFiles =
                    mapOf(0x0101 to byteArrayOf(0x61, 0x7F, 0x01)),
            )
        }) { flow ->
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
    fun g19_waitForReturnZero_aLossEndsTheReadAtOnce() =
        simTest { flow ->
            script.fault(ChipFaultAction.LoseLink(10), match = Shapes.midDg2)
            val r = read(deviceOptions(flow), NfcReadSettings(waitForReturnMillis = 0))
            assertEquals(NfcTagLost, assertIs<ChipReadEnding.Partial>(r.ending).stoppedBy)
            assertEquals(1, script.links)
        }

    @Test
    fun g23_waitForDocumentLongMaxValue_meansNoLimitAndDoesNotOverflow() =
        simTest { flow ->
            val (read, recorder) = start(deviceOptions(flow), NfcReadSettings(waitForDocumentMillis = Long.MAX_VALUE))
            step(30_000)
            assertTrue(recorder.endings.isEmpty(), "the read still waits: ${recorder.endings}")
            read.onTagDiscovered(AndroidSim.aTag())
            runUntilEnded(recorder)
            assertDeviceChecksPass(recorder.ending)
        }

    @Test
    fun g19_waitForReturnLongMaxValue_doesNotOverflow() =
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
    fun g07_openDecision_activityPausedTenSecondsMidRead_currentlyResumesAndCompletes() =
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
    fun g20_openDecision_screenLockedLongerThanTheReturnWait_currentlyPartialWithTheLoss() =
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
    fun g04_openDecision_readerOptionOffMidRead_currentlyEndsWithTheLossAfterTheWait() =
        simTest { flow ->
            script.fault(ChipFaultAction.Arm { paused = true }, match = Shapes.firstProtectedSelect)
            val r = read(deviceOptions(flow))
            assertEquals(NfcTagLost, assertIs<ChipReadEnding.Partial>(r.ending).stoppedBy)
        }

    // --- The telemetry sink through the easy entry point --------------------------------------------

    @Test
    fun telemetry_aSinkInTheSettingsHearsEveryStepThroughTheEntryPointWithNoDocumentData() =
        simTest { flow ->
            val events = java.util.Collections.synchronizedList(mutableListOf<TelemetryEvent>())
            val sink =
                object : TelemetrySink {
                    override fun record(event: TelemetryEvent) {
                        events += event
                    }
                }
            val (read, recorder) = start(deviceOptions(flow), telemetry = sink)
            step(1_000)
            read.onTagDiscovered(AndroidSim.aTag())
            runUntilEnded(recorder)
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
            assertNoDocumentData(steps.joinToString())
        }
}
