package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.fakechip.FakeAttemptLink
import io.lightine.tessera.emrtd.fakechip.FakeChip
import io.lightine.tessera.emrtd.fakechip.FakeCommandHandler
import io.lightine.tessera.emrtd.fakechip.FakeFlippedByte
import io.lightine.tessera.emrtd.fakechip.FakeLinkLoss
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FakeReadSpy
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.fakechip.RecordingObserver
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.emrtd.internal.pa.withKeys
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.errors.ChipAccessDenied
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.vocabulary.CheckOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Lost links and what follows, on fake chips (the project specimen, keys made at test time): each
 * place a link can drop, under [LinkLossPolicy.Resume], [LinkLossPolicy.ReadAgain] and
 * [LinkLossPolicy.End], driven through [ChipReadCoordinator] as a platform entry point does. Every
 * attempt gets a new link to a chip that lost power (its session keys are gone).
 */
class ChipReadResumeTest {
    private val checkTime = Instant.parse("2026-06-01T12:00:00Z")
    private val dg1 = 0x0101
    private val dg2 = 0x0102
    private val dg14 = 0x010E

    private inline fun withChips(block: (ChipCrypto, TestKeys) -> Unit) {
        withCrypto { c -> withKeys { keys -> block(c, keys) } }
    }

    private fun options(
        flow: FlowChip,
        key: ChipAccessKey = FakeReadFlowChip.mrzKey(),
    ) = ChipReadOptions(key, setOf(1, 2), listOf(flow.anchor), checkTime)

    /** Drives a read as a platform does; [chipFor] gives the chip for each attempt (a new link each time). */
    private suspend fun drive(
        flow: FlowChip,
        policy: LinkLossPolicy,
        spy: FakeReadSpy? = null,
        key: ChipAccessKey = FakeReadFlowChip.mrzKey(),
        linkFor: (Int) -> ApduTransport = { FakeAttemptLink(flow.chip) },
    ): Pair<ChipReadEnding, RecordingObserver> {
        val observer = RecordingObserver()
        val coordinator = ChipReadCoordinator(options(flow, key), policy, observer, ChipReader(NoOpTelemetrySink))
        assertTrue(coordinator.start())
        coordinator.waiting(0)
        var attempt = 1
        while (true) {
            coordinator.documentFound()
            coordinator.connecting()
            spy?.attempt = attempt
            if (coordinator.read(linkFor(attempt)) == ChipReadAttemptOutcome.ENDED) break
            attempt++
            assertTrue(attempt <= 5, "too many attempts")
        }
        assertEquals(1, observer.endings.size)
        assertIs<ChipReadEnding>(observer.calls.last())
        assertProgressNeverGoesBack(observer)
        return observer.endings.single() to observer
    }

    private fun assertProgressNeverGoesBack(observer: RecordingObserver) {
        var last = 0.0
        for (u in observer.updates) {
            assertTrue(u.progress.fraction >= last, "progress went back: ${observer.updates}")
            assertTrue(u.progress.fraction < 1.0 && u.progress.percent <= 99, "${u.progress}")
            last = u.progress.fraction
        }
        val ending = observer.endings.single()
        assertTrue(ending.progress.fraction >= last)
        if (ending is ChipReadEnding.Complete) assertEquals(100, ending.progress.percent) else assertTrue(ending.progress.percent <= 99)
    }

    private fun complete(ending: ChipReadEnding): ChipRead = assertIs<ChipReadEnding.Complete>(ending, "$ending").read

    private fun assertHashPassed(
        read: ChipRead,
        n: Int,
    ) = assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.DataGroupHashMatches(n)), "DG$n")

    private fun dataGroupRead(
        chip: FakeChip,
        command: CommandApdu,
        fileId: Int,
    ) = FakeLinkLoss.isRead(command) && chip.currentEf == fileId

    /** A loss at the first READ BINARY of [fileId] past offset 0. */
    private fun midFile(fileId: Int) = FakeLinkLoss { chip, c -> dataGroupRead(chip, c, fileId) && FakeLinkLoss.offset(c) > 0 }

    private fun build(
        c: ChipCrypto,
        keys: TestKeys,
        access: FlowAccess,
        handlers: List<FakeCommandHandler>,
        caInApplication: Boolean = false,
        withSecurityObject: Boolean = true,
    ) = FakeReadFlowChip.build(
        c,
        keys,
        access,
        extraHandlers = handlers,
        caInApplication = caInApplication,
        withSecurityObject = withSecurityObject,
    )

    // --- Resume: where the link drops -----------------------------------------------------------

    @Test
    fun aLossDuringAccessReadsEverythingInTheNextAttempt() =
        runTest {
            withChips { c, keys ->
                val loss = FakeLinkLoss { _, cmd -> cmd.ins == 0x82 }
                val flow = build(c, keys, FlowAccess.BAC, listOf(loss))
                val (ending, observer) = drive(flow, LinkLossPolicy.Resume(2))
                val read = complete(ending)
                assertEquals(2, ending.attempts)
                assertEquals(ChipResumeOutcome.NOTHING_TO_COMPARE, read.resume)
                assertTrue(read.files.all { it.attempt == 2 }, "${read.files}")
                assertEquals(1, observer.updates.count { it.state is ChipReadState.LinkLost })
            }
        }

    @Test
    fun aLossDuringEfComReadsEfComAgainInFull() =
        runTest {
            withChips { c, keys ->
                val spy = FakeReadSpy()
                val loss = FakeLinkLoss { chip, cmd -> dataGroupRead(chip, cmd, LdsIds.EF_COM) }
                val flow = build(c, keys, FlowAccess.BAC, listOf(spy, loss))
                val (ending, _) = drive(flow, LinkLossPolicy.Resume(1), spy)
                val read = complete(ending)
                assertEquals(ChipResumeOutcome.NOTHING_TO_COMPARE, read.resume)
                assertEquals(2, read.applicationFile(LdsIds.EF_COM)!!.attempt)
                assertEquals(0, spy.offsets(2, LdsIds.EF_COM).first())
            }
        }

    @Test
    fun aLossDuringEfSodReadsEfSodAgainFromTheStartAndKeepsNothing() =
        runTest {
            withChips { c, keys ->
                val spy = FakeReadSpy()
                val flow = build(c, keys, FlowAccess.BAC, listOf(spy, midFile(LdsIds.EF_SOD)))
                val (ending, _) = drive(flow, LinkLossPolicy.Resume(1), spy)
                val read = complete(ending)
                assertEquals(ChipResumeOutcome.NOTHING_TO_COMPARE, read.resume)
                val sod = read.applicationFile(LdsIds.EF_SOD)!!
                assertEquals(2, sod.attempt)
                assertNull(sod.continuedAt)
                assertEquals(0, spy.offsets(2, LdsIds.EF_SOD).first())
                assertTrue(read.files.all { it.attempt == 2 })
            }
        }

    @Test
    fun theSameDocumentBackAfterALossInTheMiddleOfDg2KeepsEarlierFilesAndContinuesDg2OnBacAndPaceChips() =
        runTest {
            withChips { c, keys ->
                for (access in listOf(FlowAccess.BAC, FlowAccess.PACE_ONLY)) {
                    val label = "$access"
                    val spy = FakeReadSpy()
                    val flow = build(c, keys, access, listOf(spy, midFile(dg2)))
                    val (ending, _) = drive(flow, LinkLossPolicy.Resume(2), spy)
                    val read = complete(ending)
                    assertEquals(2, ending.attempts, label)
                    assertEquals(ChipResumeOutcome.SAME_SECURITY_OBJECT, read.resume, label)
                    // DG1 was complete in attempt 1: kept, not read again.
                    assertEquals(1, read.dataGroup(1)!!.attempt, label)
                    assertTrue(spy.offsets(2, dg1).isEmpty(), label)
                    // DG2 continues where it stopped, and its hash vouches for the joined bytes.
                    val file = read.dataGroup(2)!!
                    val continuedAt = assertNotNull(file.continuedAt, label)
                    assertEquals(spy.offsets(2, dg2).first(), continuedAt, label)
                    assertEquals(2, file.attempt, label)
                    assertTrue(file.bytes().contentEquals(flow.dataGroups.getValue(2)), label)
                    assertHashPassed(read, 1)
                    assertHashPassed(read, 2)
                    // No hash vouches for EF.COM and EF.SOD: read again in full.
                    assertEquals(2, read.applicationFile(LdsIds.EF_COM)!!.attempt, label)
                    assertEquals(2, read.applicationFile(LdsIds.EF_SOD)!!.attempt, label)
                    assertNull(read.applicationFile(LdsIds.EF_SOD)!!.continuedAt, label)
                }
            }
        }

    @Test
    fun aLossDuringChipAuthenticationRunsItAgainInTheNewSession() =
        runTest {
            withChips { c, keys ->
                var flowRef: FlowChip? = null
                val loss =
                    FakeLinkLoss { chip, cmd ->
                        (cmd.ins == 0x22 || cmd.ins == 0x86) && flowRef!!.sm!!.isActive &&
                            chip.currentApplication != null
                    }
                val flow = build(c, keys, FlowAccess.BAC, listOf(loss), caInApplication = true)
                flowRef = flow
                val (ending, observer) = drive(flow, LinkLossPolicy.Resume(1))
                val read = complete(ending)
                assertEquals(ChipResumeOutcome.SAME_SECURITY_OBJECT, read.resume)
                assertEquals(1, read.dataGroup(14)!!.attempt)
                assertEquals(1, flow.ca!!.restarts)
                assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.ChipAuthentication(ChipFileContext.EMRTD_APPLICATION)))
                // The lost attempt's authentication left no outcome; the update showed it was running.
                val states = observer.updates.map { it.state }
                val lost = states.indexOfFirst { it is ChipReadState.LinkLost }
                val running = assertIs<ChipReadState.AuthenticatingChip>(states.take(lost).last(), "$states")
                assertEquals(ChipAuthMethod.CHIP_AUTHENTICATION, running.method)
            }
        }

    @Test
    fun aLossAtTheLastPartOfTheLastFileReadsOnlyTheRest() =
        runTest {
            withChips { c, keys ->
                val spy = FakeReadSpy()
                var lastOffset = -1
                val loss = FakeLinkLoss { chip, cmd -> dataGroupRead(chip, cmd, dg2) && FakeLinkLoss.offset(cmd) == lastOffset }
                val flow = build(c, keys, FlowAccess.BAC, listOf(spy, loss))
                // A dry run finds the offset of DG2's last part.
                complete(drive(flow, LinkLossPolicy.End, spy).first)
                lastOffset = spy.offsets(1, dg2).last()
                spy.reads.clear()
                val (ending, _) = drive(flow, LinkLossPolicy.Resume(1), spy)
                val read = complete(ending)
                assertEquals(listOf(lastOffset), spy.offsets(2, dg2))
                assertEquals(lastOffset, read.dataGroup(2)!!.continuedAt)
                assertHashPassed(read, 2)
            }
        }

    @Test
    fun aLossTwiceInARowContinuesTwice() =
        runTest {
            withChips { c, keys ->
                val spy = FakeReadSpy()
                val fired = mutableListOf<Int>()
                val loss =
                    FakeLinkLoss(times = 2) { chip, cmd ->
                        val o = FakeLinkLoss.offset(cmd)
                        (dataGroupRead(chip, cmd, dg2) && o > (fired.maxOrNull() ?: 0)).also { if (it) fired += o }
                    }
                val flow = build(c, keys, FlowAccess.BAC, listOf(spy, loss))
                val (ending, observer) = drive(flow, LinkLossPolicy.Resume(2), spy)
                val read = complete(ending)
                assertEquals(3, ending.attempts)
                assertEquals(fired.last(), read.dataGroup(2)!!.continuedAt)
                assertEquals(3, read.dataGroup(2)!!.attempt)
                assertEquals(1, read.dataGroup(1)!!.attempt)
                assertHashPassed(read, 2)
                val lost = observer.updates.mapNotNull { it.state as? ChipReadState.LinkLost }.map { it.restartsLeft }
                // Each loss came after new bytes of DG2: none used a restart (TES-369).
                assertEquals(listOf(2, 2), lost)
                assertEquals(listOf(1, 2, 3), observer.updates.map { it.attempt }.distinct())
                assertTrue(observer.updates.all { it.maxAttempts == 3 })
            }
        }

    @Test
    fun aLossOnTheFirstCommandOfTheResumedAttemptStillResumesFromTheFirstAttempt() =
        runTest {
            withChips { c, keys ->
                val spy = FakeReadSpy()
                val flow = build(c, keys, FlowAccess.BAC, listOf(spy, midFile(dg2)))
                val (ending, _) =
                    drive(flow, LinkLossPolicy.Resume(2), spy) { attempt ->
                        FakeAttemptLink(flow.chip, dropFirst = if (attempt == 2) 1 else 0)
                    }
                val read = complete(ending)
                // The loss before access in the second attempt costs no restart and no attempt:
                // the third link repeats attempt 2 (TES-346).
                assertEquals(2, ending.attempts)
                assertEquals(ChipResumeOutcome.SAME_SECURITY_OBJECT, read.resume)
                assertEquals(1, read.dataGroup(1)!!.attempt)
                assertNotNull(read.dataGroup(2)!!.continuedAt)
                assertHashPassed(read, 2)
            }
        }

    // --- Resume: is it the same document? -------------------------------------------------------

    @Test
    fun aDifferentDocumentWithADifferentKeyEndsAsKeyNotAcceptedWithNothingFromEarlier() =
        runTest {
            withChips { c, keys ->
                val flow = build(c, keys, FlowAccess.BAC, listOf(midFile(dg2)))
                val other =
                    FakeReadFlowChip.build(c, keys, FlowAccess.BAC, keyMrzInformation = "X12345678" + "0" + "8001011" + "3001017")
                val (ending, _) =
                    drive(flow, LinkLossPolicy.Resume(2)) { attempt ->
                        FakeAttemptLink(if (attempt == 1) flow.chip else other.chip)
                    }
                val refused = assertIs<ChipReadEnding.KeyNotAccepted>(ending)
                assertEquals(ChipAccessDenied.Reason.KEY_NOT_MATCHED, refused.error.reason)
                assertEquals(2, refused.attempts)
                assertEquals(1, other.bac!!.refused)
            }
        }

    @Test
    fun aDifferentDocumentThatTakesTheKeyButHoldsAnotherEfSodIsReadAfresh() =
        runTest {
            withChips { c, keys ->
                val flow = build(c, keys, FlowAccess.BAC, listOf(midFile(dg2)))
                val other = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, dg2Bytes = 700)
                val (ending, _) =
                    drive(flow, LinkLossPolicy.Resume(2)) { attempt ->
                        FakeAttemptLink(if (attempt == 1) flow.chip else other.chip)
                    }
                val read = complete(ending)
                assertEquals(ChipResumeOutcome.SECURITY_OBJECT_DIFFERS, read.resume)
                assertTrue(read.files.all { it.attempt == 2 && it.continuedAt == null }, "${read.files}")
                assertTrue(read.dataGroup(2)!!.bytes().contentEquals(other.dataGroups.getValue(2)))
                assertHashPassed(read, 1)
                assertHashPassed(read, 2)
            }
        }

    @Test
    fun aContinuedFileWhoseHashDoesNotMatchIsReadAgainInFullOnce() =
        runTest {
            withChips { c, keys ->
                val spy = FakeReadSpy()
                // Attempt 1 gets DG2's first part with one byte flipped, then the link drops.
                val flow = build(c, keys, FlowAccess.BAC, listOf(spy, FakeFlippedByte(dg2), midFile(dg2)))
                val (ending, _) = drive(flow, LinkLossPolicy.Resume(1), spy)
                val read = complete(ending)
                val file = read.dataGroup(2)!!
                assertNull(file.continuedAt)
                assertEquals(2, file.attempt)
                assertTrue(file.bytes().contentEquals(flow.dataGroups.getValue(2)))
                assertHashPassed(read, 2)
                // In attempt 2: the rest from where it stopped, then the whole file from 0, once.
                val offsets = spy.offsets(2, dg2)
                assertTrue(offsets.first() > 0, "$offsets")
                assertEquals(1, offsets.count { it == 0 }, "$offsets")
            }
        }

    // --- Carried data groups pass their hash in the new session's EF.SOD (TES-346) ---------------

    @Test
    fun aCarriedDataGroupThatEfSodDoesNotListIsReadAgainInFull() =
        runTest {
            withChips { c, keys ->
                val spy = FakeReadSpy()
                // EF.COM lists DG1 and DG2; EF.SOD holds DG2's hash only.
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraHandlers = listOf(spy, midFile(dg2)), sodGroups = listOf(2))
                val (ending, _) = drive(flow, LinkLossPolicy.Resume(1), spy)
                val read = complete(ending)
                assertEquals(ChipResumeOutcome.SAME_SECURITY_OBJECT, read.resume)
                // DG1 was complete in attempt 1, but no hash vouches for it: read again from 0.
                assertEquals(2, read.dataGroup(1)!!.attempt)
                assertEquals(0, spy.offsets(2, dg1).first())
                // DG2 still continues where it stopped.
                assertNotNull(read.dataGroup(2)!!.continuedAt)
            }
        }

    @Test
    fun aCarriedDataGroupWhoseBytesNoLongerMatchItsHashIsReadAgainInFullOnce() =
        runTest {
            withChips { c, keys ->
                val spy = FakeReadSpy()
                // Attempt 1 gets DG1 with one byte flipped, completes it, then loses the link in DG2.
                val flow = build(c, keys, FlowAccess.BAC, listOf(spy, FakeFlippedByte(dg1), midFile(dg2)))
                val (ending, _) = drive(flow, LinkLossPolicy.Resume(1), spy)
                val read = complete(ending)
                assertEquals(ChipResumeOutcome.SAME_SECURITY_OBJECT, read.resume)
                val file = read.dataGroup(1)!!
                assertEquals(2, file.attempt)
                assertTrue(file.bytes().contentEquals(flow.dataGroups.getValue(1)))
                assertHashPassed(read, 1)
                assertEquals(1, spy.offsets(2, dg1).count { it == 0 }, "${spy.offsets(2, dg1)}")
                assertNotNull(read.dataGroup(2)!!.continuedAt)
            }
        }

    @Test
    fun aChipWithoutEfSodIsReadAgainInFull() =
        runTest {
            withChips { c, keys ->
                val spy = FakeReadSpy()
                val flow = build(c, keys, FlowAccess.BAC, listOf(spy, midFile(dg2)), withSecurityObject = false)
                val (ending, _) = drive(flow, LinkLossPolicy.Resume(1), spy)
                val read = complete(ending)
                assertEquals(ChipResumeOutcome.NOTHING_TO_COMPARE, read.resume)
                assertNull(read.dataGroup(2)!!.continuedAt)
                assertEquals(0, spy.offsets(2, dg2).first())
                assertTrue(spy.offsets(2, dg1).isNotEmpty())
            }
        }

    @Test
    fun theBudgetUsedUpEndsPartialWithWhatWasRead() =
        runTest {
            withChips { c, keys ->
                // The first loss comes after new bytes (free); the second and third find DG2 at the
                // same offset, so with one restart the third ends the read (TES-369).
                val loss = FakeLinkLoss(times = 3) { chip, cmd -> dataGroupRead(chip, cmd, dg2) && FakeLinkLoss.offset(cmd) > 0 }
                val flow = build(c, keys, FlowAccess.BAC, listOf(loss))
                val (ending, _) = drive(flow, LinkLossPolicy.Resume(1))
                val partial = assertIs<ChipReadEnding.Partial>(ending)
                assertEquals(NfcTagLost, partial.stoppedBy)
                assertEquals(3, partial.attempts)
                assertEquals(1, partial.read.dataGroup(1)!!.attempt)
                assertNull(partial.read.dataGroup(2))
                val stoppedIn = assertIs<ChipReadState.ReadingFile>(partial.stoppedIn)
                assertEquals(2, stoppedIn.file.dataGroup)
                assertTrue(partial.progress.percent < 100)
            }
        }

    // --- The other policies ---------------------------------------------------------------------

    @Test
    fun readAgainDropsEverythingFromTheEarlierAttempt() =
        runTest {
            withChips { c, keys ->
                val spy = FakeReadSpy()
                val flow = build(c, keys, FlowAccess.BAC, listOf(spy, midFile(dg2)))
                val (ending, _) = drive(flow, LinkLossPolicy.ReadAgain(1), spy)
                val read = complete(ending)
                assertEquals(ChipResumeOutcome.NOT_RESUMED, read.resume)
                assertTrue(read.files.all { it.attempt == 2 && it.continuedAt == null })
                assertTrue(spy.offsets(2, dg1).isNotEmpty())
                assertEquals(0, spy.offsets(2, dg2).first())
            }
        }

    @Test
    fun endGivesPartialAfterAccessAndFailedBefore() =
        runTest {
            withChips { c, keys ->
                val mid = build(c, keys, FlowAccess.BAC, listOf(midFile(dg2)))
                val partial = assertIs<ChipReadEnding.Partial>(drive(mid, LinkLossPolicy.End).first)
                assertEquals(NfcTagLost, partial.stoppedBy)
                assertEquals(1, partial.attempts)

                val early = build(c, keys, FlowAccess.BAC, listOf(FakeLinkLoss { _, cmd -> cmd.ins == 0x82 }))
                val failed = assertIs<ChipReadEnding.Failed>(drive(early, LinkLossPolicy.End).first)
                assertEquals(NfcTagLost, failed.error)
                assertNull(failed.access)
            }
        }

    @Test
    fun aLaterAttemptThatFailsBeforeAccessStillEndsPartialWithTheEarlierRead() =
        runTest {
            withChips { c, keys ->
                val flow = build(c, keys, FlowAccess.BAC, listOf(midFile(dg2)))
                val observer = RecordingObserver()
                val coordinator =
                    ChipReadCoordinator(
                        options(flow, FakeReadFlowChip.mrzKey()),
                        LinkLossPolicy.ReadAgain(1),
                        observer,
                        ChipReader(NoOpTelemetrySink),
                    )
                assertTrue(coordinator.start())
                assertEquals(ChipReadAttemptOutcome.LINK_LOST, coordinator.read(FakeAttemptLink(flow.chip)))
                // The document is back but its chip does not answer before access: no restart is
                // used, and when the platform gives up the read ends with the earlier read.
                assertEquals(ChipReadAttemptOutcome.NO_ANSWER_YET, coordinator.read(FakeAttemptLink(flow.chip, dropFirst = 1)))
                assertTrue(coordinator.documentNotReturned())
                val partial = assertIs<ChipReadEnding.Partial>(observer.endings.single())
                assertEquals(NfcTagLost, partial.stoppedBy)
                assertEquals(2, partial.attempts)
                assertEquals(1, partial.read.dataGroup(1)!!.attempt)
            }
        }

    // --- Never after an answer ------------------------------------------------------------------

    @Test
    fun aRefusedKeyIsTriedOnceMoreOnTheSameLinkAndNeverStartsAgain() =
        runTest {
            withChips { c, keys ->
                for (access in listOf(FlowAccess.BAC, FlowAccess.PACE_ONLY)) {
                    val flow = FakeReadFlowChip.build(c, keys, access)
                    val (ending, observer) = drive(flow, LinkLossPolicy.Resume(3), key = FakeReadFlowChip.wrongMrzKey())
                    val refused = assertIs<ChipReadEnding.KeyNotAccepted>(ending, "$access")
                    assertEquals(0x6300, refused.error.statusWord, "$access")
                    // The one more try runs on the same link: no restart, no new attempt.
                    assertEquals(1, refused.attempts)
                    assertTrue(observer.updates.none { it.state is ChipReadState.LinkLost })
                    if (access == FlowAccess.BAC) assertEquals(2, flow.bac!!.refused) else assertEquals(2, flow.pace!!.refusedTokens)
                }
            }
        }

    @Test
    fun aSecureMessagingErrorIsNeverFollowedByANewAttempt() =
        runTest {
            withChips { c, keys ->
                var flowRef: FlowChip? = null
                val corrupt =
                    FakeCommandHandler { chip, cmd ->
                        if (dataGroupRead(chip, cmd, dg2) && FakeLinkLoss.offset(cmd) == 0) flowRef!!.sm!!.corruptNextResponseMac = true
                        null
                    }
                val flow = build(c, keys, FlowAccess.BAC, listOf(corrupt))
                flowRef = flow
                val (ending, observer) = drive(flow, LinkLossPolicy.Resume(3))
                val partial = assertIs<ChipReadEnding.Partial>(ending)
                assertIs<io.lightine.tessera.types.errors.ChipSecureMessagingLost>(partial.stoppedBy)
                assertEquals(1, partial.attempts)
                assertTrue(observer.updates.none { it.state is ChipReadState.LinkLost })
            }
        }

    @Test
    fun aMalformedFileIsNeverFollowedByANewAttempt() =
        runTest {
            withChips { c, keys ->
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        extraApplicationFiles =
                            mapOf(dg2 to byteArrayOf(0x75, 0x82.toByte(), 0x7F)),
                    )
                val (ending, observer) = drive(flow, LinkLossPolicy.Resume(3))
                val read = complete(ending)
                assertEquals(1, ending.attempts)
                assertTrue(read.fileErrors.any { it.fileId == dg2 }, "${read.fileErrors}")
                assertTrue(observer.updates.none { it.state is ChipReadState.LinkLost })
            }
        }

    @Test
    fun theSessionBoundChecksComeFromTheLastAttemptOnly() =
        runTest {
            withChips { c, keys ->
                // Chip authentication runs in attempt 1, then the link drops in DG2 (read after it).
                val flow = build(c, keys, FlowAccess.BAC, listOf(midFile(dg2)), caInApplication = true)
                val (ending, _) = drive(flow, LinkLossPolicy.Resume(1))
                val read = complete(ending)
                assertEquals(2, flow.ca!!.restarts)
                assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.ChipAuthentication(ChipFileContext.EMRTD_APPLICATION)))
                assertEquals(1, read.dataGroup(14)!!.attempt)
            }
        }
}
