package io.lightine.tessera.emrtd.internal.files

import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.emrtd.internal.apdu.ApduSender
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.apdu.MalformedResponse
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.cms.ByteSink
import io.lightine.tessera.emrtd.internal.sm.SecureMessagingException
import io.lightine.tessera.emrtd.internal.tlv.TlvException
import io.lightine.tessera.emrtd.internal.tlv.TlvFailure
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import kotlin.coroutines.cancellation.CancellationException

/**
 * Selects LDS files and reads their BER-TLV bytes without interpreting their contents.
 *
 * The length a file's first bytes declare is untrusted: a file declaring more than
 * [maxFileLength] bytes is refused as [MalformedFile] with [TlvFailure.LENGTH_TOO_LONG] before
 * any further read. One limit serves every file, because the reader does not know what a file
 * holds; the default, [DEFAULT_MAX_FILE_LENGTH], leaves room for the largest image groups (DG2 to
 * DG4 with several high-resolution images) while keeping a hostile declaration from making the
 * reader buffer or poll for gigabytes. A caller that reads only small files may pass less.
 *
 * A file's content is its outer BER-TLV: when a chip returns more bytes than that TLV declares
 * (some chips send a short file's whole padded length with 6282), the reader keeps exactly the
 * declared length, drops the trailing bytes and stops reading.
 *
 * Offsets up to 32 767 are read with READ BINARY `B0`, and a `B0` read never reaches past that
 * offset, because some chips do not let `B0` and `B1` overlap (Part 10, section 3.6.3.1, Note 1).
 * Later offsets use `B1` with the offset in DO'54'; its answer carries the bytes in DO'53'
 * (Part 10, Table 6), and only that object's value is kept.
 */
internal class FileReader(
    private val sender: ApduSender,
    private val maxFileLength: Int = DEFAULT_MAX_FILE_LENGTH,
) {
    /** Told of each part of a file as it arrives: the file, the bytes so far and the declared total. */
    fun interface ChunkListener {
        fun chunk(
            fid: Int?,
            bytesRead: Int,
            bytesTotal: Int?,
        )
    }

    var listener: ChunkListener? = null

    // The bytes of the file being read; left in place when a read ends early, for a later resume.
    private var inProgress: ByteSink? = null
    private var inProgressFid: Int? = null

    /**
     * The bytes of the last file read so far, when that read ended before the file was complete
     * (a lost link, for example), with the file it belongs to; `null` after a complete read.
     */
    fun unfinished(): Pair<Int, ByteArray>? {
        val sink = inProgress ?: return null
        val fid = inProgressFid ?: return null
        return if (sink.size > 0) fid to sink.toByteArray() else null
    }

    /**
     * Selects [fid] and reads the rest of it after [prefix], the bytes an earlier read of the same
     * file received from offset 0. The prefix holds the file's outer TLV header, so the length is
     * known; the result is the prefix followed by the rest.
     */
    suspend fun resumeFile(
        fid: Int,
        prefix: ByteArray,
        extendedLengthAllowed: Boolean = false,
    ): ByteArray =
        withTypedFailures {
            bytesReadSoFar = 0
            select(ChipCommands.selectFile(fid), fid, extendedLengthAllowed)
            val totalLength =
                try {
                    TlvParser().declaredTotalLength(prefix)
                } catch (e: TlvException) {
                    throw MalformedFile(e.failure)
                }
            if (totalLength > maxFileLength) throw MalformedFile(TlvFailure.LENGTH_TOO_LONG)
            if (prefix.size >= totalLength) return@withTypedFailures prefix.copyOfRange(0, totalLength)
            val content = start(fid)
            content.write(prefix)
            bytesReadSoFar = content.size
            readRest(content, totalLength, extendedLengthAllowed, fid)
        }

    private fun start(fid: Int?): ByteSink {
        val sink = ByteSink()
        inProgress = sink
        inProgressFid = fid
        return sink
    }

    private fun done(bytes: ByteArray): ByteArray {
        inProgress = null
        inProgressFid = null
        return bytes
    }

    suspend fun selectApplication(extendedLengthAllowed: Boolean = false) =
        withTypedFailures {
            select(ChipCommands.selectApplication(LdsIds.LDS1_AID), null, extendedLengthAllowed)
        }

    /** How many bytes of the file the last read had received when it ended, for telemetry. */
    var bytesReadSoFar: Int = 0
        private set

    /**
     * Selects the master file: first as Part 10, Table 1 gives it (`00 A4 00 0C`, no data), then,
     * when the chip answers that with any status other than 9000, by its identifier
     * (`00 A4 00 0C 02 3F00`), which many chips expect instead. Throws the second answer's failure
     * when the chip takes neither form; a refused SELECT leaves the current DF as it was.
     */
    suspend fun selectMasterFile(extendedLengthAllowed: Boolean = false) =
        withTypedFailures {
            try {
                select(ChipCommands.selectMasterFile(), MASTER_FILE_ID, extendedLengthAllowed)
            } catch (e: FileReadFailure) {
                select(ChipCommands.selectMasterFileById(), MASTER_FILE_ID, extendedLengthAllowed)
            }
        }

    suspend fun selectFile(
        fid: Int,
        extendedLengthAllowed: Boolean = false,
    ) = withTypedFailures {
        bytesReadSoFar = 0
        select(ChipCommands.selectFile(fid), fid, extendedLengthAllowed)
    }

    /** Selects [fid] under the current DF, then reads the whole file. */
    suspend fun readFile(
        fid: Int,
        extendedLengthAllowed: Boolean = false,
    ): ByteArray =
        withTypedFailures {
            bytesReadSoFar = 0
            inProgress = null
            select(ChipCommands.selectFile(fid), fid, extendedLengthAllowed)
            readSelectedFile(fid, extendedLengthAllowed)
        }

    /** Reads the whole of the current EF, which a [selectFile] of [fid] made current. */
    suspend fun readCurrentFile(
        fid: Int,
        extendedLengthAllowed: Boolean = false,
    ): ByteArray =
        withTypedFailures {
            bytesReadSoFar = 0
            inProgress = null
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
            bytesReadSoFar = 0
            val firstSize = chunkSize(0, 0, extendedLengthAllowed, sfi)
            val first = sender.send(ChipCommands.readBinaryBySfi(sfi, 0, firstSize), extendedLengthAllowed)
            readAfterFirst(first, firstSize, extendedLengthAllowed)
        }

    private suspend fun readSelectedFile(
        fid: Int?,
        extendedLengthAllowed: Boolean,
    ): ByteArray {
        val firstSize = chunkSize(0, 0, extendedLengthAllowed)
        inProgressFid = fid
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
        bytesReadSoFar = firstBytes.size
        val totalLength =
            try {
                TlvParser().declaredTotalLength(firstBytes)
            } catch (e: TlvException) {
                throw MalformedFile(e.failure)
            }
        if (totalLength > maxFileLength) throw MalformedFile(TlvFailure.LENGTH_TOO_LONG)
        // Bytes past the declared end of the outer TLV are not part of the file (trailing fill on
        // the chip): keep exactly the declared length and stop reading.
        if (firstBytes.size >= totalLength) {
            notify(fid, totalLength, totalLength)
            return done(firstBytes.copyOfRange(0, totalLength))
        }
        if (first.sw == StatusWord.END_OF_FILE_BEFORE_NE) {
            notify(fid, firstBytes.size, totalLength)
            return done(firstBytes)
        }
        val content = start(fid)
        content.write(firstBytes)
        notify(fid, content.size, totalLength)
        return readRest(content, totalLength, extendedLengthAllowed, fid)
    }

    private suspend fun readRest(
        content: ByteSink,
        totalLength: Int,
        extendedLengthAllowed: Boolean,
        fid: Int?,
    ): ByteArray {
        while (content.size < totalLength) {
            val offset = content.size
            val requested = chunkSize(offset, totalLength - offset, extendedLengthAllowed)
            val response = sendRead(offset, requested, extendedLengthAllowed)
            validateReadStatus(response, fid)
            if (response.data.size > requested) throw MalformedFile(null)
            val data = if (offset > MAX_SHORT_OFFSET) offsetAnswerData(response.data) else response.data
            if (data.size > totalLength - content.size) {
                // This answer reaches past the declared end: keep what the TLV needs and stop.
                content.write(data, 0, totalLength - content.size)
                bytesReadSoFar = content.size
                notify(fid, content.size, totalLength)
                break
            }
            content.write(data)
            bytesReadSoFar = content.size
            notify(fid, content.size, totalLength)
            if (response.sw == StatusWord.END_OF_FILE_BEFORE_NE) break
            if (data.isEmpty()) throw FileProtocolFailure()
        }
        return done(content.toByteArray())
    }

    private fun notify(
        fid: Int?,
        bytesRead: Int,
        bytesTotal: Int?,
    ) {
        listener?.chunk(fid, bytesRead, bytesTotal)
    }

    /**
     * The file bytes of a `B1` answer: the value of DO'53' (Part 10, Table 6). An empty answer is
     * no bytes; anything else that is not exactly one DO'53' is an unusable answer.
     */
    private fun offsetAnswerData(answer: ByteArray): ByteArray {
        if (answer.isEmpty()) return answer
        val dataObject =
            try {
                TlvParser().parseSingle(answer)
            } catch (e: TlvException) {
                throw FileProtocolFailure()
            }
        if (dataObject.tag != DISCRETIONARY_DATA_TAG) throw FileProtocolFailure()
        return dataObject.value()
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
        val wanted =
            if (canUseExtended && remaining > CommandApdu.MAX_SHORT_NE) {
                minOf(remaining, MAX_EXTENDED_CHUNK)
            } else if (remaining <= 0) {
                DEFAULT_SHORT_CHUNK
            } else {
                minOf(remaining, DEFAULT_SHORT_CHUNK)
            }
        // A B0 read stops at offset 32 767; B1 reads on from 32 768 (Part 10, 3.6.3.1, Note 1).
        // A B1 answer also spends up to 4 bytes of Ne on the DO'53' header (Table 6).
        val chunk =
            when {
                offset <= MAX_SHORT_OFFSET -> minOf(wanted, MAX_SHORT_OFFSET + 1 - offset)
                remaining > 0 -> minOf(wanted + OFFSET_ANSWER_HEADER, chunkLimit(canUseExtended && remaining > CommandApdu.MAX_SHORT_NE))
                else -> wanted
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
        } catch (e: SecureMessagingException) {
            // The session ended: the read flow reports it as such, not as a file problem.
            throw e
        } catch (e: MalformedResponse) {
            throw e
        } catch (e: Exception) {
            throw FileProtocolFailure()
        }

    companion object {
        /** The default [maxFileLength]: 4 MiB. */
        const val DEFAULT_MAX_FILE_LENGTH = 4 * 1024 * 1024

        /** Use a conservative short Le; some chips reject the short encoding of 256 (`00`). */
        const val DEFAULT_SHORT_CHUNK = 0xDF
        const val MAX_EXTENDED_CHUNK = CommandApdu.MAX_EXTENDED_NE - 1
        const val MAX_SHORT_OFFSET = 0x7FFF
        const val MASTER_FILE_ID = 0x3F00

        /** DO'53': the discretionary data object that carries a `B1` answer's bytes. */
        const val DISCRETIONARY_DATA_TAG = 0x53

        /** The most a DO'53' header takes: the tag and a three-byte length (`53 82 xx xx`). */
        const val OFFSET_ANSWER_HEADER = 4

        private fun chunkLimit(extended: Boolean): Int = if (extended) MAX_EXTENDED_CHUNK else DEFAULT_SHORT_CHUNK
    }
}
