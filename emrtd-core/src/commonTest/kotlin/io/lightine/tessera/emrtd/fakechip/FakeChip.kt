package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.types.errors.NfcTagLost

/**
 * Handles one command before the fake chip's plain file commands do: protocol cards (BAC, PACE,
 * chip and active authentication, secure messaging) each add their own handler file next to this
 * one. Returns `null` to let the next handler, and then the core, answer.
 */
internal fun interface FakeCommandHandler {
    fun handle(
        chip: FakeChip,
        command: CommandApdu,
    ): ResponseApdu?
}

/** The files of a fake chip: master-file EFs and, per application AID, its EFs by file identifier. */
internal class FakeFileSystem(
    val masterFiles: Map<Int, ByteArray> = emptyMap(),
    val applications: Map<List<Byte>, Map<Int, ByteArray>> = emptyMap(),
)

/**
 * A chip in memory that answers APDUs: an [ApduTransport] for tests, with no device.
 *
 * The core implements a plain (unprotected) chip: SELECT by AID and by file identifier, READ BINARY
 * by offset and by short file identifier, and the ISO/IEC 7816-4 status-word rules. Protocol
 * handlers ([handlers]) run first and can take over any command.
 *
 * Data comes from the SDK's own generator at test time ([FakeChipData]); no binary chip file is
 * ever committed.
 */
internal class FakeChip(
    val files: FakeFileSystem,
    override val maxCommandLength: Int = SHORT_MAX_COMMAND,
    override val supportsExtendedLength: Boolean = false,
    val handlers: List<FakeCommandHandler> = emptyList(),
) : ApduTransport {
    /** Every command received, in order, as bytes. */
    val received = mutableListOf<ByteArray>()

    /** When set, the command with this index (0-based) throws [ApduTransportException] with [NfcTagLost]. */
    var loseTagAtCommand: Int? = null

    /** The current DF: `null` for the master file, else the selected application's AID. */
    var currentApplication: List<Byte>? = null
        private set

    /** The current EF's file identifier, or `null`. */
    var currentEf: Int? = null
        private set

    override suspend fun transceive(command: ByteArray): ByteArray {
        check(command.size <= maxCommandLength) { "command of ${command.size} bytes exceeds $maxCommandLength" }
        val index = received.size
        received += command.copyOf()
        if (loseTagAtCommand == index) throw ApduTransportException(NfcTagLost)
        val apdu = CommandApdu.decode(command) ?: return sw(StatusWord.WRONG_LENGTH)
        if (!supportsExtendedLength && !apdu.fitsShort) return sw(StatusWord.WRONG_LENGTH)
        handlers.forEach { h -> h.handle(this, apdu)?.let { return it.encode() } }
        return core(apdu).encode()
    }

    /**
     * Answers [command] with the handlers after [after] and then the core, without recording it:
     * secure messaging unwraps a protected command and passes the plain one on here.
     */
    fun answerInner(
        command: CommandApdu,
        after: FakeCommandHandler,
    ): ResponseApdu {
        handlers.drop(handlers.indexOf(after) + 1).forEach { h -> h.handle(this, command)?.let { return it } }
        return core(command)
    }

    private fun core(c: CommandApdu): ResponseApdu {
        if (c.cla and CommandApdu.CLA_CHAINING_BIT != 0) return status(StatusWord.CHAINING_NOT_SUPPORTED)
        if (c.cla != 0x00) return status(StatusWord.CLA_NOT_SUPPORTED)
        return when (c.ins) {
            ChipCommands.INS_SELECT -> select(c)
            ChipCommands.INS_READ_BINARY -> readBinary(c)
            else -> status(StatusWord.INS_NOT_SUPPORTED)
        }
    }

    private fun select(c: CommandApdu): ResponseApdu {
        if (c.p2 != ChipCommands.SELECT_NO_FCI) return status(StatusWord.INCORRECT_P1_P2)
        return when (c.p1) {
            ChipCommands.SELECT_BY_AID -> {
                val aid = c.data.toList()
                if (aid !in files.applications) return status(StatusWord.FILE_NOT_FOUND)
                currentApplication = aid
                currentEf = null
                status(StatusWord.SUCCESS)
            }

            0x00, ChipCommands.SELECT_EF_UNDER_CURRENT_DF -> {
                if (c.data.size != 2) return status(StatusWord.WRONG_LENGTH)
                val fid = ((c.data[0].toInt() and 0xFF) shl 8) or (c.data[1].toInt() and 0xFF)
                if (c.p1 == 0x00 && fid == MF_FID) {
                    currentApplication = null
                    currentEf = null
                    return status(StatusWord.SUCCESS)
                }
                if (fid !in currentFiles()) return status(StatusWord.FILE_NOT_FOUND)
                currentEf = fid
                status(StatusWord.SUCCESS)
            }

            else -> {
                status(StatusWord.INCORRECT_P1_P2)
            }
        }
    }

    private fun readBinary(c: CommandApdu): ResponseApdu {
        val ne = c.ne ?: return status(StatusWord.WRONG_LENGTH)
        val offset: Int
        if (c.p1 and 0x80 != 0) {
            if (c.p1 and 0x60 != 0) return status(StatusWord.INCORRECT_P1_P2)
            val sfi = c.p1 and 0x1F
            val fid = currentFiles().keys.firstOrNull { it and 0x1F == sfi } ?: return status(StatusWord.FILE_NOT_FOUND)
            currentEf = fid
            offset = c.p2
        } else {
            offset = (c.p1 shl 8) or c.p2
        }
        val fid = currentEf ?: return status(StatusWord.NO_CURRENT_EF)
        val content = currentFiles().getValue(fid)
        if (offset > content.size) return status(StatusWord.WRONG_P1_P2)
        val n = minOf(ne, content.size - offset)
        val data = content.copyOfRange(offset, offset + n)
        return ResponseApdu(data, if (n < ne) StatusWord.END_OF_FILE_BEFORE_NE else StatusWord.SUCCESS)
    }

    private fun currentFiles(): Map<Int, ByteArray> = currentApplication?.let { files.applications.getValue(it) } ?: files.masterFiles

    private fun status(sw: Int) = ResponseApdu(ByteArray(0), sw)

    private fun sw(sw: Int) = status(sw).encode()

    companion object {
        /** A short-APDU-only reader: 4 header bytes, Lc, 255 data bytes, Le. */
        const val SHORT_MAX_COMMAND = 261

        /** An extended-length reader: 4 header bytes, 3-byte Lc, 65,535 data bytes, 2-byte Le. */
        const val EXTENDED_MAX_COMMAND = 65_544

        const val MF_FID = 0x3F00
    }
}
