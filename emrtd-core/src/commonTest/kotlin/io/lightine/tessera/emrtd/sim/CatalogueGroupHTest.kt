package io.lightine.tessera.emrtd.sim

import io.lightine.tessera.emrtd.ChipCheckKind
import io.lightine.tessera.emrtd.ChipFileContext
import io.lightine.tessera.emrtd.ChipRead
import io.lightine.tessera.emrtd.ChipReadAttemptOutcome
import io.lightine.tessera.emrtd.ChipReadCoordinator
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipReader
import io.lightine.tessera.emrtd.ChipResumeOutcome
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.emrtd.fakechip.FakeAttemptLink
import io.lightine.tessera.emrtd.fakechip.FakeChip
import io.lightine.tessera.emrtd.fakechip.FakeCommandHandler
import io.lightine.tessera.emrtd.fakechip.FakeFiles
import io.lightine.tessera.emrtd.fakechip.FakeLinkLoss
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.fakechip.SimRecorder
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.pa.FakeGroups
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.emrtd.sim.SimDrive.assertHashPassed
import io.lightine.tessera.emrtd.sim.SimDrive.drive
import io.lightine.tessera.emrtd.sim.SimDrive.driveFlow
import io.lightine.tessera.emrtd.sim.SimDrive.options
import io.lightine.tessera.emrtd.sim.SimDrive.withChips
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.errors.ChipFileNotFound
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.vocabulary.CheckOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Catalogue group H (link loss and resume, every stage), TES-346: whole reads through
 * [ChipReadCoordinator] on the scripted chip, the document back on a new link after each loss.
 * Expected behaviour from the lifecycle design, section 5.2 (Resume) and 5.1 (access attempts).
 */
class CatalogueGroupHTest {
    private val dg1 = 0x0101
    private val dg2 = 0x0102
    private val dg11 = 0x010B

    private fun bacChip(
        c: ChipCrypto,
        keys: TestKeys,
        handlers: List<FakeCommandHandler> = emptyList(),
    ) = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, caInApplication = true, extraHandlers = handlers)

    private fun paceChip(
        c: ChipCrypto,
        keys: TestKeys,
        handlers: List<FakeCommandHandler> = emptyList(),
    ) = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, caInMasterFile = true, extraHandlers = handlers)

    private fun fileIds(read: ChipRead): List<String> = read.files.map { "${it.fileId.toString(16)}/${it.inMasterFile}" }.sorted()

    /** Every file the read returns is byte for byte the chip's file. */
    private fun assertFilesAreTheChips(
        flow: FlowChip,
        read: ChipRead,
        what: String,
    ) {
        for (file in read.files) {
            val source =
                if (file.inMasterFile) {
                    flow.chip.files.masterFiles
                } else {
                    flow.chip.files.applications
                        .getValue(LdsIds.LDS1_AID.toList())
                }
            assertContentEquals(source.getValue(file.fileId), file.bytes(), "$what: file ${file.fileId.toString(16)}")
        }
    }

    private fun checkSummary(read: ChipRead): List<String> = read.checks.map { "${it.kind}=${it.outcome}" }

    // --- H-01: a loss at every command index ------------------------------------------------------

    private suspend fun sweep(build: (List<FakeCommandHandler>) -> FlowChip) {
        val clean = build(emptyList())
        val reference = driveFlow(clean).complete
        val commands = clean.chip.received.size
        assertTrue(commands > 10, "the reference read sent $commands commands")
        // The first command of the access protocol: BAC's GET CHALLENGE or PACE's MSE:Set AT.
        val firstAccess = clean.chip.received.indexOfFirst { (it[1].toInt() and 0xFF) in setOf(0x84, 0x22) }
        assertTrue(firstAccess > 0, "plain commands come before access")
        for (k in 0 until commands) {
            val flow = build(emptyList())
            flow.chip.loseTagAtCommand = k
            val driven = driveFlow(flow)
            val read = assertIs<ChipReadEnding.Complete>(driven.ending, "loss at command $k: ${driven.ending}").read
            // A loss before access costs no attempt (the same attempt is tried again); after it, a
            // restart (TES-346).
            assertEquals(if (k < firstAccess) 1 else 2, driven.ending.attempts, "loss at command $k")
            assertEquals(k < firstAccess, driven.recorder.states.any { it is ChipReadState.NoAnswerYet }, "loss at command $k")
            assertEquals(k >= firstAccess, driven.recorder.states.any { it is ChipReadState.LinkLost }, "loss at command $k")
            assertEquals(fileIds(reference), fileIds(read), "loss at command $k: the files differ")
            assertFilesAreTheChips(flow, read, "loss at command $k")
            assertEquals(checkSummary(reference), checkSummary(read), "loss at command $k: the checks differ")
        }
    }

    @Test
    fun h01_lossAtEveryCommandOfABacRead_completesWithTheSameFilesAndChecks() =
        runTest { withChips { c, keys -> sweep { bacChip(c, keys, it) } } }

    @Test
    fun h01_lossAtEveryCommandOfAPaceRead_completesWithTheSameFilesAndChecks() =
        runTest { withChips { c, keys -> sweep { paceChip(c, keys, it) } } }

    // --- H-05: master-file files are read again in full ----------------------------------------------

    @Test
    fun h05_lossInTheMiddleOfEfCardSecurity_readsItAgainInFullInTheNextAttempt() =
        runTest {
            withChips { c, keys ->
                val loss =
                    FakeLinkLoss { chip, cmd ->
                        SimDrive.readsMasterFile(chip, cmd, 0x011D) && SimDrive.isPlainRead(cmd) &&
                            SimDrive.offset(cmd) > 0
                    }
                val flow = paceChip(c, keys, listOf(loss))
                val driven = driveFlow(flow)
                val read = driven.complete
                assertEquals(1, loss.fired)
                assertEquals(2, driven.ending.attempts)
                val cardSecurity = assertNotNull(read.masterFile(0x011D))
                assertEquals(2, cardSecurity.attempt)
                assertNull(cardSecurity.continuedAt, "a master-file file is never continued")
                assertContentEquals(
                    flow.chip.files.masterFiles
                        .getValue(0x011D),
                    cardSecurity.bytes(),
                )
                assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.ChipAuthentication(ChipFileContext.MASTER_FILE)))
            }
        }

    @Test
    fun h05_lossAtTheReadOfEfCardAccess_readsItAgainInTheSameAttempt() =
        runTest {
            withChips { c, keys ->
                val loss = FakeLinkLoss { chip, cmd -> SimDrive.readsMasterFile(chip, cmd, LdsIds.EF_CARD_ACCESS) }
                val flow = paceChip(c, keys, listOf(loss))
                val driven = driveFlow(flow)
                val read = driven.complete
                assertEquals(1, loss.fired)
                // Before access: no restart used, the same attempt tried again (TES-346).
                assertEquals(1, driven.ending.attempts)
                assertEquals(1, read.masterFile(LdsIds.EF_CARD_ACCESS)!!.attempt)
                assertNull(read.masterFile(LdsIds.EF_CARD_ACCESS)!!.continuedAt)
                assertTrue(read.access.cardAccessRead)
                assertEquals(ChipResumeOutcome.NOT_RESUMED, read.resume, "the loss came before access: no restart, nothing resumed")
            }
        }

    // --- H-07: continuing above 32 767 bytes ------------------------------------------------------------

    @Test
    fun h07_lossInADg2AboveTheB0Boundary_continuesWithB1AboveTheBoundaryAndTheHashVouches() =
        runTest {
            withChips { c, keys ->
                var b1Reads = 0
                val loss =
                    FakeLinkLoss { chip, cmd ->
                        if (cmd.ins == ChipCommands.INS_READ_BINARY_ODD && chip.currentEf == dg2) b1Reads++
                        cmd.ins == ChipCommands.INS_READ_BINARY_ODD && chip.currentEf == dg2 && b1Reads == 3
                    }
                val oddInsLog = OddInsLog()
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        dg2Bytes = 40_000,
                        extraHandlers = listOf(loss, oddInsLog, FakeFiles),
                    )
                val driven = driveFlow(flow)
                val read = driven.complete
                assertEquals(1, loss.fired)
                val dg2File = read.dataGroup(2)!!
                val continuedAt = assertNotNull(dg2File.continuedAt, "DG2 continues from where it stopped")
                assertTrue(continuedAt > 0x7FFF, "continued at $continuedAt, above the B0 boundary")
                assertEquals(2, dg2File.attempt)
                assertContentEquals(flow.dataGroups.getValue(2), dg2File.bytes())
                assertHashPassed(read, 1, 2)
                assertEquals(ChipResumeOutcome.SAME_SECURITY_OBJECT, read.resume)
                // Attempt 2 reads DG2 only with B1 (Part 10, 3.6.3.1 Note 1): no B0 read of DG2 in it.
                assertTrue(oddInsLog.b0OfDg2InLink(2) == 0, "B0 reads of DG2 after the resume: ${oddInsLog.b0OfDg2InLink(2)}")
                assertTrue(oddInsLog.b1OfDg2InLink(2) > 0)
            }
        }

    /** Counts B0 and B1 reads of DG2 per link (the chip powers up at each link). */
    private class OddInsLog : FakeCommandHandler {
        private var link = 1
        private val b0 = HashMap<Int, Int>()
        private val b1 = HashMap<Int, Int>()

        override fun powerOff() {
            link++
        }

        override fun handle(
            chip: FakeChip,
            command: CommandApdu,
        ): ResponseApdu? {
            if (chip.currentEf == 0x0102 && chip.currentApplication != null) {
                if (command.ins == ChipCommands.INS_READ_BINARY && command.p1 and 0x80 == 0) b0[link] = (b0[link] ?: 0) + 1
                if (command.ins == ChipCommands.INS_READ_BINARY_ODD) b1[link] = (b1[link] ?: 0) + 1
            }
            return null
        }

        // FakeAttemptLink powers the chip up before the first link too: link 1 is counted as 2.
        fun b0OfDg2InLink(n: Int): Int = b0[n + 1] ?: 0

        fun b1OfDg2InLink(n: Int): Int = b1[n + 1] ?: 0
    }

    // --- H-09: loss during active authentication -----------------------------------------------------

    @Test
    fun h09_lossDuringActiveAuthentication_runsItAgainInTheNewSessionAndItPasses() =
        runTest {
            withChips { c, keys ->
                val loss = FakeLinkLoss { _, cmd -> cmd.ins == 0x88 }
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, activeAuthentication = true, extraHandlers = listOf(loss))
                val driven = driveFlow(flow)
                val read = driven.complete
                assertEquals(1, loss.fired)
                assertEquals(2, driven.ending.attempts)
                assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.ActiveAuthentication))
                val internalAuthenticates = flow.chip.received.count { (it[1].toInt() and 0xFF) == 0x88 }
                assertEquals(2, internalAuthenticates, "INTERNAL AUTHENTICATE sent once per attempt")
            }
        }

    // --- H-24: access attempts across restarts -------------------------------------------------------

    @Test
    fun h24_accessAttemptsAcrossThreeRestarts_atMostFourExternalAuthenticatesAllWithAKeyNoChipRefused() =
        runTest {
            withChips { c, keys ->
                // A loss at every EXTERNAL AUTHENTICATE of the first three attempts, then it goes through.
                val loss = FakeLinkLoss(times = 3) { _, cmd -> cmd.ins == 0x82 }
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraHandlers = listOf(loss))
                val driven = driveFlow(flow, LinkLossPolicy.Resume(3))
                driven.complete
                assertEquals(4, driven.ending.attempts)
                assertEquals(4, flow.chip.received.count { (it[1].toInt() and 0xFF) == 0x82 })
                assertEquals(0, flow.bac!!.refused)

                // Every attempt loses the link there: the fourth loss ends the read, with no fifth try.
                val always = FakeLinkLoss(times = 10) { _, cmd -> cmd.ins == 0x82 }
                val flow2 = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraHandlers = listOf(always))
                val ended = driveFlow(flow2, LinkLossPolicy.Resume(3))
                val failed = assertIs<ChipReadEnding.Failed>(ended.ending)
                assertEquals(NfcTagLost, failed.error)
                assertEquals(4, failed.attempts)
                assertEquals(4, flow2.chip.received.count { (it[1].toInt() and 0xFF) == 0x82 }, "at most 1 + maxRestarts")
            }
        }

    @Test
    fun h24_accessAttemptsAcrossThreeRestarts_atMostFourPaceMutualAuthenticationsWithAnUnrefusedKey() =
        runTest {
            withChips { c, keys ->
                // PACE step 4 (mutual authentication) is the last GENERAL AUTHENTICATE: not chained (CLA 00).
                val isStep4 = { cmd: CommandApdu -> cmd.ins == 0x86 && cmd.cla == 0x00 }
                val always = FakeLinkLoss(times = 10) { _, cmd -> isStep4(cmd) }
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, extraHandlers = listOf(always))
                val ended = driveFlow(flow, LinkLossPolicy.Resume(3))
                assertIs<ChipReadEnding.Failed>(ended.ending)
                assertEquals(4, ended.ending.attempts)
                assertEquals(4, always.fired, "one PACE run per attempt, at most 1 + maxRestarts")
            }
        }

    // --- H-23: cancel or system end while waiting after a loss ---------------------------------------

    @Test
    fun h23_cancelOrSystemEndWhileWaitingForTheDocument_oneEndingAndNoNewAttempt() =
        runTest {
            withChips { c, keys ->
                for (how in listOf("cancel", "system")) {
                    val loss = FakeLinkLoss { chip, cmd -> SimDrive.readsAppFile(chip, cmd, dg2) && SimDrive.offset(cmd) > 0 }
                    val flow = bacChip(c, keys, listOf(loss))
                    val recorder = SimRecorder { 0L }
                    val coordinator = ChipReadCoordinator(options(flow), LinkLossPolicy.Resume(2), recorder, ChipReader(NoOpTelemetrySink))
                    assertTrue(coordinator.start())
                    coordinator.waiting(0)
                    coordinator.documentFound()
                    coordinator.connecting()
                    assertEquals(ChipReadAttemptOutcome.LINK_LOST, coordinator.read(FakeAttemptLink(flow.chip)))
                    val sentBefore = flow.chip.received.size
                    if (how == "cancel") {
                        assertTrue(coordinator.cancel(ChipReadEnding.Cancelled.By.APP))
                    } else {
                        assertTrue(
                            coordinator.endedBySystem(
                                io.lightine.tessera.types.errors.NfcUnavailable(
                                    io.lightine.tessera.types.errors.NfcUnavailable.Reason.ENDED_BY_SYSTEM,
                                ),
                            ),
                        )
                    }
                    // Whatever the platform does next changes nothing and sends nothing.
                    assertFalse(coordinator.documentNotReturned())
                    coordinator.documentFound()
                    assertEquals(ChipReadAttemptOutcome.ENDED, coordinator.read(FakeAttemptLink(flow.chip)))
                    assertEquals(sentBefore, flow.chip.received.size, "$how: no command after the ending")
                    assertNull(recorder.checkPromises())
                    val ending = recorder.ending
                    if (how == "cancel") assertIs<ChipReadEnding.Cancelled>(ending) else assertIs<ChipReadEnding.EndedBySystem>(ending)
                    assertEquals(1, ending.attempts)
                    assertEquals(1, recorder.states.count { it is ChipReadState.LinkLost }, "no LinkLost after the ending")
                }
            }
        }

    // --- H-25: a loss after the last command ------------------------------------------------------------

    @Test
    fun h25_aLinkThatDropsAfterTheLastCommand_doesNotAffectTheChecksAndTheReadIsComplete() =
        runTest {
            withChips { c, keys ->
                val reference = bacChip(c, keys)
                driveFlow(reference).complete
                val commands = reference.chip.received.size
                val flow = bacChip(c, keys)
                // The link is gone from the moment the last command was answered: checking the files
                // (no radio, design 2.1 step 10) must not need it.
                flow.chip.loseTagAtCommand = commands
                val driven = driveFlow(flow)
                val read = driven.complete
                assertEquals(1, driven.ending.attempts)
                assertEquals(commands, flow.chip.received.size, "nothing is sent while the files are checked")
                assertHashPassed(read, 1, 2)
            }
        }

    // --- H-26: a data group listed by EF.COM only ------------------------------------------------------

    @Test
    fun h26_resumeWithAGroupListedByEfComOnly_keepsFilesByNumberNeverJoinsAnUnvouchedFileAndADroppedGroupStaysDropped() =
        runTest {
            withChips { c, keys ->
                val dg11Bytes = FakeGroups.group(0x6B, 11, length = 600)
                val loss = FakeLinkLoss { chip, cmd -> SimDrive.readsAppFile(chip, cmd, dg11) && SimDrive.offset(cmd) > 0 }
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        extraDataGroups = mapOf(11 to dg11Bytes),
                        // EF.COM lists DG11; EF.SOD holds no hash for it.
                        sodGroups = listOf(1, 2),
                        extraHandlers = listOf(loss),
                    )
                val driven = driveFlow(flow, options = options(flow, groups = setOf(1, 2, 3, 11)))
                val read = driven.complete
                assertEquals(1, loss.fired)
                assertEquals(ChipResumeOutcome.SAME_SECURITY_OBJECT, read.resume)
                assertEquals(1, read.dataGroup(1)!!.attempt, "DG1 kept by number from attempt 1")
                assertEquals(1, read.dataGroup(2)!!.attempt, "DG2 kept by number from attempt 1")
                val eleven = read.dataGroup(11)!!
                assertEquals(2, eleven.attempt)
                assertNull(eleven.continuedAt, "no stored hash can vouch for joined bytes: DG11 is read again in full")
                assertContentEquals(dg11Bytes, eleven.bytes())
                assertEquals(
                    CheckOutcome.Failed(io.lightine.tessera.emrtd.ChipCheckReason.NO_HASH_IN_SECURITY_OBJECT),
                    read.outcomeOf(ChipCheckKind.DataGroupHashMatches(11)),
                )
                val dropped = read.fileErrors.filter { it.fileId == 0x0103 }
                assertEquals(1, dropped.size, "DG3, listed nowhere, is one error: ${read.fileErrors}")
                assertEquals(false, assertIs<ChipFileNotFound>(dropped.single().error).listedByChip)
            }
        }

    // --- H-27: many losses per read ------------------------------------------------------------------

    @Test
    fun h27_twoLossesInDg2_completeWithResumeTwo_andAThirdEndsPartialWithWhatWasRead() =
        runTest {
            withChips { c, keys ->
                val twice = FakeLinkLoss(times = 2) { chip, cmd -> SimDrive.readsAppFile(chip, cmd, dg2) && SimDrive.offset(cmd) > 0 }
                val ok = driveFlow(bacChip(c, keys, listOf(twice)))
                ok.complete
                assertEquals(3, ok.ending.attempts)

                val thrice = FakeLinkLoss(times = 3) { chip, cmd -> SimDrive.readsAppFile(chip, cmd, dg2) && SimDrive.offset(cmd) > 0 }
                val flow = bacChip(c, keys, listOf(thrice))
                val ended = driveFlow(flow)
                val partial = assertIs<ChipReadEnding.Partial>(ended.ending)
                assertEquals(NfcTagLost, partial.stoppedBy)
                assertEquals(3, partial.attempts)
                assertNotNull(partial.read.dataGroup(1), "what was read stays in the ending")
                assertContentEquals(flow.dataGroups.getValue(1), partial.read.dataGroup(1)!!.bytes())
            }
        }

    // --- H-18: after a loss, an answer is never followed by another attempt ------------------------------

    @Test
    fun h18_afterALoss_anSmErrorOrAMalformedFileInTheNewAttemptEndsTheReadWithNoThirdLink() =
        runTest {
            withChips { c, keys ->
                // A secure-messaging error in attempt 2 (a bad MAC on the first DG1 answer there).
                var flowRef: FlowChip? = null
                var links = 0
                val corruptInSecondLink =
                    object : FakeCommandHandler {
                        override fun powerOff() {
                            links++
                        }

                        override fun handle(
                            chip: FakeChip,
                            command: CommandApdu,
                        ): ResponseApdu? {
                            // FakeAttemptLink powers the chip up for link 1 too: link 2 is the third power-up.
                            if (links == 2 &&
                                SimDrive.readsAppFile(chip, command, LdsIds.EF_COM)
                            ) {
                                flowRef!!.sm!!.corruptNextResponseMac = true
                            }
                            return null
                        }
                    }
                val loss = FakeLinkLoss { chip, cmd -> SimDrive.readsAppFile(chip, cmd, dg2) && SimDrive.offset(cmd) > 0 }
                val flow = bacChip(c, keys, listOf(loss, corruptInSecondLink))
                flowRef = flow
                val sm = driveFlow(flow, LinkLossPolicy.Resume(3))
                val partial = assertIs<ChipReadEnding.Partial>(sm.ending)
                assertIs<io.lightine.tessera.types.errors.ChipSecureMessagingLost>(partial.stoppedBy)
                assertEquals(2, sm.links, "no link after the secure-messaging error")
                assertEquals(2, partial.attempts)

                // A malformed file in attempt 2: a file error, the read goes on and ends, no new attempt.
                val malformed = byteArrayOf(0x6B, 0x82.toByte(), 0x7F)
                val flow2 =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        extraApplicationFiles = mapOf(dg11 to malformed),
                        extraHandlers =
                            listOf(
                                FakeLinkLoss { chip, cmd -> SimDrive.readsAppFile(chip, cmd, dg2) && SimDrive.offset(cmd) > 0 },
                            ),
                    )
                val driven = driveFlow(flow2, LinkLossPolicy.Resume(3), options(flow2, groups = setOf(1, 2, 11)))
                val read = driven.complete
                assertEquals(2, driven.links)
                assertTrue(read.fileErrors.any { it.fileId == dg11 }, "${read.fileErrors}")
            }
        }

    // --- H-28: a loss while the observer throws ----------------------------------------------------------

    @Test
    fun h28_aLossWhileTheObserverThrowsOnEveryCall_changesNothing() =
        runTest {
            withChips { c, keys ->
                val flow =
                    bacChip(
                        c,
                        keys,
                        listOf(
                            FakeLinkLoss { chip, cmd ->
                                SimDrive.readsAppFile(chip, cmd, dg2) &&
                                    SimDrive.offset(cmd) > 0
                            },
                        ),
                    )
                val calls = mutableListOf<Any>()
                val throwing =
                    object : io.lightine.tessera.emrtd.ChipReadObserver {
                        override fun onUpdate(update: io.lightine.tessera.emrtd.ChipReadUpdate) {
                            calls += update
                            error("observer failure")
                        }

                        override fun onEnding(ending: ChipReadEnding) {
                            calls += ending
                            error("observer failure")
                        }
                    }
                val coordinator = ChipReadCoordinator(options(flow), LinkLossPolicy.Resume(2), throwing, ChipReader(NoOpTelemetrySink))
                assertTrue(coordinator.start())
                coordinator.waiting(0)
                var link = 0
                while (true) {
                    link++
                    coordinator.documentFound()
                    coordinator.connecting()
                    if (coordinator.read(FakeAttemptLink(flow.chip)) == ChipReadAttemptOutcome.ENDED) break
                    assertTrue(link < 4)
                }
                assertEquals(2, link)
                val ending = assertIs<ChipReadEnding.Complete>(calls.last())
                assertEquals(2, ending.attempts)
                assertEquals(1, calls.count { it is ChipReadEnding })
                assertEquals(1, calls.count { (it as? io.lightine.tessera.emrtd.ChipReadUpdate)?.state is ChipReadState.LinkLost })
            }
        }
}
