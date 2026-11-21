package io.lightine.tessera.emrtd.internal.tlv

/**
 * Writes BER-TLV data objects, always in the shortest form: Doc 9303 Part 10, section 3.6.6 asks
 * for the shortest tag and length encodings, and Part 11, section 9.4.1 for unsigned integers with
 * no leading zero bytes ([minimalUnsigned]).
 */
internal object TlvWriter {
    /** The tag's bytes. [tag] is the tag bytes as one number, for example `0x7F49`. */
    fun tag(tag: Int): ByteArray {
        require(tag in 1..0xFFFFFF) { "tag out of range" }
        return when {
            tag > 0xFFFF -> byteArrayOf((tag shr 16).toByte(), (tag shr 8).toByte(), tag.toByte())
            tag > 0xFF -> byteArrayOf((tag shr 8).toByte(), tag.toByte())
            else -> byteArrayOf(tag.toByte())
        }
    }

    /** The shortest length encoding of [length]. */
    fun length(length: Int): ByteArray {
        require(length >= 0) { "negative length" }
        return when {
            length < 0x80 -> {
                byteArrayOf(length.toByte())
            }

            length <= 0xFF -> {
                byteArrayOf(0x81.toByte(), length.toByte())
            }

            length <= 0xFFFF -> {
                byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
            }

            length <= 0xFFFFFF -> {
                byteArrayOf(0x83.toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte())
            }

            else -> {
                byteArrayOf(
                    0x84.toByte(),
                    (length shr 24).toByte(),
                    (length shr 16).toByte(),
                    (length shr 8).toByte(),
                    length.toByte(),
                )
            }
        }
    }

    /** Number of bytes the shortest length encoding of [length] takes. */
    fun lengthOfLength(length: Int): Int =
        when {
            length < 0x80 -> 1
            length <= 0xFF -> 2
            length <= 0xFFFF -> 3
            length <= 0xFFFFFF -> 4
            else -> 5
        }

    /** One data object: tag, shortest length, value. */
    fun encode(
        tag: Int,
        value: ByteArray,
    ): ByteArray = tag(tag) + length(value.size) + value

    /** A constructed data object whose value is the concatenated [children] encodings. */
    fun constructed(
        tag: Int,
        vararg children: ByteArray,
    ): ByteArray {
        var value = ByteArray(0)
        children.forEach { value += it }
        return encode(tag, value)
    }

    /**
     * An unsigned big-endian integer with no leading zero bytes (Doc 9303 Part 11, section 9.4.1).
     * Zero is one `00` byte.
     */
    fun minimalUnsigned(bytes: ByteArray): ByteArray {
        val first = bytes.indexOfFirst { it.toInt() != 0 }
        return if (first < 0) byteArrayOf(0) else bytes.copyOfRange(first, bytes.size)
    }
}
