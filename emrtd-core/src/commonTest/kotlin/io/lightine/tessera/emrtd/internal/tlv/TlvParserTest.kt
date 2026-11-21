package io.lightine.tessera.emrtd.internal.tlv

import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class TlvParserTest {
    private val parser = TlvParser()

    private fun hex(s: String): ByteArray =
        s
            .replace(" ", "")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    private fun failure(
        bytes: ByteArray,
        p: TlvParser = parser,
    ): TlvFailure = assertFailsWith<TlvException> { p.parseAll(bytes) }.failure

    @Test
    fun reads_one_and_two_byte_tags_and_nested_objects() {
        // 61 { 5F1F "AB" }  then  5C [61 75]
        val bytes = hex("61 05 5F 1F 02 41 42 5C 02 61 75")
        val all = parser.parseAll(bytes)
        assertEquals(listOf(0x61, 0x5C), all.map { it.tag })
        assertTrue(all[0].isConstructed)
        assertFalse(all[1].isConstructed)
        val mrz = all[0].requireChild(0x5F1F)
        assertContentEquals("AB".encodeToByteArray(), mrz.value())
        assertEquals(1, mrz.depth)
        assertContentEquals(hex("5F 1F 02 41 42"), mrz.encoded())
        assertNull(all[0].child(0x5F36))
        assertEquals(TlvFailure.MISSING_TAG, assertFailsWith<TlvException> { all[0].requireChild(0x5F36) }.failure)
        assertEquals(TlvFailure.NOT_CONSTRUCTED, assertFailsWith<TlvException> { all[1].children() }.failure)
    }

    @Test
    fun long_form_lengths() {
        val value = ByteArray(300) { it.toByte() }
        val t = parser.parseSingle(TlvWriter.encode(0x53, value))
        assertEquals(300, t.length)
        assertContentEquals(value, t.value())
        assertEquals(1 + 3 + 300, parser.declaredTotalLength(TlvWriter.encode(0x53, value).copyOfRange(0, 4)))
    }

    @Test
    fun non_minimal_length_is_read_by_default_and_refused_when_strict() {
        val bytes = hex("53 81 02 01 02")
        assertEquals(2, parser.parseSingle(bytes).length)
        assertEquals(TlvFailure.NON_MINIMAL_LENGTH, failure(bytes, TlvParser(strictLengths = true)))
        assertEquals(TlvFailure.NON_MINIMAL_LENGTH, failure(hex("53 82 00 81") + ByteArray(0x81), TlvParser(strictLengths = true)))
    }

    @Test
    fun tag_rules() {
        assertEquals(TlvFailure.INVALID_TAG, failure(hex("00 01 00")))
        assertEquals(TlvFailure.INVALID_TAG, failure(hex("FF 01 00")))
        assertEquals(TlvFailure.TRUNCATED_TAG, failure(hex("5F")))
        assertEquals(TlvFailure.NON_MINIMAL_TAG, failure(hex("5F 80 01 00")))
        // ICAO's two-byte tags with a small number, such as 5F01, are allowed.
        assertEquals(0x5F01, parser.parseSingle(hex("5F 01 00")).tag)
        assertEquals(TlvFailure.TAG_TOO_LONG, failure(hex("5F 81 81 81 01 00")))
        assertEquals(0x5F8101, parser.parseSingle(hex("5F 81 01 00")).tag)
    }

    @Test
    fun length_rules() {
        assertEquals(TlvFailure.TRUNCATED_LENGTH, failure(hex("53")))
        assertEquals(TlvFailure.TRUNCATED_LENGTH, failure(hex("53 82 01")))
        assertEquals(TlvFailure.INDEFINITE_LENGTH, failure(hex("73 80 00 00")))
        assertEquals(TlvFailure.LENGTH_TOO_LONG, failure(hex("53 85 01 00 00 00 00")))
        assertEquals(TlvFailure.LENGTH_TOO_LONG, failure(hex("53 84 80 00 00 00")))
    }

    @Test
    fun huge_declared_lengths_fail_without_allocating() {
        // Each declares up to 2 GB of value in a few bytes; parsing must fail at once.
        listOf("53 84 7F FF FF FF", "53 83 FF FF FF 00", "53 82 FF FF 01").forEach {
            assertEquals(TlvFailure.TRUNCATED_VALUE, failure(hex(it)))
        }
        assertFailsWith<TlvException> { parser.declaredTotalLength(hex("53 84 FF FF FF FF")) }
        assertEquals(Int.MAX_VALUE.toLong(), parser.declaredTotalLength(hex("53 84 7F FF FF F9")).toLong())
    }

    @Test
    fun a_child_longer_than_its_parent_is_truncated() {
        assertEquals(
            TlvFailure.TRUNCATED_VALUE,
            assertFailsWith<TlvException> { parser.parseSingle(hex("61 03 5F 1F 05")).children() }.failure,
        )
    }

    @Test
    fun trailing_bytes_after_a_single_object() {
        assertEquals(TlvFailure.TRAILING_BYTES, assertFailsWith<TlvException> { parser.parseSingle(hex("53 01 00 00")) }.failure)
        assertEquals(0x53, parser.parseFirst(hex("53 01 00 00")).tag)
    }

    @Test
    fun deep_nesting_fails_at_the_limit_not_with_a_stack_overflow() {
        // 3,000 nested constructed objects: 73 L (73 L (...)).
        var inner = ByteArray(0)
        repeat(3_000) { inner = TlvWriter.encode(0x73, inner) }
        val e = assertFailsWith<TlvException> { walk(parser.parseSingle(inner)) }
        assertEquals(TlvFailure.TOO_DEEP, e.failure)

        var ok = ByteArray(0)
        repeat(TlvParser.DEFAULT_MAX_DEPTH + 1) { ok = TlvWriter.encode(0x73, ok) }
        walk(parser.parseSingle(ok))
    }

    @Test
    fun every_truncation_of_a_valid_encoding_fails_cleanly() {
        val full =
            TlvWriter.constructed(
                0x61,
                TlvWriter.encode(0x5F1F, ByteArray(200) { 0x41 }),
                TlvWriter.constructed(0x7F61, TlvWriter.encode(0x02, byteArrayOf(1))),
            )
        for (cut in 1 until full.size) {
            val e = assertFailsWith<TlvException>("cut at $cut") { walkAll(parser.parseAll(full.copyOfRange(0, cut))) }
            assertTrue(e.offset in 0..cut, "offset ${e.offset} outside input of $cut bytes")
        }
    }

    @Test
    fun random_bytes_only_ever_fail_with_a_tlv_exception() {
        val random = Random(20261121)
        repeat(20_000) {
            val bytes = random.nextBytes(random.nextInt(0, 64))
            try {
                walkAll(parser.parseAll(bytes))
            } catch (e: TlvException) {
                // expected for most inputs
            } catch (e: Throwable) {
                fail("unexpected ${e::class.simpleName} for input of ${bytes.size} bytes")
            }
        }
    }

    @Test
    fun mutated_valid_encodings_only_ever_fail_with_a_tlv_exception() {
        val random = Random(9303)
        val base =
            TlvWriter.constructed(
                0x77,
                TlvWriter.encode(0x5F01, "0108".encodeToByteArray()),
                TlvWriter.constructed(0x61, TlvWriter.encode(0x5F1F, ByteArray(150) { 0x3C })),
            )
        repeat(20_000) {
            val bytes = base.copyOf()
            repeat(random.nextInt(1, 4)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
            try {
                walkAll(TlvParser(strictLengths = random.nextBoolean()).parseAll(bytes))
            } catch (e: TlvException) {
                // expected
            } catch (e: Throwable) {
                fail("unexpected ${e::class.simpleName}")
            }
        }
    }

    @Test
    fun parse_inverts_encode_for_any_tag_and_value() {
        val tags = Arb.element(listOf(0x01, 0x04, 0x1E, 0x53, 0x5C, 0x5F1F, 0x5F2E, 0x7F2E, 0x9F8101, 0x86))
        val lengths = Arb.int(0..70_000)
        repeat(300) {
            val tag = tags.next()
            val n = lengths.next()
            val value = Arb.byteArray(Arb.int(n..n), Arb.byte()).next()
            val encoded = TlvWriter.encode(tag, value)
            val t = TlvParser(strictLengths = true).parseSingle(encoded)
            assertEquals(tag, t.tag)
            assertContentEquals(value, t.value())
            assertContentEquals(encoded, t.encoded())
        }
    }

    @Test
    fun writer_uses_the_shortest_forms() {
        assertContentEquals(hex("7F"), TlvWriter.length(127))
        assertContentEquals(hex("81 80"), TlvWriter.length(128))
        assertContentEquals(hex("81 FF"), TlvWriter.length(255))
        assertContentEquals(hex("82 01 00"), TlvWriter.length(256))
        assertContentEquals(hex("83 01 00 00"), TlvWriter.length(65_536))
        assertContentEquals(hex("84 01 00 00 00"), TlvWriter.length(0x1000000))
        assertContentEquals(hex("5F 1F"), TlvWriter.tag(0x5F1F))
        assertContentEquals(hex("9F 81 01"), TlvWriter.tag(0x9F8101))
        assertFailsWith<IllegalArgumentException> { TlvWriter.tag(0) }
        assertFailsWith<IllegalArgumentException> { TlvWriter.length(-1) }
    }

    @Test
    fun unsigned_integers_have_no_leading_zero_bytes() {
        assertContentEquals(hex("01 00"), TlvWriter.minimalUnsigned(hex("00 00 01 00")))
        assertContentEquals(hex("80"), TlvWriter.minimalUnsigned(hex("00 80")))
        assertContentEquals(hex("00"), TlvWriter.minimalUnsigned(hex("00 00")))
        assertContentEquals(hex("00"), TlvWriter.minimalUnsigned(ByteArray(0)))
    }

    private fun walk(t: Tlv) {
        if (t.isConstructed) t.children().forEach { walk(it) }
    }

    private fun walkAll(list: List<Tlv>) = list.forEach { walk(it) }
}
