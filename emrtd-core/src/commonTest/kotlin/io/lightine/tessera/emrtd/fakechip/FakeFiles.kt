package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.apdu.u
import io.lightine.tessera.emrtd.internal.tlv.TlvParser

/** Handles READ BINARY with the offset data object (B1), leaving the fake-chip core unchanged. */
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
        val size = minOf(command.ne, content.size - start)
        val data = content.copyOfRange(start, start + size)
        return ResponseApdu(data, if (size < command.ne) StatusWord.END_OF_FILE_BEFORE_NE else StatusWord.SUCCESS)
    }

    private fun response(statusWord: Int) = ResponseApdu(ByteArray(0), statusWord)

    private const val OFFSET_TAG = 0x54
}
