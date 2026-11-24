package io.lightine.tessera.emrtd.sim

import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.ChipCheckKind
import io.lightine.tessera.emrtd.ChipRead
import io.lightine.tessera.emrtd.ChipReadAttemptOutcome
import io.lightine.tessera.emrtd.ChipReadCoordinator
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReader
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.emrtd.fakechip.FakeAttemptLink
import io.lightine.tessera.emrtd.fakechip.FakeChip
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.fakechip.SimRecorder
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.emrtd.internal.pa.withKeys
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.vocabulary.CheckOutcome
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** What a simulated read through the coordinator left: the ending, every update, and the links used. */
internal class Driven(
    val ending: ChipReadEnding,
    val recorder: SimRecorder,
    val links: Int,
) {
    val complete: ChipRead get() = assertIs<ChipReadEnding.Complete>(ending, "$ending").read
}

/**
 * Drives reads through [ChipReadCoordinator] as a platform entry point does (TES-346 catalogue
 * groups E, H, J, L, M): start, wait, find the document, connect, read; after a lost link the
 * document comes back on a new link that the test chooses.
 */
internal object SimDrive {
    val CHECK_TIME: Instant = Instant.parse("2026-06-01T12:00:00Z")

    inline fun withChips(block: (ChipCrypto, TestKeys) -> Unit) {
        withCrypto { c -> withKeys { keys -> block(c, keys) } }
    }

    fun options(
        flow: FlowChip,
        key: ChipAccessKey = FakeReadFlowChip.mrzKey(),
        groups: Set<Int> = setOf(1, 2),
    ) = ChipReadOptions(key, groups, listOf(flow.anchor), CHECK_TIME)

    /**
     * Runs one read; [linkFor] gives the link of each attempt (from 1). A coordinator that keeps
     * asking for links past [maxLinks] fails the test. The read's promises (one ending, progress)
     * are checked.
     */
    suspend fun drive(
        options: ChipReadOptions,
        policy: LinkLossPolicy,
        maxLinks: Int = 6,
        coordinatorHook: (ChipReadCoordinator) -> Unit = {},
        afterLoss: (ChipReadCoordinator) -> Boolean = { true },
        linkFor: (Int) -> ApduTransport,
    ): Driven {
        val recorder = SimRecorder { 0L }
        val coordinator = ChipReadCoordinator(options, policy, recorder, ChipReader(NoOpTelemetrySink))
        coordinatorHook(coordinator)
        if (coordinator.start()) {
            coordinator.waiting(0)
            var link = 1
            while (true) {
                coordinator.documentFound()
                coordinator.connecting()
                if (coordinator.read(linkFor(link)) == ChipReadAttemptOutcome.ENDED) break
                if (!afterLoss(coordinator)) break
                link++
                assertTrue(link <= maxLinks, "more links than the policy allows: $link")
            }
            assertTrue(link <= maxLinks)
            assertNull(recorder.checkPromises())
            return Driven(recorder.ending, recorder, link)
        }
        assertNull(recorder.checkPromises())
        return Driven(recorder.ending, recorder, 0)
    }

    /** The same document on every link: [FakeAttemptLink] powers the chip up again each time. */
    suspend fun driveFlow(
        flow: FlowChip,
        policy: LinkLossPolicy = LinkLossPolicy.Resume(2),
        options: ChipReadOptions = options(flow),
    ): Driven = drive(options, policy) { FakeAttemptLink(flow.chip) }

    fun assertHashPassed(
        read: ChipRead,
        vararg numbers: Int,
    ) {
        for (n in numbers) assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.DataGroupHashMatches(n)), "DG$n")
    }

    fun isPlainRead(command: CommandApdu): Boolean = command.ins == ChipCommands.INS_READ_BINARY && command.p1 and 0x80 == 0

    fun offset(command: CommandApdu): Int = (command.p1 shl 8) or command.p2

    /** A READ BINARY (B0 or B1) of [fid] in the application. */
    fun readsAppFile(
        chip: FakeChip,
        command: CommandApdu,
        fid: Int,
    ): Boolean =
        (command.ins == ChipCommands.INS_READ_BINARY || command.ins == ChipCommands.INS_READ_BINARY_ODD) &&
            chip.currentApplication != null && chip.currentEf == fid

    /** A READ BINARY of [fid] in the master file. */
    fun readsMasterFile(
        chip: FakeChip,
        command: CommandApdu,
        fid: Int,
    ): Boolean = command.ins == ChipCommands.INS_READ_BINARY && chip.currentApplication == null && chip.currentEf == fid
}
