package io.lightine.tessera.emrtd.internal.asn1

/**
 * Why DER bytes could not be read. Every failure of [DerReader] is one of these, with the offset
 * where it was found: hostile or damaged chip data fails here, never with an index error.
 */
internal enum class Asn1Failure {
    /** The bytes end inside a tag, a length or a value. */
    TRUNCATED,

    /** The tag number does not fit [DerReader.MAX_TAG_NUMBER_BYTES] bytes. */
    TAG_TOO_LONG,

    /** The tag is not in its shortest form. */
    NON_MINIMAL_TAG,

    /** The length byte is `80` (indefinite form), which DER forbids. */
    INDEFINITE_LENGTH,

    /** The length has more than 4 length bytes, or is larger than an `Int`. */
    LENGTH_TOO_LONG,

    /** The length is not in its shortest form, which DER requires. */
    NON_MINIMAL_LENGTH,

    /** Constructed values are nested deeper than the reader's limit. */
    TOO_DEEP,

    /** A different tag, or a primitive/constructed form, than the structure requires. */
    UNEXPECTED_TAG,

    /** Bytes are left after the last expected element. */
    TRAILING_BYTES,

    /** An INTEGER is empty or not in its shortest form. */
    INVALID_INTEGER,

    /** An INTEGER is too large for the number type asked for. */
    INTEGER_TOO_LARGE,

    /** An OBJECT IDENTIFIER is empty, not in its shortest form, cut short, or has an arc too large. */
    INVALID_OID,

    /** A BOOLEAN is not one byte `00` or `FF`. */
    INVALID_BOOLEAN,

    /** A NULL has content. */
    INVALID_NULL,

    /** A BIT STRING has no unused-bits byte, an unused-bits count above 7, or unused bits set. */
    INVALID_BIT_STRING,
}

/** DER bytes could not be read. Carries no data bytes: only the reason and an offset. */
internal class Asn1Exception(
    val failure: Asn1Failure,
    val offset: Int,
) : IllegalStateException("DER: $failure at offset $offset")

/** A tag: its class (0 universal, 1 application, 2 context-specific, 3 private), form and number. */
internal data class DerTag(
    val tagClass: Int,
    val constructed: Boolean,
    val number: Int,
) {
    override fun toString(): String = "DerTag(class=$tagClass, constructed=$constructed, number=$number)"

    internal companion object {
        const val UNIVERSAL = 0
        const val APPLICATION = 1
        const val CONTEXT = 2
        const val PRIVATE = 3

        const val BOOLEAN = 1
        const val INTEGER = 2
        const val BIT_STRING = 3
        const val OCTET_STRING = 4
        const val NULL = 5
        const val OID = 6
        const val UTF8_STRING = 12
        const val SEQUENCE = 16
        const val SET = 17
        const val PRINTABLE_STRING = 19
        const val IA5_STRING = 22
        const val UTC_TIME = 23
        const val GENERALIZED_TIME = 24

        fun universal(
            number: Int,
            constructed: Boolean = false,
        ): DerTag = DerTag(UNIVERSAL, constructed, number)

        fun context(
            number: Int,
            constructed: Boolean,
        ): DerTag = DerTag(CONTEXT, constructed, number)
    }
}

/** One DER element: a view into the source bytes, not a copy. */
internal class DerElement(
    private val source: ByteArray,
    val tag: DerTag,
    /** Offset of the first tag byte in the source. */
    val offset: Int,
    /** Offset of the first value byte in the source. */
    val valueOffset: Int,
    val length: Int,
    val depth: Int,
    private val maxDepth: Int,
) {
    val end: Int get() = valueOffset + length

    /** A copy of the value bytes. */
    fun value(): ByteArray = source.copyOfRange(valueOffset, end)

    /** A copy of the whole encoding (tag, length, value), for example to hash a signed structure. */
    fun encoded(): ByteArray = source.copyOfRange(offset, end)

    /** A reader over the elements inside this constructed element. */
    fun contents(): DerReader {
        if (!tag.constructed) throw Asn1Exception(Asn1Failure.UNEXPECTED_TAG, offset)
        return DerReader(source, valueOffset, end, depth + 1, maxDepth)
    }

    override fun toString(): String = "DerElement($tag, length=$length)"
}

/**
 * Reads DER (X.690, section 10) from chip data, which may be hostile, one element at a time.
 *
 * DER has exactly one encoding per value, so any other form fails: indefinite or longer-than-needed
 * lengths, tags in a longer form, non-minimal INTEGERs and OBJECT IDENTIFIER arcs. Every length is
 * checked against the bytes that exist before anything is copied, so a huge declared length fails as
 * [Asn1Failure.TRUNCATED] without allocating. Nesting deeper than [maxDepth] fails as
 * [Asn1Failure.TOO_DEEP].
 */
internal class DerReader(
    private val source: ByteArray,
    from: Int = 0,
    private val to: Int = source.size,
    private val depth: Int = 0,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {
    private var pos = from

    init {
        require(from in 0..to && to <= source.size) { "range out of bounds" }
        if (depth > maxDepth) throw Asn1Exception(Asn1Failure.TOO_DEEP, from)
    }

    /** True while elements are left. */
    val hasMore: Boolean get() = pos < to

    /** The next element's tag, without reading it; `null` at the end. */
    fun peekTag(): DerTag? = if (pos >= to) null else readHeader(pos).tag

    /** The next element, whatever it is. */
    fun read(): DerElement {
        val e = readHeader(pos)
        pos = e.end
        return e
    }

    /** The next element; it must have [expected] as its tag. */
    fun read(expected: DerTag): DerElement {
        val e = readHeader(pos)
        if (e.tag != expected) throw Asn1Exception(Asn1Failure.UNEXPECTED_TAG, e.offset)
        pos = e.end
        return e
    }

    /** The next element if its tag is [expected], else `null` and nothing is read. */
    fun readOptional(expected: DerTag): DerElement? = if (peekTag() == expected) read(expected) else null

    fun readSequence(): DerReader = read(DerTag.universal(DerTag.SEQUENCE, constructed = true)).contents()

    fun readSet(): DerReader = read(DerTag.universal(DerTag.SET, constructed = true)).contents()

    /** An explicitly tagged `[number]` element: a reader over its contents. */
    fun readExplicit(number: Int): DerReader = read(DerTag.context(number, constructed = true)).contents()

    /** An INTEGER's two's-complement bytes, exactly as encoded. */
    fun readInteger(): ByteArray {
        val e = read(DerTag.universal(DerTag.INTEGER))
        val v = e.value()
        if (v.isEmpty()) throw Asn1Exception(Asn1Failure.INVALID_INTEGER, e.offset)
        if (v.size > 1) {
            val b0 = v[0].toInt() and 0xFF
            val b1 = v[1].toInt() and 0x80
            if ((b0 == 0x00 && b1 == 0) || (b0 == 0xFF && b1 != 0)) {
                throw Asn1Exception(Asn1Failure.INVALID_INTEGER, e.offset)
            }
        }
        return v
    }

    /** A non-negative INTEGER as an unsigned big-endian magnitude with no leading zero byte. */
    fun readUnsignedInteger(): ByteArray {
        val offset = pos
        val v = readInteger()
        if (v[0] < 0) throw Asn1Exception(Asn1Failure.INVALID_INTEGER, offset)
        return if (v.size > 1 && v[0].toInt() == 0) v.copyOfRange(1, v.size) else v
    }

    /** An INTEGER that must fit an `Int`. */
    fun readInt(): Int {
        val offset = pos
        val v = readInteger()
        if (v.size > 4) throw Asn1Exception(Asn1Failure.INTEGER_TOO_LARGE, offset)
        var r = if (v[0] < 0) -1 else 0
        v.forEach { r = (r shl 8) or (it.toInt() and 0xFF) }
        return r
    }

    /** An OBJECT IDENTIFIER in dotted form, for example `0.4.0.127.0.7.2.2.4.2.2`. */
    fun readOid(): String {
        val e = read(DerTag.universal(DerTag.OID))
        return decodeOid(source, e.valueOffset, e.end, e.offset)
    }

    fun readOctetString(): ByteArray = read(DerTag.universal(DerTag.OCTET_STRING)).value()

    /** A BIT STRING's bytes; the number of unused bits in the last byte must be zero or the bits clear. */
    fun readBitString(): DerBitString {
        val e = read(DerTag.universal(DerTag.BIT_STRING))
        if (e.length < 1) throw Asn1Exception(Asn1Failure.INVALID_BIT_STRING, e.offset)
        val unused = source[e.valueOffset].toInt() and 0xFF
        if (unused > 7 || (e.length == 1 && unused != 0)) throw Asn1Exception(Asn1Failure.INVALID_BIT_STRING, e.offset)
        if (unused > 0) {
            val last = source[e.end - 1].toInt() and 0xFF
            if (last and ((1 shl unused) - 1) != 0) throw Asn1Exception(Asn1Failure.INVALID_BIT_STRING, e.offset)
        }
        return DerBitString(source.copyOfRange(e.valueOffset + 1, e.end), unused)
    }

    fun readNull() {
        val e = read(DerTag.universal(DerTag.NULL))
        if (e.length != 0) throw Asn1Exception(Asn1Failure.INVALID_NULL, e.offset)
    }

    fun readBoolean(): Boolean {
        val e = read(DerTag.universal(DerTag.BOOLEAN))
        if (e.length != 1) throw Asn1Exception(Asn1Failure.INVALID_BOOLEAN, e.offset)
        return when (source[e.valueOffset].toInt() and 0xFF) {
            0x00 -> false
            0xFF -> true
            else -> throw Asn1Exception(Asn1Failure.INVALID_BOOLEAN, e.offset)
        }
    }

    /** Fails with [Asn1Failure.TRAILING_BYTES] unless every element was read. */
    fun finish() {
        if (pos < to) throw Asn1Exception(Asn1Failure.TRAILING_BYTES, pos)
    }

    private fun readHeader(offset: Int): DerElement {
        var p = offset
        if (p >= to) throw Asn1Exception(Asn1Failure.TRUNCATED, offset)
        val first = source[p++].toInt() and 0xFF
        val tagClass = first shr 6
        val constructed = (first and 0x20) != 0
        var number = first and 0x1F
        if (number == 0x1F) {
            number = 0
            var count = 0
            while (true) {
                if (p >= to) throw Asn1Exception(Asn1Failure.TRUNCATED, offset)
                val b = source[p++].toInt() and 0xFF
                if (count == 0 && (b and 0x7F) == 0) throw Asn1Exception(Asn1Failure.NON_MINIMAL_TAG, offset)
                if (++count > MAX_TAG_NUMBER_BYTES) throw Asn1Exception(Asn1Failure.TAG_TOO_LONG, offset)
                number = (number shl 7) or (b and 0x7F)
                if ((b and 0x80) == 0) break
            }
            if (number < 0x1F) throw Asn1Exception(Asn1Failure.NON_MINIMAL_TAG, offset)
        }
        if (p >= to) throw Asn1Exception(Asn1Failure.TRUNCATED, offset)
        val lengthOffset = p
        val l0 = source[p++].toInt() and 0xFF
        val length: Int
        if (l0 < 0x80) {
            length = l0
        } else {
            if (l0 == 0x80) throw Asn1Exception(Asn1Failure.INDEFINITE_LENGTH, lengthOffset)
            val n = l0 and 0x7F
            if (n > 4) throw Asn1Exception(Asn1Failure.LENGTH_TOO_LONG, lengthOffset)
            if (p + n > to) throw Asn1Exception(Asn1Failure.TRUNCATED, lengthOffset)
            if ((source[p].toInt() and 0xFF) == 0) throw Asn1Exception(Asn1Failure.NON_MINIMAL_LENGTH, lengthOffset)
            var l = 0L
            repeat(n) { l = (l shl 8) or (source[p++].toLong() and 0xFF) }
            if (l > Int.MAX_VALUE) throw Asn1Exception(Asn1Failure.LENGTH_TOO_LONG, lengthOffset)
            if (l < 0x80) throw Asn1Exception(Asn1Failure.NON_MINIMAL_LENGTH, lengthOffset)
            length = l.toInt()
        }
        if (length > to - p) throw Asn1Exception(Asn1Failure.TRUNCATED, lengthOffset)
        return DerElement(source, DerTag(tagClass, constructed, number), offset, p, length, depth, maxDepth)
    }

    internal companion object {
        /** Tag numbers up to 2^28 - 1: far above any tag in chip data. */
        const val MAX_TAG_NUMBER_BYTES = 4

        /** Certificates and signed data nest about ten levels; 32 leaves room and bounds recursion. */
        const val DEFAULT_MAX_DEPTH = 32

        /** An OID arc has at most this many bytes (56 bits), so it fits a `Long`. */
        const val MAX_OID_ARC_BYTES = 8

        /** An OID body longer than this is not chip data. */
        const val MAX_OID_LENGTH = 128

        private fun decodeOid(
            bytes: ByteArray,
            from: Int,
            to: Int,
            elementOffset: Int,
        ): String {
            if (from == to || to - from > MAX_OID_LENGTH) throw Asn1Exception(Asn1Failure.INVALID_OID, elementOffset)
            val arcs = ArrayList<Long>()
            var p = from
            while (p < to) {
                var value = 0L
                var count = 0
                while (true) {
                    if (p >= to) throw Asn1Exception(Asn1Failure.INVALID_OID, elementOffset)
                    val b = bytes[p++].toInt() and 0xFF
                    if (count == 0 && b == 0x80) throw Asn1Exception(Asn1Failure.INVALID_OID, elementOffset)
                    if (++count > MAX_OID_ARC_BYTES) throw Asn1Exception(Asn1Failure.INVALID_OID, elementOffset)
                    value = (value shl 7) or (b and 0x7F).toLong()
                    if ((b and 0x80) == 0) break
                }
                if (arcs.isEmpty()) {
                    val firstArc =
                        if (value < 40) {
                            0L
                        } else if (value < 80) {
                            1L
                        } else {
                            2L
                        }
                    arcs.add(firstArc)
                    arcs.add(value - firstArc * 40)
                } else {
                    arcs.add(value)
                }
            }
            return arcs.joinToString(".")
        }
    }
}

/** A BIT STRING: its bytes and the number of unused bits at the end of the last byte. */
internal class DerBitString(
    val bytes: ByteArray,
    val unusedBits: Int,
)
