package io.lightine.tessera.mrz.checkdigit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CheckDigitTest {
    // The Doc 9303 check-digit examples (Part 3 Appendix A, and the document number, birth and expiry
    // fields of the Part 7 Figure B-1 specimen) are checked in conformance/IcaoConformanceTest.kt.

    @Test
    fun applies_first_weight_of_seven_to_a_single_letter() {
        assertEquals('0', computeCheckDigit("A"))
    }

    @Test
    fun applies_first_weight_of_seven_to_a_single_digit() {
        assertEquals('7', computeCheckDigit("1"))
    }

    @Test
    fun returns_zero_for_empty_input() {
        assertEquals('0', computeCheckDigit(""))
    }

    @Test
    fun treats_filler_character_as_zero() {
        assertEquals('0', computeCheckDigit("<<<<<<"))
    }

    @Test
    fun rejects_lowercase_letter() {
        assertFailsWith<IllegalArgumentException> { computeCheckDigit("abc") }
    }

    @Test
    fun rejects_space() {
        assertFailsWith<IllegalArgumentException> { computeCheckDigit("AB CD") }
    }

    @Test
    fun rejects_punctuation() {
        assertFailsWith<IllegalArgumentException> { computeCheckDigit("AB!") }
    }

    // TES-198: a non-ASCII letter is outside the alphabet. The throw is declared with @Throws so
    // Swift sees a throwing call instead of a crash.
    @Test
    fun rejects_non_ascii_letter() {
        assertFailsWith<IllegalArgumentException> { computeCheckDigit("ä") }
    }

    @Test
    fun error_message_names_the_position_but_not_the_character() {
        val error = assertFailsWith<IllegalArgumentException> { computeCheckDigit("AB\u0416C") }
        val message = error.message.orEmpty()
        kotlin.test.assertTrue(message.contains("position 2"), message)
        kotlin.test.assertFalse(message.contains("\u0416"), message)
    }
}
