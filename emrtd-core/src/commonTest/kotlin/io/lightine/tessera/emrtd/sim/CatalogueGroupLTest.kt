package io.lightine.tessera.emrtd.sim

import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.emrtd.fakechip.FakeAttemptLink
import io.lightine.tessera.emrtd.fakechip.FakeLinkLoss
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.SimRecorder
import io.lightine.tessera.emrtd.sim.SimDrive.drive
import io.lightine.tessera.emrtd.sim.SimDrive.driveFlow
import io.lightine.tessera.emrtd.sim.SimDrive.options
import io.lightine.tessera.emrtd.sim.SimDrive.withChips
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Catalogue group L (progress and updates), TES-346, at the core; design section 3.4 unless noted. */
class CatalogueGroupLTest {
    private val dg1 = 0x0101
    private val dg2 = 0x0102

    private fun midDg2() = FakeLinkLoss { chip, cmd -> SimDrive.readsAppFile(chip, cmd, dg2) && SimDrive.offset(cmd) > 0 }

    /** The fraction of the last update, which every ending other than Complete keeps (L-03). */
    private fun lastFraction(recorder: SimRecorder): Double =
        recorder.updates
            .last()
            .value.progress.fraction

    @Test
    fun l03_everyEndingButCompleteKeepsTheLastValueBelowOne() =
        runTest {
            withChips { c, keys ->
                val endings = mutableListOf<Pair<ChipReadEnding, SimRecorder>>()
                // Partial: the budget used up after access.
                val always = FakeLinkLoss(times = 10) { chip, cmd -> SimDrive.readsAppFile(chip, cmd, dg2) && SimDrive.offset(cmd) > 0 }
                driveFlow(FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraHandlers = listOf(always))).let {
                    endings +=
                        it.ending to it.recorder
                }
                // KeyNotAccepted: the chip refuses the key.
                val wrongKey = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                drive(options(wrongKey, key = FakeReadFlowChip.wrongMrzKey()), LinkLossPolicy.Resume(2)) { FakeAttemptLink(wrongKey.chip) }
                    .let { endings += it.ending to it.recorder }
                // Failed: a blank chip.
                driveFlow(FakeReadFlowChip.build(c, keys, FlowAccess.BAC, blank = true)).let { endings += it.ending to it.recorder }
                // Cancelled and EndedBySystem: while waiting after a loss.
                for (systemEnd in listOf(false, true)) {
                    val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraHandlers = listOf(midDg2()))
                    val driven =
                        drive(options(flow), LinkLossPolicy.Resume(2), afterLoss = { co ->
                            if (systemEnd) {
                                co.endedBySystem(
                                    NfcUnavailable(NfcUnavailable.Reason.ENDED_BY_SYSTEM),
                                )
                            } else {
                                co.cancel(ChipReadEnding.Cancelled.By.APP)
                            }
                            false
                        }) { FakeAttemptLink(flow.chip) }
                    endings += driven.ending to driven.recorder
                }
                val kinds = endings.map { it.first::class.simpleName }
                assertEquals(listOf("Partial", "KeyNotAccepted", "Failed", "Cancelled", "EndedBySystem"), kinds)
                for ((ending, recorder) in endings) {
                    assertTrue(ending.progress.fraction < 1.0 && ending.progress.percent <= 99, "$ending: ${ending.progress}")
                    assertEquals(lastFraction(recorder), ending.progress.fraction, "$ending keeps the last value")
                }
            }
        }

    @Test
    fun l04_bytesTotalIsKnownAfterTheFirstChunkAndFilesPlannedAfterTheLists() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, dg2Bytes = 3_000)
                val driven = driveFlow(flow)
                driven.complete
                val updates = driven.recorder.updates.map { it.value }
                val reading = updates.filter { it.state is ChipReadState.ReadingFile }
                for ((file, group) in reading.groupBy { (it.state as ChipReadState.ReadingFile).file.fileId }) {
                    val states = group.map { it.state as ChipReadState.ReadingFile }
                    assertNull(states.first().bytesTotal, "file ${file.toString(16)}: the length is not known before the first chunk")
                    val size =
                        flow.chip.files.applications.values
                            .first()[file]
                            ?.size
                    if (size != null && states.size > 1) {
                        assertTrue(states.drop(1).all { it.bytesTotal == size }, "file ${file.toString(16)}: $states")
                    }
                }
                // filesPlanned stays unknown until EF.COM and EF.SOD are read, then known.
                val firstPlanned = updates.indexOfFirst { it.progress.filesPlanned != null }
                assertTrue(firstPlanned > 0)
                val sodUpdates = updates.withIndex().filter { (it.value.state as? ChipReadState.ReadingFile)?.file?.fileId == 0x011D }
                assertTrue(sodUpdates.all { it.index < firstPlanned }, "planned only after EF.SOD")
                assertTrue(updates.drop(firstPlanned).all { it.progress.filesPlanned != null })
            }
        }

    @Test
    fun l08_updatesEndingsAndTheReadCarryNoDocumentValues() =
        runTest {
            withChips { c, keys ->
                val sentinel = "QXZSENTINELQXZ"
                val dg11 =
                    io.lightine.tessera.emrtd.internal.tlv.TlvWriter.constructed(
                        0x6B,
                        io.lightine.tessera.emrtd.internal.tlv.TlvWriter
                            .encode(0x5F0E, sentinel.encodeToByteArray()),
                    )
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraDataGroups = mapOf(11 to dg11))
                val driven = driveFlow(flow, options = options(flow, groups = setOf(1, 2, 11)))
                val read = driven.complete
                assertNotNull(read.dataGroup(11))
                val values = listOf(sentinel, "SPECIMEN", "ALEX", "MORGAN", "T22000129", "900315", "340314", "S98765432")
                val texts =
                    driven.recorder.updates.map { it.value.toString() } +
                        driven.recorder.updates.map { it.value.state.toString() } +
                        driven.ending.toString() + read.toString() + read.files.map { it.toString() } +
                        read.checks.map { it.toString() } +
                        listOfNotNull(read.dg1?.toString(), read.efCom?.toString(), read.dg11?.toString())
                for (text in texts) for (value in values) assertFalse(value in text, "\"$value\" in: $text")
            }
        }

    @Test
    fun l11_filesKeptOnResumeCountAsDoneAtOnceAndTheBarNeverGoesBack() =
        runTest {
            withChips { c, keys ->
                val reference = driveFlow(FakeReadFlowChip.build(c, keys, FlowAccess.BAC, caInApplication = true))
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, caInApplication = true, extraHandlers = listOf(midDg2()))
                val driven = driveFlow(flow)
                val read = driven.complete
                assertEquals(2, driven.ending.attempts)
                assertEquals(1, read.dataGroup(1)!!.attempt, "DG1 kept")
                val updates = driven.recorder.updates.map { it.value }
                val lost = updates.indexOfFirst { it.state is ChipReadState.LinkLost }
                val continuedDg2 =
                    updates.withIndex().first { (i, u) -> i > lost && (u.state as? ChipReadState.ReadingFile)?.file?.fileId == dg2 }
                // EF.COM, EF.SOD (read again), and DG1 and DG14 (kept) are done when DG2 continues.
                assertTrue(continuedDg2.value.progress.filesDone >= 4, "${continuedDg2.value.progress}")
                assertTrue(continuedDg2.value.progress.fraction > updates[lost].progress.fraction, "the bar moves forward after the loss")
                assertEquals(reference.ending.progress.filesDone, driven.ending.progress.filesDone, "every file counted once")
            }
        }

    @Test
    fun l12_requestedGroupsTheChipDoesNotListShrinkThePlanAndTheBarStillReachesTheEnd() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val driven = driveFlow(flow, options = options(flow, groups = setOf(1, 2, 3, 4, 7, 11, 12)))
                val read = driven.complete
                val updates = driven.recorder.updates.map { it.value }
                // The plan: EF.COM, EF.SOD, DG1, DG2; the five groups listed nowhere are dropped.
                assertEquals(4, updates.last().progress.filesPlanned)
                assertEquals(5, read.fileErrors.size, "${read.fileErrors}")
                val beforeChecks = updates.last { it.state !is ChipReadState.CheckingFiles }
                // The same chip read for the listed groups only: the dropped groups' estimates must
                // not hold the bar back, so at the same point it stands where that read stands.
                val plain = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val asked =
                    driveFlow(plain)
                        .recorder.updates
                        .map { it.value }
                        .last { it.state !is ChipReadState.CheckingFiles }
                assertTrue(
                    beforeChecks.progress.fraction >= asked.progress.fraction - 0.02,
                    "with dropped groups ${beforeChecks.progress}, without ${asked.progress}",
                )
                assertEquals(100, driven.ending.progress.percent)
            }
        }
}
