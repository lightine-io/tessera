package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadObserver
import io.lightine.tessera.emrtd.ChipReadUpdate
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.types.errors.NfcTagLost

/**
 * Loses the link (throws [NfcTagLost], as a transport does when the document leaves the field)
 * at the first command [where] matches, [times] times in all. Goes in `extraHandlers`, after secure
 * messaging, so it sees the plain commands inside the protected ones.
 */
internal class FakeLinkLoss(
    var times: Int = 1,
    private val where: (FakeChip, CommandApdu) -> Boolean,
) : FakeCommandHandler {
    var fired = 0
        private set

    override fun handle(
        chip: FakeChip,
        command: CommandApdu,
    ): ResponseApdu? {
        if (fired < times && where(chip, command)) {
            fired++
            throw ApduTransportException(NfcTagLost)
        }
        return null
    }

    companion object {
        fun offset(command: CommandApdu): Int = (command.p1 shl 8) or command.p2

        fun isRead(command: CommandApdu): Boolean = command.ins == ChipCommands.INS_READ_BINARY && command.p1 and 0x80 == 0
    }
}

/** Records the plain READ BINARY commands the chip answers: the current EF and the offset, per attempt. */
internal class FakeReadSpy : FakeCommandHandler {
    var attempt = 1
    val reads = mutableListOf<Triple<Int, Int?, Int>>()

    override fun handle(
        chip: FakeChip,
        command: CommandApdu,
    ): ResponseApdu? {
        if (FakeLinkLoss.isRead(command)) reads += Triple(attempt, chip.currentEf, FakeLinkLoss.offset(command))
        return null
    }

    /** The offsets read of [fileId] in [attempt]. */
    fun offsets(
        attempt: Int,
        fileId: Int,
    ): List<Int> = reads.filter { it.first == attempt && it.second == fileId }.map { it.third }
}

/** Flips one byte of the first READ BINARY answer at offset 0 of [fileId], [times] times. */
internal class FakeFlippedByte(
    private val fileId: Int,
    var times: Int = 1,
) : FakeCommandHandler {
    override fun handle(
        chip: FakeChip,
        command: CommandApdu,
    ): ResponseApdu? {
        if (times <= 0 || !FakeLinkLoss.isRead(command) || chip.currentEf != fileId || FakeLinkLoss.offset(command) != 0) return null
        times--
        val real = chip.answerInner(command, this)
        val data = real.data.copyOf()
        data[data.size - 1] = (data[data.size - 1].toInt() xor 0x01).toByte()
        return ResponseApdu(data, real.sw)
    }
}

/** A new link to [chip] for one attempt: the chip powers up again, and the link drops at its first [dropFirst] commands. */
internal class FakeAttemptLink(
    private val chip: FakeChip,
    private var dropFirst: Int = 0,
) : ApduTransport by chip {
    init {
        chip.powerCycle()
    }

    override suspend fun transceive(command: ByteArray): ByteArray {
        if (dropFirst > 0) {
            dropFirst--
            throw ApduTransportException(NfcTagLost)
        }
        return chip.transceive(command)
    }
}

/** Keeps every update and ending, in order. Thread-safe enough for the tests: the read never overlaps calls. */
internal class RecordingObserver(
    private val throwing: Boolean = false,
) : ChipReadObserver {
    val updates = mutableListOf<ChipReadUpdate>()
    val endings = mutableListOf<ChipReadEnding>()
    val calls = mutableListOf<Any>()

    override fun onUpdate(update: ChipReadUpdate) {
        updates += update
        calls += update
        if (throwing) error("observer failure")
    }

    override fun onEnding(ending: ChipReadEnding) {
        endings += ending
        calls += ending
        if (throwing) error("observer failure")
    }
}
