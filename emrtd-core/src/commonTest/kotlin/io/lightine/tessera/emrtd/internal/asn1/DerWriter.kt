package io.lightine.tessera.emrtd.internal.asn1

import io.lightine.tessera.emrtd.internal.tlv.TlvWriter

/**
 * Test-only DER writer: builds the structures the parsers read. It lives in the test sources on
 * purpose, so the SDK ships no ASN.1 writer. Each function returns one complete element.
 */
internal object DerWriter {
    fun tlv(
        tag: Int,
        content: ByteArray,
    ): ByteArray = byteArrayOf(tag.toByte()) + TlvWriter.length(content.size) + content

    fun concat(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { a, b -> a + b }

    fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(*parts))

    fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, concat(*parts))

    fun integer(value: Long): ByteArray {
        var bytes = ByteArray(0)
        var v = value
        do {
            bytes = byteArrayOf((v and 0xFF).toByte()) + bytes
            v = v shr 8
        } while (!((v == 0L && bytes[0] >= 0) || (v == -1L && bytes[0] < 0)))
        return tlv(0x02, bytes)
    }

    /** A positive INTEGER from magnitude bytes (a zero byte is added when the top bit is set). */
    fun unsignedInteger(magnitude: ByteArray): ByteArray = tlv(0x02, if (magnitude[0] < 0) byteArrayOf(0) + magnitude else magnitude)

    fun octetString(bytes: ByteArray): ByteArray = tlv(0x04, bytes)

    fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)

    fun boolean(value: Boolean): ByteArray = tlv(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0))

    fun bitString(
        bytes: ByteArray,
        unusedBits: Int = 0,
    ): ByteArray = tlv(0x03, byteArrayOf(unusedBits.toByte()) + bytes)

    fun printableString(text: String): ByteArray = tlv(0x13, text.encodeToByteArray())

    fun utf8String(text: String): ByteArray = tlv(0x0C, text.encodeToByteArray())

    /** `YYMMDDHHMMSSZ`. */
    fun utcTime(text: String): ByteArray = tlv(0x17, text.encodeToByteArray())

    /** `YYYYMMDDHHMMSSZ`. */
    fun generalizedTime(text: String): ByteArray = tlv(0x18, text.encodeToByteArray())

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val body = ArrayList<Byte>()

        fun base128(v: Long) {
            val parts = ArrayList<Int>()
            var x = v
            do {
                parts.add((x and 0x7F).toInt())
                x = x shr 7
            } while (x > 0)
            for (i in parts.indices.reversed()) body.add((parts[i] or if (i > 0) 0x80 else 0).toByte())
        }
        base128(arcs[0] * 40 + arcs[1])
        for (i in 2 until arcs.size) base128(arcs[i])
        return tlv(0x06, body.toByteArray())
    }

    /** `[number] EXPLICIT`: a constructed context tag around [content]. */
    fun explicit(
        number: Int,
        content: ByteArray,
    ): ByteArray = tlv(0xA0 or number, content)

    /** `[number] IMPLICIT` constructed: the context tag replaces the SET or SEQUENCE tag. */
    fun implicitConstructed(
        number: Int,
        vararg parts: ByteArray,
    ): ByteArray = tlv(0xA0 or number, concat(*parts))

    /** `[number] IMPLICIT` primitive. */
    fun implicitPrimitive(
        number: Int,
        content: ByteArray,
    ): ByteArray = tlv(0x80 or number, content)

    /** A BER constructed element with the indefinite length form and its end marker, for tests of BER input. */
    fun indefinite(
        tag: Int,
        vararg parts: ByteArray,
    ): ByteArray = byteArrayOf(tag.toByte(), 0x80.toByte()) + concat(*parts) + byteArrayOf(0, 0)
}
