package io.lightine.tessera.emrtd.internal.files

import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.emrtd.internal.apdu.ApduSender
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.tlv.TlvException
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import kotlin.coroutines.cancellation.CancellationException

/** Selects LDS files and reads their BER-TLV bytes without interpreting their contents. */
internal class FileReader(
    private val sender: ApduSender,
) {
    suspend fun selectApplication(extendedLengthAllowed: Boolean = false) =
        withTypedFailures {
            select(ChipCommands.selectApplication(LdsIds.LDS1_AID), null, extendedLengthAllowed)
        }

    suspend fun selectMasterFile(extendedLengthAllowed: Boolean = false) =
        withTypedFailures {
            select(ChipCommands.selectMasterFile(), MASTER_FILE_ID, extendedLengthAllowed)
        }

    suspend fun selectFile(
        fid: Int,
        extendedLengthAllowed: Boolean = false,
    ) = withTypedFailures {
        select(ChipCommands.selectFile(fid), fid, extendedLengthAllowed)
    }

    /** Selects [fid] under the current DF, then reads the whole file. */
    suspend fun readFile(
        fid: Int,
        extendedLengthAllowed: Boolean = false,
    ): ByteArray =
        withTypedFailures {
            select(ChipCommands.selectFile(fid), fid, extendedLengthAllowed)
            readSelectedFile(fid, extendedLengthAllowed)
        }

    /**
     * Reads a file by its short file identifier. The first READ BINARY selects the EF; later chunks
     * use the current EF and its absolute offset.
     */
    suspend fun readFileBySfi(
        sfi: Int,
        extendedLengthAllowed: Boolean = false,
    ): ByteArray =
        withTypedFailures {
            val firstSize = chunkSize(0, 0, extendedLengthAllowed, sfi)
            val first = sender.send(ChipCommands.readBinaryBySfi(sfi, 0, firstSize), extendedLengthAllowed)
            readAfterFirst(first, firstSize, extendedLengthAllowed)
        }

    private suspend fun readSelectedFile(
        fid: Int?,
        extendedLengthAllowed: Boolean,
    ): ByteArray {
        val firstSize = chunkSize(0, 0, extendedLengthAllowed)
        val first = sendRead(0, firstSize, extendedLengthAllowed)
        return readAfterFirst(first, firstSize, extendedLengthAllowed, fid = fid)
    }

    private suspend fun readAfterFirst(
        first: ResponseApdu,
        firstRequested: Int,
        extendedLengthAllowed: Boolean,
        fid: Int? = null,
    ): ByteArray {
        validateReadStatus(first, fid)
        val firstBytes = first.data
        if (firstBytes.size > firstRequested) throw MalformedFile(null)
        val totalLength =
            try {
                TlvParser().declaredTotalLength(firstBytes)
            } catch (e: TlvException) {
                throw MalformedFile(e.failure)
            }
        if (firstBytes.size > totalLength) throw MalformedFile(null)
        if (first.sw == StatusWord.END_OF_FILE_BEFORE_NE || firstBytes.size == totalLength) return firstBytes

        var content = firstBytes
        while (content.size < totalLength) {
            val offset = content.size
            val requested = chunkSize(offset, totalLength - offset, extendedLengthAllowed)
            val response = sendRead(offset, requested, extendedLengthAllowed)
            validateReadStatus(response, fid)
            if (response.data.size > requested) throw MalformedFile(null)
            content += response.data
            if (response.sw == StatusWord.END_OF_FILE_BEFORE_NE) return content
            if (response.data.isEmpty()) throw FileProtocolFailure()
        }
        return content
    }

    private suspend fun sendRead(
        offset: Int,
        ne: Int,
        extendedLengthAllowed: Boolean,
    ): ResponseApdu {
        val command =
            if (offset <= MAX_SHORT_OFFSET) {
                ChipCommands.readBinary(offset, ne)
            } else {
                ChipCommands.readBinaryExtendedOffset(offset, ne)
            }
        return sender.send(command, extendedLengthAllowed)
    }

    private fun chunkSize(
        offset: Int,
        remaining: Int,
        extendedLengthAllowed: Boolean,
        sfi: Int? = null,
    ): Int {
        val extendedCommand =
            if (offset > MAX_SHORT_OFFSET) {
                ChipCommands.readBinaryExtendedOffset(offset, MAX_EXTENDED_CHUNK)
            } else if (sfi != null && offset == 0) {
                ChipCommands.readBinaryBySfi(sfi, offset, MAX_EXTENDED_CHUNK)
            } else {
                ChipCommands.readBinary(offset, MAX_EXTENDED_CHUNK)
            }
        val canUseExtended =
            extendedLengthAllowed &&
                sender.supportsExtendedLength &&
                extendedCommand.encodedLength() <= sender.maxCommandLength
        val chunk =
            if (canUseExtended && remaining > CommandApdu.MAX_SHORT_NE) {
                minOf(remaining, MAX_EXTENDED_CHUNK)
            } else if (remaining <= 0) {
                DEFAULT_SHORT_CHUNK
            } else {
                minOf(remaining, DEFAULT_SHORT_CHUNK)
            }
        val shortCommand =
            if (offset > MAX_SHORT_OFFSET) {
                ChipCommands.readBinaryExtendedOffset(offset, chunk)
            } else if (sfi != null && offset == 0) {
                ChipCommands.readBinaryBySfi(sfi, offset, chunk)
            } else {
                ChipCommands.readBinary(offset, chunk)
            }
        if (shortCommand.encodedLength() > sender.maxCommandLength) {
            throw FileProtocolFailure()
        }
        return chunk
    }

    private suspend fun select(
        command: CommandApdu,
        fid: Int?,
        extendedLengthAllowed: Boolean,
    ) {
        val response = sender.send(command, extendedLengthAllowed)
        when (response.sw) {
            StatusWord.SUCCESS -> Unit
            StatusWord.FILE_NOT_FOUND -> throw FileNotFound(fid)
            StatusWord.SECURITY_STATUS_NOT_SATISFIED -> throw FileSecurityStatusNotSatisfied(fid)
            else -> throw FileStatusFailure(response.sw)
        }
    }

    private fun validateReadStatus(
        response: ResponseApdu,
        fid: Int?,
    ) {
        when (response.sw) {
            StatusWord.SUCCESS, StatusWord.END_OF_FILE_BEFORE_NE -> Unit
            StatusWord.FILE_NOT_FOUND -> throw FileNotFound(fid)
            StatusWord.SECURITY_STATUS_NOT_SATISFIED -> throw FileSecurityStatusNotSatisfied(fid)
            else -> throw FileStatusFailure(response.sw)
        }
    }

    private suspend inline fun <T> withTypedFailures(crossinline block: suspend () -> T): T =
        try {
            block()
        } catch (e: ApduTransportException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileReadFailure) {
            throw e
        } catch (e: Exception) {
            throw FileProtocolFailure()
        }

    private companion object {
        /** Use a conservative short Le; some chips reject the short encoding of 256 (`00`). */
        const val DEFAULT_SHORT_CHUNK = 0xDF
        const val MAX_EXTENDED_CHUNK = CommandApdu.MAX_EXTENDED_NE - 1
        const val MAX_SHORT_OFFSET = 0x7FFF
        const val MASTER_FILE_ID = 0x3F00
    }
}
