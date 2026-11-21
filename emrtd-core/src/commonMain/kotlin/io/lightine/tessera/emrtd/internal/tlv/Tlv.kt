package io.lightine.tessera.emrtd.internal.tlv

/**
 * Why BER-TLV bytes could not be read. Every failure of [TlvParser] is one of these, with the
 * offset where it was found: hostile or damaged chip data fails here, never with an index error.
 */
internal enum class TlvFailure {
    /** The bytes end inside a tag. */
    TRUNCATED_TAG,

    /** The first tag byte is `00` or `FF`, which ISO/IEC 7816-4 does not allow. */
    INVALID_TAG,

    /** The tag has more than [TlvParser.MAX_TAG_BYTES] bytes. */
    TAG_TOO_LONG,

    /** The tag's first subsequent byte is `80`: leading zero bits (X.690, 8.1.2.4.2 c). */
    NON_MINIMAL_TAG,

    /** The bytes end inside a length. */
    TRUNCATED_LENGTH,

    /** The length byte is `80` (indefinite form), which chip data does not use. */
    INDEFINITE_LENGTH,

    /** The length has more than 4 length bytes, or is larger than an `Int`. */
    LENGTH_TOO_LONG,

    /** The length is not in its shortest form; reported only when the parser is strict. */
    NON_MINIMAL_LENGTH,

    /** The length says more value bytes than there are. */
    TRUNCATED_VALUE,

    /** Constructed objects are nested deeper than the parser's limit. */
    TOO_DEEP,

    /** Children were asked of a primitive object. */
    NOT_CONSTRUCTED,

    /** Bytes follow the one data object that should fill the input. */
    TRAILING_BYTES,

    /** A required tag is absent. */
    MISSING_TAG,
}

/** BER-TLV bytes could not be read. Carries no data bytes: only the reason and an offset. */
internal class TlvException(
    val failure: TlvFailure,
    val offset: Int,
) : IllegalStateException("BER-TLV: $failure at offset $offset")

/**
 * One BER-TLV data object: a view into the source bytes, not a copy.
 *
 * [tag] holds the tag bytes as one number, for example `0x5F1F` or `0x7F61`.
 */
internal class Tlv(
    private val source: ByteArray,
    val tag: Int,
    /** Offset of the first tag byte in the source. */
    val offset: Int,
    /** Offset of the first value byte in the source. */
    val valueOffset: Int,
    val length: Int,
    /** Nesting depth: 0 for a top-level object. */
    val depth: Int,
    private val parser: TlvParser,
) {
    /** True when bit 6 of the first tag byte is set: the value holds further data objects. */
    val isConstructed: Boolean
        get() = (firstTagByte(tag) and 0x20) != 0

    /** Offset one past the last value byte. */
    val end: Int get() = valueOffset + length

    /** A copy of the value bytes. */
    fun value(): ByteArray = source.copyOfRange(valueOffset, end)

    /** A copy of the whole encoding: tag, length and value. */
    fun encoded(): ByteArray = source.copyOfRange(offset, end)

    /** The data objects inside a constructed object. */
    fun children(): List<Tlv> {
        if (!isConstructed) throw TlvException(TlvFailure.NOT_CONSTRUCTED, offset)
        return parser.parseRange(source, valueOffset, end, depth + 1)
    }

    /** The first child with [childTag], or `null`. */
    fun child(childTag: Int): Tlv? = children().firstOrNull { it.tag == childTag }

    /** The first child with [childTag]; throws [TlvException] with [TlvFailure.MISSING_TAG] if absent. */
    fun requireChild(childTag: Int): Tlv = child(childTag) ?: throw TlvException(TlvFailure.MISSING_TAG, valueOffset)

    override fun toString(): String = "Tlv(tag=${tag.toString(16)}, length=$length)"

    private companion object {
        fun firstTagByte(tag: Int): Int =
            when {
                tag > 0xFFFF -> tag shr 16
                tag > 0xFF -> tag shr 8
                else -> tag
            }
    }
}
