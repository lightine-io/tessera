package io.lightine.tessera.emrtd.internal.cms

import io.lightine.tessera.emrtd.internal.asn1.Asn1Exception
import io.lightine.tessera.emrtd.internal.asn1.Asn1Failure
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter

/** The result of [BerNormalizer.normalize]: [der] is the input with every length in its shortest definite form. */
internal class BerNormalized(
    val der: ByteArray,
    /** `true` when the input was not already in that form (it had indefinite or longer-than-needed lengths). */
    val changed: Boolean,
)

/**
 * Rewrites BER lengths into the definite, shortest form DER uses, so the strict `DerReader` can read
 * the result. Nothing else changes: tags and the bytes of primitive values are copied as they are.
 *
 * Doc 9303 Part 10, sections 4.6.2.2 and D.1, require the security object to be DER, because the
 * signatures run over its bytes. Some issuers wrote EF.SOD as BER with indefinite lengths anyway. The
 * reader's job is to read what the chip holds, so the SOD parser goes through this step first. It is
 * safe for the later signature check: the signature covers the content bytes of the encapsulated
 * content (kept as they are) and the signed attributes, which CMS (RFC 5652, section 5.4) defines as
 * their DER encoding, which is what this produces.
 *
 * Hostile input fails with [Asn1Exception], never an index error: every length is checked against
 * the bytes that exist, and nesting is limited to [MAX_DEPTH].
 */
internal object BerNormalizer {
    const val MAX_DEPTH = 30

    fun normalize(source: ByteArray): BerNormalized {
        val out = ByteSink()
        range(source, 0, source.size, 0, out)
        val der = out.toByteArray()
        return BerNormalized(der, !der.contentEquals(source))
    }

    private fun range(
        src: ByteArray,
        from: Int,
        to: Int,
        depth: Int,
        out: ByteSink,
    ) {
        var p = from
        while (p < to) p = element(src, p, to, depth, out)
    }

    // Copies one element to [out] with a definite length; returns the offset after it.
    private fun element(
        src: ByteArray,
        offset: Int,
        limit: Int,
        depth: Int,
        out: ByteSink,
    ): Int {
        if (depth > MAX_DEPTH) throw Asn1Exception(Asn1Failure.TOO_DEEP, offset)
        var p = offset
        val first = src[p++].toInt() and 0xFF
        val constructed = (first and 0x20) != 0
        if ((first and 0x1F) == 0x1F) {
            var count = 0
            while (true) {
                if (p >= limit) throw Asn1Exception(Asn1Failure.TRUNCATED, offset)
                val b = src[p++].toInt() and 0xFF
                if (++count > 4) throw Asn1Exception(Asn1Failure.TAG_TOO_LONG, offset)
                if ((b and 0x80) == 0) break
            }
        }
        val tagEnd = p
        if (p >= limit) throw Asn1Exception(Asn1Failure.TRUNCATED, offset)
        val l0 = src[p++].toInt() and 0xFF
        if (l0 == 0x80) {
            if (!constructed) throw Asn1Exception(Asn1Failure.INDEFINITE_LENGTH, tagEnd)
            val body = ByteSink()
            while (true) {
                if (p + 2 <= limit && src[p].toInt() == 0 && src[p + 1].toInt() == 0) {
                    p += 2
                    break
                }
                if (p >= limit) throw Asn1Exception(Asn1Failure.TRUNCATED, offset)
                p = element(src, p, limit, depth + 1, body)
            }
            emit(src, offset, tagEnd, body, out)
            return p
        }
        val length: Int
        if (l0 < 0x80) {
            length = l0
        } else {
            val n = l0 and 0x7F
            if (n > 4) throw Asn1Exception(Asn1Failure.LENGTH_TOO_LONG, tagEnd)
            if (p + n > limit) throw Asn1Exception(Asn1Failure.TRUNCATED, tagEnd)
            var l = 0L
            repeat(n) { l = (l shl 8) or (src[p++].toLong() and 0xFF) }
            if (l > Int.MAX_VALUE) throw Asn1Exception(Asn1Failure.LENGTH_TOO_LONG, tagEnd)
            length = l.toInt()
        }
        if (length > limit - p) throw Asn1Exception(Asn1Failure.TRUNCATED, tagEnd)
        if (constructed) {
            val body = ByteSink()
            range(src, p, p + length, depth + 1, body)
            emit(src, offset, tagEnd, body, out)
        } else {
            out.write(src, offset, tagEnd)
            out.write(TlvWriter.length(length))
            out.write(src, p, p + length)
        }
        return p + length
    }

    private fun emit(
        src: ByteArray,
        offset: Int,
        tagEnd: Int,
        body: ByteSink,
        out: ByteSink,
    ) {
        out.write(src, offset, tagEnd)
        out.write(TlvWriter.length(body.size))
        out.write(body.toByteArray())
    }
}

/** A growing byte buffer. */
internal class ByteSink {
    private var buffer = ByteArray(256)

    var size: Int = 0
        private set

    fun write(
        src: ByteArray,
        from: Int = 0,
        to: Int = src.size,
    ) {
        val n = to - from
        if (size + n > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + n))
        src.copyInto(buffer, size, from, to)
        size += n
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}
