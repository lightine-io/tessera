package io.lightine.tessera.emrtd.sim

import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadResult
import io.lightine.tessera.emrtd.ChipReader
import io.lightine.tessera.emrtd.fakechip.FakeChip
import io.lightine.tessera.emrtd.fakechip.FakeCommandHandler
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.emrtd.internal.pa.withKeys
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.errors.ChipError
import kotlin.test.assertIs
import kotlin.time.Instant

/** Shared helpers of the catalogue scenario tests (card TES-346): fake chips from the project specimen. */
internal object Sim {
    val checkTime: Instant = Instant.parse("2026-06-01T12:00:00Z")

    inline fun withChips(block: (ChipCrypto, TestKeys) -> Unit) {
        withCrypto { c -> withKeys { keys -> block(c, keys) } }
    }

    fun options(
        flow: FlowChip,
        key: ChipAccessKey = FakeReadFlowChip.mrzKey(),
        dataGroups: Set<Int> = setOf(1, 2),
    ) = ChipReadOptions(key, dataGroups, listOf(flow.anchor), checkTime)

    suspend fun read(
        flow: FlowChip,
        key: ChipAccessKey = FakeReadFlowChip.mrzKey(),
        transport: ApduTransport = flow.chip,
        dataGroups: Set<Int> = setOf(1, 2),
    ): ChipReadResult = ChipReader(NoOpTelemetrySink).read(transport, options(flow, key, dataGroups))

    fun failed(r: ChipReadResult): ChipError = assertIs<ChipReadResult.Failed>(r, "$r").error

    /** Answers the plain or protected commands with [cla] and [ins] (and [p1] when given) with the bare [sw]. */
    fun answer(
        cla: Int,
        ins: Int,
        sw: Int,
        p1: Int? = null,
    ) = FakeCommandHandler { _, c ->
        if (c.cla == cla && c.ins == ins && (p1 == null || c.p1 == p1)) ResponseApdu(ByteArray(0), sw) else null
    }

    fun FakeChip.count(
        cla: Int,
        ins: Int,
    ): Int = received.count { (it[0].toInt() and 0xFF) == cla && (it[1].toInt() and 0xFF) == ins }

    fun ByteArray.isCommand(
        cla: Int,
        ins: Int,
    ): Boolean = (this[0].toInt() and 0xFF) == cla && (this[1].toInt() and 0xFF) == ins

    fun FakeChip.last(): ByteArray = received.last()

    /** The BAC/PACE key seed text from TD1 lines: number with digit, birth with digit, expiry with digit (Part 11, 9.7.1.1). */
    fun td1MrzInformation(lines: List<String>): String = lines[0].substring(5, 15) + lines[1].substring(0, 7) + lines[1].substring(8, 15)

    fun td1Key(lines: List<String>): ChipAccessKey.Mrz =
        ChipAccessKey.Mrz(
            lines[0].substring(5, 14),
            lines[0][14],
            lines[1].substring(0, 6),
            lines[1][6],
            lines[1].substring(8, 14),
            lines[1][14],
        )
}
