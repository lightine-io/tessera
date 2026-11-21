package io.lightine.tessera.emrtd.internal.apdu

import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandApduTest {
    private fun hex(s: String): ByteArray =
        s
            .replace(" ", "")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    @Test
    fun case_1_header_only() {
        assertContentEquals(hex("00 A4 04 0C"), CommandApdu(0, 0xA4, 4, 0x0C).encode(false))
    }

    @Test
    fun case_2_short_le_and_256_as_00() {
        assertContentEquals(hex("00 B0 00 00 20"), CommandApdu(0, 0xB0, 0, 0, ne = 32).encode(false))
        assertContentEquals(hex("00 B0 00 00 00"), CommandApdu(0, 0xB0, 0, 0, ne = 256).encode(false))
    }

    @Test
    fun case_3_short_lc() {
        assertContentEquals(
            hex("00 A4 04 0C 07 A0 00 00 02 47 10 01"),
            ChipCommands.selectApplication(LdsIds.LDS1_AID).encode(false),
        )
    }

    @Test
    fun case_4_short_lc_and_le() {
        assertContentEquals(hex("00 88 00 00 02 01 02 00"), CommandApdu(0, 0x88, 0, 0, byteArrayOf(1, 2), 256).encode(false))
    }

    @Test
    fun extended_le_without_data_has_three_bytes() {
        assertContentEquals(hex("00 B0 00 00 00 01 01"), CommandApdu(0, 0xB0, 0, 0, ne = 257).encode(true))
        assertContentEquals(hex("00 B0 00 00 00 00 00"), CommandApdu(0, 0xB0, 0, 0, ne = 65_536).encode(true))
    }

    @Test
    fun extended_lc_and_le() {
        val data = ByteArray(256) { it.toByte() }
        val encoded = CommandApdu(0, 0x86, 0, 0, data, 65_536).encode(true)
        assertContentEquals(hex("00 86 00 00 00 01 00"), encoded.copyOfRange(0, 7))
        assertContentEquals(data, encoded.copyOfRange(7, 7 + 256))
        assertContentEquals(hex("00 00"), encoded.copyOfRange(7 + 256, encoded.size))
        assertEquals(encoded.size, CommandApdu(0, 0x86, 0, 0, data, 65_536).encodedLength())
    }

    @Test
    fun boundaries_between_short_and_extended() {
        assertTrue(CommandApdu(0, 0, 0, 0, ByteArray(255), 256).fitsShort)
        assertFalse(CommandApdu(0, 0, 0, 0, ByteArray(256)).fitsShort)
        assertFalse(CommandApdu(0, 0, 0, 0, ne = 257).fitsShort)
    }

    @Test
    fun extended_is_refused_when_not_allowed() {
        val e = assertFailsWith<ExtendedLengthRequired> { CommandApdu(0, 0xB0, 0, 0, ne = 1000).encode(false) }
        assertEquals(1000, e.ne)
    }

    @Test
    fun out_of_range_values_are_programming_errors() {
        assertFailsWith<IllegalArgumentException> { CommandApdu(0x100, 0, 0, 0) }
        assertFailsWith<IllegalArgumentException> { CommandApdu(0, 0, 0, 0, ByteArray(65_536)) }
        assertFailsWith<IllegalArgumentException> { CommandApdu(0, 0, 0, 0, ne = 0) }
        assertFailsWith<IllegalArgumentException> { CommandApdu(0, 0, 0, 0, ne = 65_537) }
    }

    @Test
    fun data_is_copied_in() {
        val data = byteArrayOf(1, 2, 3)
        val c = CommandApdu(0, 0, 0, 0, data)
        data.fill(0)
        assertContentEquals(byteArrayOf(1, 2, 3), c.data)
    }

    @Test
    fun decode_inverts_encode_for_every_case_short_and_extended() {
        val lengths = Arb.int(0..600)
        repeat(400) {
            val nc = lengths.next()
            val data = Arb.byteArray(Arb.int(nc..nc), Arb.byte()).next()
            val ne = if (it % 3 == 0) null else Arb.int(1..65_536).next()
            val c = CommandApdu(0x00, 0xB0, it and 0x7F, 0x01, data, ne)
            val decoded = assertNotNull(CommandApdu.decode(c.encode(true)))
            assertContentEquals(c.data, decoded.data)
            assertEquals(c.ne, decoded.ne)
            assertEquals(c.p1, decoded.p1)
        }
    }

    @Test
    fun decode_refuses_inconsistent_lengths() {
        assertNull(CommandApdu.decode(hex("00 A4")))
        assertNull(CommandApdu.decode(hex("00 A4 04 0C 05 01 02")))
        assertNull(CommandApdu.decode(hex("00 A4 04 0C 00 00")))
        assertNull(CommandApdu.decode(hex("00 A4 04 0C 00 00 00 01")))
    }

    @Test
    fun file_commands_have_the_part_10_shapes() {
        assertContentEquals(hex("00 A4 02 0C 02 01 1E"), ChipCommands.selectFile(LdsIds.EF_COM).encode(false))
        assertContentEquals(hex("00 B0 01 02 10"), ChipCommands.readBinary(0x0102, 16).encode(false))
        assertContentEquals(hex("00 B0 81 00 04"), ChipCommands.readBinaryBySfi(LdsIds.sfi(LdsIds.EF_DG1), 0, 4).encode(false))
        assertEquals(0x1E, LdsIds.sfi(LdsIds.EF_COM))
    }
}
