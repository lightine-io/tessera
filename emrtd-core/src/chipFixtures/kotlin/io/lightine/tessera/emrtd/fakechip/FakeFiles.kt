package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.apdu.u
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter

/**
 * Handles READ BINARY with the offset data object (B1), leaving the fake-chip core unchanged. The
 * answer carries the file bytes in DO'53' (Part 10, Table 6), and the whole answer, header included,
 * fits in Ne.
 */
internal object FakeFiles : FakeCommandHandler {
    override fun handle(
        chip: FakeChip,
        command: CommandApdu,
    ): ResponseApdu? {
        if (command.ins != ChipCommands.INS_READ_BINARY_ODD || command.cla != 0x00) return null
        if (command.p1 != 0 || command.p2 != 0 || command.ne == null) return response(StatusWord.INCORRECT_P1_P2)
        val offset =
            try {
                val object54 = TlvParser().parseSingle(command.data)
                if (object54.tag != OFFSET_TAG || object54.length !in 1..4) return response(StatusWord.WRONG_DATA)
                object54.value().fold(0L) { value, byte -> (value shl 8) or byte.u().toLong() }
            } catch (_: Exception) {
                return response(StatusWord.WRONG_DATA)
            }
        if (offset > Int.MAX_VALUE) return response(StatusWord.WRONG_DATA)

        val fid = chip.currentEf ?: return response(StatusWord.NO_CURRENT_EF)
        val files =
            chip.currentApplication?.let { aid -> chip.files.applications[aid] }
                ?: if (chip.currentApplication == null) chip.files.masterFiles else return response(StatusWord.FILE_NOT_FOUND)
        val content = files[fid] ?: return response(StatusWord.FILE_NOT_FOUND)
        if (offset > content.size) return response(StatusWord.WRONG_P1_P2)
        val start = offset.toInt()
        val room = command.ne - headerSize(command.ne)
        if (room <= 0) return response(StatusWord.WRONG_LENGTH)
        val size = minOf(room, content.size - start)
        val data = TlvWriter.encode(DISCRETIONARY_DATA_TAG, content.copyOfRange(start, start + size))
        return ResponseApdu(data, if (size < room) StatusWord.END_OF_FILE_BEFORE_NE else StatusWord.SUCCESS)
    }

    private fun response(statusWord: Int) = ResponseApdu(ByteArray(0), statusWord)

    /** The DO'53' header for a value that fills the rest of [ne] bytes. */
    private fun headerSize(ne: Int): Int =
        when {
            ne - 2 <= 0x7F -> 2
            ne - 3 <= 0xFF -> 3
            else -> 4
        }

    private const val OFFSET_TAG = 0x54
    private const val DISCRETIONARY_DATA_TAG = 0x53
}
