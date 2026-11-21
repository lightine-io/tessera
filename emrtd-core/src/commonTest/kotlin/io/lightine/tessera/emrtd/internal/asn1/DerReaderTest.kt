package io.lightine.tessera.emrtd.internal.asn1

import io.lightine.tessera.emrtd.internal.tlv.TlvWriter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** A minimal DER writer for tests: the tag byte, the shortest length, the value. */
private fun der(
    tag: Int,
    vararg parts: ByteArray,
): ByteArray {
    var v = ByteArray(0)
    parts.forEach { v += it }
    return byteArrayOf(tag.toByte()) + TlvWriter.length(v.size) + v
}

class DerReaderTest {
    private fun hex(s: String): ByteArray =
        s
            .replace(" ", "")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    private fun failure(block: () -> Unit): Asn1Failure = assertFailsWith<Asn1Exception> { block() }.failure

    @Test
    fun reads_a_security_info_shaped_sequence() {
        // SEQUENCE { OID 0.4.0.127.0.7.2.2.4.2.2, INTEGER 2, INTEGER 13 }: the shape of a PACEInfo.
        val bytes = der(0x30, hex("06 0A 04 00 7F 00 07 02 02 04 02 02"), hex("02 01 02"), hex("02 01 0D"))
        val r = DerReader(bytes)
        val seq = r.readSequence()
        assertEquals("0.4.0.127.0.7.2.2.4.2.2", seq.readOid())
        assertEquals(2, seq.readInt())
        assertEquals(13, seq.readInt())
        assertFalse(seq.hasMore)
        seq.finish()
        r.finish()
    }

    @Test
    fun oid_first_arcs_and_large_arcs() {
        assertEquals("1.2.840.113549.1.1.1", DerReader(hex("06 09 2A 86 48 86 F7 0D 01 01 01")).readOid())
        assertEquals("2.5.29.15", DerReader(hex("06 03 55 1D 0F")).readOid())
        assertEquals("2.999", DerReader(hex("06 02 88 37")).readOid())
    }

    @Test
    fun oid_rules() {
        assertEquals(Asn1Failure.INVALID_OID, failure { DerReader(hex("06 00")).readOid() })
        assertEquals(Asn1Failure.INVALID_OID, failure { DerReader(hex("06 02 80 01")).readOid() })
        assertEquals(Asn1Failure.INVALID_OID, failure { DerReader(hex("06 02 2A 86")).readOid() })
        assertEquals(Asn1Failure.INVALID_OID, failure { DerReader(der(0x06, ByteArray(9) { 0xFF.toByte() } + byteArrayOf(1))).readOid() })
        assertEquals(Asn1Failure.INVALID_OID, failure { DerReader(der(0x06, ByteArray(129) { 1 })).readOid() })
    }

    @Test
    fun integer_rules() {
        assertContentEquals(hex("00 80"), DerReader(hex("02 02 00 80")).readInteger())
        assertContentEquals(hex("80"), DerReader(hex("02 02 00 80")).readUnsignedInteger())
        assertEquals(-1, DerReader(hex("02 01 FF")).readInt())
        assertEquals(0x7FFFFFFF, DerReader(hex("02 04 7F FF FF FF")).readInt())
        assertEquals(Asn1Failure.INVALID_INTEGER, failure { DerReader(hex("02 00")).readInteger() })
        assertEquals(Asn1Failure.INVALID_INTEGER, failure { DerReader(hex("02 02 00 01")).readInteger() })
        assertEquals(Asn1Failure.INVALID_INTEGER, failure { DerReader(hex("02 02 FF 80")).readInteger() })
        assertEquals(Asn1Failure.INVALID_INTEGER, failure { DerReader(hex("02 01 80")).readUnsignedInteger() })
        assertEquals(Asn1Failure.INTEGER_TOO_LARGE, failure { DerReader(hex("02 05 01 00 00 00 00")).readInt() })
    }

    @Test
    fun boolean_null_octet_and_bit_strings() {
        assertTrue(DerReader(hex("01 01 FF")).readBoolean())
        assertFalse(DerReader(hex("01 01 00")).readBoolean())
        assertEquals(Asn1Failure.INVALID_BOOLEAN, failure { DerReader(hex("01 01 01")).readBoolean() })
        assertEquals(Asn1Failure.INVALID_BOOLEAN, failure { DerReader(hex("01 02 FF FF")).readBoolean() })
        DerReader(hex("05 00")).readNull()
        assertEquals(Asn1Failure.INVALID_NULL, failure { DerReader(hex("05 01 00")).readNull() })
        assertContentEquals(hex("AA BB"), DerReader(hex("04 02 AA BB")).readOctetString())
        val bits = DerReader(hex("03 03 04 AB C0")).readBitString()
        assertEquals(4, bits.unusedBits)
        assertContentEquals(hex("AB C0"), bits.bytes)
        assertEquals(Asn1Failure.INVALID_BIT_STRING, failure { DerReader(hex("03 00")).readBitString() })
        assertEquals(Asn1Failure.INVALID_BIT_STRING, failure { DerReader(hex("03 02 08 00")).readBitString() })
        assertEquals(Asn1Failure.INVALID_BIT_STRING, failure { DerReader(hex("03 01 01")).readBitString() })
        assertEquals(Asn1Failure.INVALID_BIT_STRING, failure { DerReader(hex("03 02 04 AF")).readBitString() })
    }

    @Test
    fun tagged_optional_and_unexpected_elements() {
        val bytes = der(0x30, der(0xA0, hex("02 01 05")), hex("04 01 00"))
        val seq = DerReader(bytes).readSequence()
        assertNull(seq.readOptional(DerTag.context(1, constructed = true)))
        assertEquals(5, seq.readExplicit(0).readInt())
        assertEquals(DerTag.universal(DerTag.OCTET_STRING), seq.peekTag())
        assertEquals(Asn1Failure.UNEXPECTED_TAG, failure { seq.readInt() })
        assertEquals(1, seq.read().length)
        assertNull(seq.peekTag())
        assertEquals(Asn1Failure.TRAILING_BYTES, failure { DerReader(hex("05 00 05 00")).also { it.readNull() }.finish() })
        // A primitive element has no contents.
        assertEquals(Asn1Failure.UNEXPECTED_TAG, failure { DerReader(hex("04 00")).read().contents() })
    }

    @Test
    fun high_tag_numbers() {
        val e = DerReader(hex("9F 81 01 00")).read()
        assertEquals(DerTag(DerTag.CONTEXT, false, 129), e.tag)
        assertEquals(Asn1Failure.NON_MINIMAL_TAG, failure { DerReader(hex("9F 80 01 00")).read() })
        assertEquals(Asn1Failure.NON_MINIMAL_TAG, failure { DerReader(hex("9F 1E 00")).read() })
        assertEquals(Asn1Failure.TAG_TOO_LONG, failure { DerReader(hex("9F 81 81 81 81 01 00")).read() })
    }

    @Test
    fun der_length_rules() {
        assertEquals(Asn1Failure.INDEFINITE_LENGTH, failure { DerReader(hex("30 80 00 00")).read() })
        assertEquals(Asn1Failure.NON_MINIMAL_LENGTH, failure { DerReader(hex("04 81 05 00 00 00 00 00")).read() })
        assertEquals(Asn1Failure.NON_MINIMAL_LENGTH, failure { DerReader(hex("04 82 00 80") + ByteArray(0x80)).read() })
        assertEquals(Asn1Failure.LENGTH_TOO_LONG, failure { DerReader(hex("04 85 01 00 00 00 00")).read() })
        assertEquals(Asn1Failure.LENGTH_TOO_LONG, failure { DerReader(hex("04 84 80 00 00 00")).read() })
        assertEquals(Asn1Failure.TRUNCATED, failure { DerReader(hex("04")).read() })
        assertEquals(Asn1Failure.TRUNCATED, failure { DerReader(hex("04 82 01")).read() })
        assertEquals(128, DerReader(hex("04 81 80") + ByteArray(128)).read().length)
    }

    @Test
    fun huge_declared_lengths_fail_without_allocating() {
        listOf("30 84 7F FF FF FF", "04 83 FF FF FF 00", "02 82 FF FF 01").forEach {
            assertEquals(Asn1Failure.TRUNCATED, failure { DerReader(hex(it)).read() })
        }
    }

    @Test
    fun deep_nesting_fails_at_the_limit_not_with_a_stack_overflow() {
        var inner = ByteArray(0)
        repeat(3_000) { inner = der(0x30, inner) }
        assertEquals(Asn1Failure.TOO_DEEP, failure { walk(DerReader(inner)) })

        var ok = ByteArray(0)
        repeat(DerReader.DEFAULT_MAX_DEPTH) { ok = der(0x30, ok) }
        walk(DerReader(ok))
    }

    @Test
    fun every_truncation_of_a_valid_encoding_fails_cleanly() {
        val full = der(0x30, hex("06 06 67 81 08 01 01 01"), der(0x31, der(0x04, ByteArray(200))), hex("02 01 01"))
        for (cut in 1 until full.size) {
            assertFailsWith<Asn1Exception>("cut at $cut") { walk(DerReader(full.copyOfRange(0, cut))) }
        }
        walk(DerReader(full))
    }

    @Test
    fun random_and_mutated_bytes_only_ever_fail_with_an_asn1_exception() {
        val random = Random(20261121)
        val base =
            der(0x30, hex("06 06 67 81 08 01 01 01"), der(0xA0, der(0x31, der(0x04, ByteArray(100)), hex("01 01 FF"))), hex("02 02 01 00"))
        repeat(40_000) { i ->
            val bytes =
                if (i % 2 == 0) {
                    random.nextBytes(random.nextInt(0, 64))
                } else {
                    base.copyOf().also { b -> repeat(random.nextInt(1, 4)) { b[random.nextInt(b.size)] = random.nextInt(256).toByte() } }
                }
            try {
                walk(DerReader(bytes))
            } catch (e: Asn1Exception) {
                // expected for most inputs
            } catch (e: Throwable) {
                fail("unexpected ${e::class.simpleName} for input of ${bytes.size} bytes")
            }
        }
    }

    /** Reads every element and decodes each primitive by its universal tag. */
    private fun walk(r: DerReader) {
        while (r.hasMore) {
            val tag = r.peekTag()!!
            if (tag.constructed) {
                walk(r.read().contents())
                continue
            }
            if (tag.tagClass != DerTag.UNIVERSAL) {
                r.read()
                continue
            }
            when (tag.number) {
                DerTag.INTEGER -> r.readInteger()
                DerTag.OID -> r.readOid()
                DerTag.BOOLEAN -> r.readBoolean()
                DerTag.NULL -> r.readNull()
                DerTag.BIT_STRING -> r.readBitString()
                else -> r.read()
            }
        }
        r.finish()
    }
}
