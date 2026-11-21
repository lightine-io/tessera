package io.lightine.tessera.emrtd.internal.tlv

/**
 * Reads BER-TLV data objects (ISO/IEC 7816-4, section 5.2; X.690 tag and length forms) from chip
 * data, which may be hostile.
 *
 * Limits that keep hostile input harmless:
 * - every length is checked against the bytes that exist before anything is read or copied, so a
 *   huge declared length fails as [TlvFailure.TRUNCATED_VALUE] without allocating;
 * - tags have at most [MAX_TAG_BYTES] bytes and lengths at most 4 length bytes;
 * - nesting deeper than [maxDepth] fails as [TlvFailure.TOO_DEEP], so recursion stays bounded.
 *
 * Lengths: Doc 9303 Part 10, section 3.6.6 asks the writer for the shortest form. Real chips do
 * not always follow it, so by default the reader accepts a longer form; with [strictLengths] it
 * rejects one as [TlvFailure.NON_MINIMAL_LENGTH]. A tag's first subsequent byte must not be `80`.
 */
internal class TlvParser(
    val strictLengths: Boolean = false,
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {
    /** All data objects in [bytes], one after another, at depth 0. */
    fun parseAll(bytes: ByteArray): List<Tlv> = parseRange(bytes, 0, bytes.size, 0)

    /** Exactly one data object filling all of [bytes]; bytes after it are [TlvFailure.TRAILING_BYTES]. */
    fun parseSingle(bytes: ByteArray): Tlv {
        val tlv = parseAt(bytes, 0, bytes.size, 0)
        if (tlv.end != bytes.size) throw TlvException(TlvFailure.TRAILING_BYTES, tlv.end)
        return tlv
    }

    /** The first data object at the start of [bytes]; bytes after it are ignored. */
    fun parseFirst(bytes: ByteArray): Tlv = parseAt(bytes, 0, bytes.size, 0)

    /**
     * Reads only the tag and length at the start of [bytes], which may be a file's first bytes:
     * returns the total encoded length (header plus value) the object declares, without checking
     * that the value is there. Used to learn a file's size from its first chunk.
     */
    fun declaredTotalLength(bytes: ByteArray): Int {
        val (_, afterTag) = readTag(bytes, 0, bytes.size)
        val (length, afterLength) = readLength(bytes, afterTag, bytes.size)
        val total = afterLength.toLong() + length
        if (total > Int.MAX_VALUE) throw TlvException(TlvFailure.LENGTH_TOO_LONG, afterTag)
        return total.toInt()
    }

    internal fun parseRange(
        bytes: ByteArray,
        from: Int,
        to: Int,
        depth: Int,
    ): List<Tlv> {
        val out = ArrayList<Tlv>()
        var pos = from
        while (pos < to) {
            val tlv = parseAt(bytes, pos, to, depth)
            out.add(tlv)
            pos = tlv.end
        }
        return out
    }

    private fun parseAt(
        bytes: ByteArray,
        offset: Int,
        limit: Int,
        depth: Int,
    ): Tlv {
        if (depth > maxDepth) throw TlvException(TlvFailure.TOO_DEEP, offset)
        val (tag, afterTag) = readTag(bytes, offset, limit)
        val (length, valueOffset) = readLength(bytes, afterTag, limit)
        if (length > limit - valueOffset) throw TlvException(TlvFailure.TRUNCATED_VALUE, afterTag)
        return Tlv(bytes, tag, offset, valueOffset, length, depth, this)
    }

    private fun readTag(
        bytes: ByteArray,
        offset: Int,
        limit: Int,
    ): Pair<Int, Int> {
        if (offset >= limit) throw TlvException(TlvFailure.TRUNCATED_TAG, offset)
        val first = bytes[offset].toInt() and 0xFF
        if (first == 0x00 || first == 0xFF) throw TlvException(TlvFailure.INVALID_TAG, offset)
        var tag = first
        var pos = offset + 1
        if ((first and 0x1F) != 0x1F) return tag to pos
        // High tag number form: subsequent bytes while bit 8 is set.
        var count = 1
        var subsequent = 0
        while (true) {
            if (pos >= limit) throw TlvException(TlvFailure.TRUNCATED_TAG, offset)
            val b = bytes[pos].toInt() and 0xFF
            if (subsequent == 0 && (b and 0x7F) == 0) throw TlvException(TlvFailure.NON_MINIMAL_TAG, offset)
            if (++count > MAX_TAG_BYTES) throw TlvException(TlvFailure.TAG_TOO_LONG, offset)
            tag = (tag shl 8) or b
            subsequent++
            pos++
            if ((b and 0x80) == 0) break
        }
        // Unlike DER, a number below 31 in the two-byte form is allowed: ICAO's LDS uses tags such as
        // 5F01 and 5F0E (Doc 9303 Part 10, section 4.6).
        return tag to pos
    }

    private fun readLength(
        bytes: ByteArray,
        offset: Int,
        limit: Int,
    ): Pair<Int, Int> {
        if (offset >= limit) throw TlvException(TlvFailure.TRUNCATED_LENGTH, offset)
        val first = bytes[offset].toInt() and 0xFF
        if (first < 0x80) return first to offset + 1
        if (first == 0x80) throw TlvException(TlvFailure.INDEFINITE_LENGTH, offset)
        val n = first and 0x7F
        if (n > 4) throw TlvException(TlvFailure.LENGTH_TOO_LONG, offset)
        if (offset + n >= limit) throw TlvException(TlvFailure.TRUNCATED_LENGTH, offset)
        var length = 0L
        for (i in 1..n) length = (length shl 8) or (bytes[offset + i].toLong() and 0xFF)
        if (length > Int.MAX_VALUE) throw TlvException(TlvFailure.LENGTH_TOO_LONG, offset)
        if (strictLengths && n != TlvWriter.lengthOfLength(length.toInt()) - 1) {
            throw TlvException(TlvFailure.NON_MINIMAL_LENGTH, offset)
        }
        return length.toInt() to offset + 1 + n
    }

    internal companion object {
        /** ICAO data uses tags of one or two bytes; three leaves room for ISO/IEC 7816 and biometric data. */
        const val MAX_TAG_BYTES = 3

        /** LDS files nest a few levels; biometric templates a few more. */
        const val DEFAULT_MAX_DEPTH = 16
    }
}
