package io.lightine.tessera.emrtd.internal.lds

import io.lightine.tessera.emrtd.internal.tlv.Tlv
import io.lightine.tessera.emrtd.internal.tlv.TlvException
import io.lightine.tessera.emrtd.internal.tlv.TlvFailure
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import io.lightine.tessera.emrtd.lds.BiometricDataBlock
import io.lightine.tessera.emrtd.lds.BiometricGroup
import io.lightine.tessera.emrtd.lds.BiometricHeader
import io.lightine.tessera.emrtd.lds.BiometricStandard
import io.lightine.tessera.emrtd.lds.BiometricTemplate
import io.lightine.tessera.emrtd.lds.DisplayedImageGroup
import io.lightine.tessera.emrtd.lds.Iso39794Part
import io.lightine.tessera.emrtd.lds.LdsParseResult
import io.lightine.tessera.types.errors.ChipMalformedFile

/**
 * Reads DG2, DG5 and DG7 down to the containers Doc 9303 Part 10 defines, and no further.
 * Biometric data blocks and images are copied out as opaque bytes. Every fault in the bytes ends
 * as [ChipMalformedFile]; nothing else escapes.
 */
internal object ImageGroupReader {
    private const val TAG_DG2 = 0x75
    private const val TAG_DG5 = 0x65
    private const val TAG_DG7 = 0x67
    private const val TAG_BIT_GROUP = 0x7F61
    private const val TAG_BIT = 0x7F60
    private const val TAG_COUNT = 0x02
    private const val TAG_HEADER = 0xA1
    private const val TAG_BDB_19794 = 0x5F2E
    private const val TAG_BDB_39794 = 0x7F2E
    private const val TAG_PORTRAIT = 0x5F40
    private const val TAG_SIGNATURE = 0x5F43

    // Deepest real nesting is 75 > 7F61 > 7F60 > A1 / 7F2E > A1 > 6x: about 5 levels. 8 is margin.
    private const val MAX_DEPTH = 8
    private const val MAX_COUNT_BYTES = 4
    private const val FILE_DG2 = 0x0102
    private const val FILE_DG5 = 0x0105
    private const val FILE_DG7 = 0x0107

    private class Stop(
        val problem: ChipMalformedFile.Reason,
        val offset: Int?,
    ) : Exception()

    fun readDg2(bytes: ByteArray): LdsParseResult<BiometricGroup> =
        run(FILE_DG2) {
            val root = firstObject(bytes, TAG_DG2)
            var declared: Int? = null
            var seenGroup = false
            val templates = ArrayList<BiometricTemplate>()
            for (group in root.children()) {
                if (group.tag != TAG_BIT_GROUP) continue
                if (!group.isConstructed) throw Stop(ChipMalformedFile.Reason.INVALID_ELEMENT, group.offset)
                val kids = group.children()
                if (!seenGroup) declared = kids.firstOrNull { it.tag == TAG_COUNT }?.let(::count)
                seenGroup = true
                for (bit in kids) {
                    if (bit.tag != TAG_BIT) continue
                    templates.add(template(bit))
                }
            }
            if (!seenGroup) throw Stop(ChipMalformedFile.Reason.MISSING_ELEMENT, root.valueOffset)
            BiometricGroup(declared, templates)
        }

    fun readDisplayed(
        bytes: ByteArray,
        dg7: Boolean,
    ): LdsParseResult<DisplayedImageGroup> =
        run(if (dg7) FILE_DG7 else FILE_DG5) {
            val fileTag = if (dg7) TAG_DG7 else TAG_DG5
            val imageTag = if (dg7) TAG_SIGNATURE else TAG_PORTRAIT
            val templates = guard { PARSER.parseAll(bytes) }
            if (templates.isEmpty()) throw Stop(ChipMalformedFile.Reason.UNEXPECTED_FILE_TAG, 0)
            var declared: Int? = null
            val images = ArrayList<ByteArray>()
            for ((i, t) in templates.withIndex()) {
                if (t.tag != fileTag) throw Stop(ChipMalformedFile.Reason.UNEXPECTED_FILE_TAG, t.offset)
                if (!t.isConstructed) throw Stop(ChipMalformedFile.Reason.INVALID_ELEMENT, t.offset)
                for (child in guard { t.children() }) {
                    when (child.tag) {
                        TAG_COUNT -> if (i == 0 && declared == null) declared = count(child)
                        imageTag -> images.add(child.value())
                    }
                }
            }
            if (images.isEmpty()) throw Stop(ChipMalformedFile.Reason.MISSING_ELEMENT, templates[0].valueOffset)
            DisplayedImageGroup(declared, images)
        }

    private fun template(bit: Tlv): BiometricTemplate {
        if (!bit.isConstructed) throw Stop(ChipMalformedFile.Reason.INVALID_ELEMENT, bit.offset)
        val kids = guard { bit.children() }
        val headerTlv = kids.firstOrNull { it.tag == TAG_HEADER }
        val header = if (headerTlv == null) emptyHeader() else header(headerTlv)
        val bdb =
            kids.firstOrNull { it.tag == TAG_BDB_19794 || it.tag == TAG_BDB_39794 }
                ?: throw Stop(ChipMalformedFile.Reason.MISSING_ELEMENT, bit.valueOffset)
        return BiometricTemplate(header, block(bdb))
    }

    private fun emptyHeader() = BiometricHeader(null, null, null, null, null, null, null, null)

    private fun header(a1: Tlv): BiometricHeader {
        if (!a1.isConstructed) throw Stop(ChipMalformedFile.Reason.INVALID_ELEMENT, a1.offset)
        val fields = HashMap<Int, ByteArray>()
        for (f in guard { a1.children() }) if (f.tag !in fields) fields[f.tag] = f.value()
        return BiometricHeader(
            fields[0x80],
            fields[0x81],
            fields[0x82],
            fields[0x83],
            fields[0x85],
            fields[0x86],
            fields[0x87],
            fields[0x88],
        )
    }

    private fun block(bdb: Tlv): BiometricDataBlock {
        if (bdb.tag == TAG_BDB_19794) {
            return BiometricDataBlock(BiometricStandard.ISO_19794, bdb.value(), null, null)
        }
        // 7F2E { A1 { 64 | 65 | 66 } } (Part 10, Table 2). Walk to the container; never into it.
        if (!bdb.isConstructed) throw Stop(ChipMalformedFile.Reason.INVALID_ELEMENT, bdb.offset)
        val a1 = guard { bdb.children() }.firstOrNull { it.tag == TAG_HEADER }
        var part: Iso39794Part? = null
        var data: ByteArray? = null
        if (a1 != null && a1.isConstructed) {
            val inner = guard { a1.children() }.firstOrNull { it.tag in 0x64..0x66 }
            if (inner != null) {
                part = Iso39794Part.entries[inner.tag - 0x64]
                data = inner.value()
            }
        }
        return BiometricDataBlock(BiometricStandard.ISO_39794, bdb.value(), part, data)
    }

    private fun count(t: Tlv): Int? {
        if (t.length !in 1..MAX_COUNT_BYTES) return null
        var v = 0L
        for (b in t.value()) v = (v shl 8) or (b.toLong() and 0xFF)
        return if (v > Int.MAX_VALUE) null else v.toInt()
    }

    private val PARSER = TlvParser(maxDepth = MAX_DEPTH)

    private fun firstObject(
        bytes: ByteArray,
        tag: Int,
    ): Tlv {
        val t = guard { PARSER.parseFirst(bytes) }
        if (t.tag != tag) throw Stop(ChipMalformedFile.Reason.UNEXPECTED_FILE_TAG, 0)
        if (!t.isConstructed) throw Stop(ChipMalformedFile.Reason.INVALID_ELEMENT, 0)
        return t
    }

    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: TlvException) {
            throw Stop(map(e.failure), e.offset)
        }

    private fun map(f: TlvFailure): ChipMalformedFile.Reason =
        when (f) {
            TlvFailure.TRUNCATED_VALUE, TlvFailure.TRUNCATED_LENGTH -> {
                ChipMalformedFile.Reason.NOT_VALID_TLV
            }

            TlvFailure.INDEFINITE_LENGTH, TlvFailure.LENGTH_TOO_LONG, TlvFailure.NON_MINIMAL_LENGTH -> {
                ChipMalformedFile.Reason.NOT_VALID_TLV
            }

            TlvFailure.TRUNCATED_TAG, TlvFailure.INVALID_TAG, TlvFailure.TAG_TOO_LONG, TlvFailure.NON_MINIMAL_TAG -> {
                ChipMalformedFile.Reason.NOT_VALID_TLV
            }

            TlvFailure.TOO_DEEP -> {
                ChipMalformedFile.Reason.NESTING_TOO_DEEP
            }

            TlvFailure.MISSING_TAG -> {
                ChipMalformedFile.Reason.MISSING_ELEMENT
            }

            TlvFailure.NOT_CONSTRUCTED, TlvFailure.TRAILING_BYTES -> {
                ChipMalformedFile.Reason.INVALID_ELEMENT
            }
        }

    private fun <T> run(
        fileId: Int,
        block: () -> T,
    ): LdsParseResult<T> =
        try {
            LdsParseResult.Success(block())
        } catch (s: Stop) {
            LdsParseResult.Failure(ChipMalformedFile(fileId, s.problem, s.offset))
        } catch (e: TlvException) {
            LdsParseResult.Failure(ChipMalformedFile(fileId, map(e.failure), e.offset))
        }
}
