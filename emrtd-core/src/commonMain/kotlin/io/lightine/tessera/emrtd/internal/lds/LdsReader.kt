package io.lightine.tessera.emrtd.internal.lds

import io.lightine.tessera.emrtd.internal.tlv.Tlv
import io.lightine.tessera.emrtd.internal.tlv.TlvException
import io.lightine.tessera.emrtd.internal.tlv.TlvFailure
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import io.lightine.tessera.emrtd.lds.ChipDataElement
import io.lightine.tessera.emrtd.lds.LdsParseResult
import io.lightine.tessera.types.errors.ChipMalformedFile

/** Thrown inside the LDS views' parsers; [LdsReader.guard] turns it into a typed failure. */
internal class LdsException(
    val error: ChipMalformedFile,
) : IllegalStateException(error.description)

/** Shared helpers of the text data group views. Nothing here logs or keeps file content in an error. */
internal object LdsReader {
    const val FILE_ID_EF_COM = 0x011E

    fun fileIdOfDataGroup(number: Int): Int = 0x0100 + number

    fun fail(
        fileId: Int,
        reason: ChipMalformedFile.Reason,
    ): Nothing = throw LdsException(ChipMalformedFile(fileId, reason))

    /** Runs [block]; maps a [TlvException] and an [LdsException] to a typed failure. Never throws for bad input. */
    fun <T> guard(
        fileId: Int,
        block: () -> T,
    ): LdsParseResult<T> =
        try {
            LdsParseResult.Success(block())
        } catch (e: LdsException) {
            LdsParseResult.Failure(e.error)
        } catch (e: TlvException) {
            val reason =
                if (e.failure == TlvFailure.MISSING_TAG) {
                    ChipMalformedFile.Reason.MISSING_ELEMENT
                } else {
                    ChipMalformedFile.Reason.NOT_VALID_TLV
                }
            LdsParseResult.Failure(ChipMalformedFile(fileId, reason))
        }

    /** The one data object that fills [bytes], which must be a constructed object with [expectedTag]. */
    fun open(
        bytes: ByteArray,
        fileId: Int,
        expectedTag: Int,
    ): Tlv {
        val outer = TlvParser().parseSingle(bytes)
        if (outer.tag != expectedTag || !outer.isConstructed) fail(fileId, ChipMalformedFile.Reason.UNEXPECTED_FILE_TAG)
        return outer
    }

    fun elementsOf(tlv: Tlv): List<ChipDataElement> = tlv.children().map { ChipDataElement(it.tag, it.value()) }

    /** Splits a tag list (tag `5C`) into its tags. A truncated last tag is an invalid element. */
    fun tagList(
        value: ByteArray,
        fileId: Int,
    ): List<Int> {
        val tags = ArrayList<Int>()
        var i = 0
        while (i < value.size) {
            val first = value[i].toInt() and 0xFF
            i++
            var tag = first
            if ((first and 0x1F) == 0x1F) {
                while (true) {
                    if (i >= value.size) fail(fileId, ChipMalformedFile.Reason.INVALID_ELEMENT)
                    val b = value[i].toInt() and 0xFF
                    i++
                    tag = (tag shl 8) or b
                    if ((b and 0x80) == 0) break
                    if (tag > 0xFFFFFF) fail(fileId, ChipMalformedFile.Reason.INVALID_ELEMENT)
                }
            }
            tags.add(tag)
        }
        return tags
    }

    /**
     * Digits of a date or time value: [asciiLength] ASCII digits, or half as many bytes of packed
     * BCD. `null` for any other length or a byte that is not a digit.
     */
    fun digits(
        value: ByteArray,
        asciiLength: Int,
    ): String? {
        val sb = StringBuilder()
        when (value.size) {
            asciiLength -> {
                for (b in value) {
                    val c = b.toInt() and 0xFF
                    if (c < '0'.code || c > '9'.code) return null
                    sb.append(c.toChar())
                }
            }

            asciiLength / 2 -> {
                for (b in value) {
                    val hi = (b.toInt() and 0xFF) shr 4
                    val lo = b.toInt() and 0x0F
                    if (hi > 9 || lo > 9) return null
                    sb.append(('0'.code + hi).toChar()).append(('0'.code + lo).toChar())
                }
            }

            else -> {
                return null
            }
        }
        return sb.toString()
    }
}
