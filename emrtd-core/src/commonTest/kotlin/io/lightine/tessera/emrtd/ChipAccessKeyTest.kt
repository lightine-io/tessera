package io.lightine.tessera.emrtd

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ChipAccessKeyTest {
    private fun specimenKey() = ChipAccessKey.Mrz("T22000129", '3', "900315", '2', "340314", '5')

    @Test
    fun mrz_key_holds_each_field_then_its_check_digit_as_given() {
        val key = specimenKey()
        assertContentEquals("T22000129390031523403145".encodeToByteArray(), key.secretCopy())
    }

    @Test
    fun mrz_key_keeps_fillers_and_printed_check_digits_without_correcting_them() {
        // A short number with its fillers, and a check digit that does not match: kept as given.
        val key = ChipAccessKey.Mrz("AB12<<<<<", '0', "9003<<", '<', "340314", '9')
        assertContentEquals("AB12<<<<<09003<<<3403149".encodeToByteArray(), key.secretCopy())
    }

    @Test
    fun can_key_holds_the_digits_as_given() {
        assertContentEquals("123456".encodeToByteArray(), ChipAccessKey.Can("123456").secretCopy())
    }

    @Test
    fun to_string_never_shows_the_values() {
        assertEquals("ChipAccessKey.Mrz", specimenKey().toString())
        assertEquals("ChipAccessKey.Can", ChipAccessKey.Can("123456").toString())
    }

    @Test
    fun keys_are_equal_only_to_themselves() {
        val key = specimenKey()
        assertEquals(key, key)
        assertNotEquals<ChipAccessKey>(key, specimenKey())
    }

    @Test
    fun clear_zeroes_the_bytes_and_blocks_further_use() {
        val key = specimenKey()
        val before = key.secret
        assertFalse(key.isCleared)
        key.clear()
        assertTrue(key.isCleared)
        assertTrue(before.all { it.toInt() == 0 })
        assertFailsWith<IllegalStateException> { key.secretCopy() }
    }

    @Test
    fun secret_copy_is_independent_of_the_key() {
        val key = ChipAccessKey.Can("123456")
        val copy = key.secretCopy()
        copy.fill(0)
        assertContentEquals("123456".encodeToByteArray(), key.secretCopy())
    }

    @Test
    fun malformed_values_are_refused_without_echoing_them() {
        val cases: List<() -> Unit> =
            listOf(
                { ChipAccessKey.Mrz("", '0', "900315", '2', "340314", '5') },
                { ChipAccessKey.Mrz("t22000129", '3', "900315", '2', "340314", '5') },
                { ChipAccessKey.Mrz("T22000129", 'x', "900315", '2', "340314", '5') },
                { ChipAccessKey.Mrz("T22000129", '3', "90031", '2', "340314", '5') },
                { ChipAccessKey.Mrz("T22000129", '3', "900315", '2', "34-314", '5') },
                { ChipAccessKey.Can("") },
                { ChipAccessKey.Can("12A456") },
            )
        cases.forEach { case ->
            val e = assertFailsWith<IllegalArgumentException> { case() }
            val message = e.message.orEmpty()
            listOf("T22000129", "t22000129", "34-314", "12A456", "90031").forEach {
                assertFalse(it in message, "message echoes a value: $message")
            }
        }
    }
}
