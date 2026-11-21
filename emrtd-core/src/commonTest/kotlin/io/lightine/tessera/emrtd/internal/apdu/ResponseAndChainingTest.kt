package io.lightine.tessera.emrtd.internal.apdu

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponseApduTest {
    @Test
    fun splits_data_and_status_word() {
        val r = ResponseApdu.parse(byteArrayOf(1, 2, 3, 0x90.toByte(), 0x00))
        assertContentEquals(byteArrayOf(1, 2, 3), r.data)
        assertEquals(0x9000, r.sw)
        assertEquals(0x90, r.sw1)
        assertEquals(0x00, r.sw2)
    }

    @Test
    fun status_word_only() {
        val r = ResponseApdu.parse(byteArrayOf(0x6A, 0x82.toByte()))
        assertEquals(0, r.data.size)
        assertEquals(StatusWord.FILE_NOT_FOUND, r.sw)
    }

    @Test
    fun fewer_than_two_bytes_is_malformed() {
        assertEquals(1, assertFailsWith<MalformedResponse> { ResponseApdu.parse(byteArrayOf(0x90.toByte())) }.length)
        assertFailsWith<MalformedResponse> { ResponseApdu.parse(ByteArray(0)) }
    }

    @Test
    fun encode_inverts_parse() {
        val bytes = byteArrayOf(9, 8, 0x62, 0x82.toByte())
        assertContentEquals(bytes, ResponseApdu.parse(bytes).encode())
    }

    @Test
    fun success_includes_the_end_of_file_warning_only() {
        assertTrue(StatusWord.isSuccess(0x9000))
        assertTrue(StatusWord.isSuccess(0x6282))
        assertFalse(StatusWord.isSuccess(0x6982))
        assertEquals("6A82", StatusWord.hex(0x6A82))
        assertEquals("0000", StatusWord.hex(0))
    }
}

class CommandChainingTest {
    @Test
    fun split_marks_all_but_the_last_and_keeps_le_on_the_last() {
        val c = CommandApdu(0x00, 0x86, 0, 0, ByteArray(600) { it.toByte() }, 256)
        val chain = CommandChaining.split(c, 255)
        assertEquals(listOf(255, 255, 90), chain.map { it.data.size })
        assertEquals(listOf(0x10, 0x10, 0x00), chain.map { it.cla })
        assertEquals(listOf(null, null, 256), chain.map { it.ne })
        assertContentEquals(c.data, chain.fold(ByteArray(0)) { acc, x -> acc + x.data })
        assertTrue(CommandChaining.isNotLast(chain[0]))
        assertFalse(CommandChaining.isNotLast(chain[2]))
    }

    @Test
    fun a_command_that_fits_is_returned_alone() {
        val c = CommandApdu(0x0C, 0x86, 0, 0, ByteArray(10))
        assertEquals(listOf(c), CommandChaining.split(c, 255))
    }

    @Test
    fun mark_not_last_keeps_other_class_bits() {
        val marked = CommandChaining.markNotLast(CommandApdu(0x0C, 0x86, 0, 0, ne = 256))
        assertEquals(0x1C, marked.cla)
        assertEquals(256, marked.ne)
        assertNull(CommandApdu(0, 0, 0, 0).ne)
    }

    @Test
    fun split_size_must_be_positive() {
        assertFailsWith<IllegalArgumentException> { CommandChaining.split(CommandApdu(0, 0, 0, 0), 0) }
    }
}
