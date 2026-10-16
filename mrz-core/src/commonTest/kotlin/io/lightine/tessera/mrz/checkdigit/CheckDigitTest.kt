package io.lightine.tessera.mrz.checkdigit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CheckDigitTest {
    @Test
    fun computes_check_digit_for_specimen_document_number_from_icao_doc_9303_part_4() {
        assertEquals('3', computeCheckDigit("L898902C<"))
    }

    @Test
    fun computes_check_digit_for_specimen_date_of_birth_from_icao_doc_9303_part_4() {
        assertEquals('1', computeCheckDigit("690806"))
    }

    @Test
    fun computes_check_digit_for_specimen_date_of_expiry_from_icao_doc_9303_part_4() {
        assertEquals('6', computeCheckDigit("940623"))
    }

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

    // ICAO Doc 9303 Part 3 Appendix A canonical specimens.
    // Independent of the Part 4 examples already covered above.

    @Test
    fun computes_part_3_appendix_a_example_1_date_specimen() {
        // Appendix A Example 1: date 27 July 1952 → 520727 → check digit 3.
        assertEquals('3', computeCheckDigit("520727"))
    }

    @Test
    fun computes_part_3_appendix_a_example_2_document_number_specimen() {
        // Appendix A Example 2: AB2134 padded to 9-character field → AB2134<<< → check digit 5.
        assertEquals('5', computeCheckDigit("AB2134<<<"))
    }

    @Test
    fun computes_part_3_appendix_a_example_3_td3_composite_specimen() {
        // Appendix A Example 3: TD3 lower line HA672242<6YTO5802254M9601086<<<<<<<<<<<<<<0,
        // composite input = positions 1-10, 14-20, 22-43 (1-indexed). Composite check digit = 8.
        val compositeInput = "HA672242<6" + "5802254" + "9601086<<<<<<<<<<<<<<0"
        assertEquals('8', computeCheckDigit(compositeInput))
    }

    @Test
    fun computes_part_3_appendix_a_example_4_td1_composite_specimen() {
        // Appendix A Example 4: TD1 upper line I<YTOD231458907<<<<<<<<<<<<<<< (positions 6-30)
        // and middle line 3407127M9507122YTO<<<<<<<<<<<2 (positions 1-7, 9-15, 19-29).
        // Composite check digit = 2.
        val compositeInput =
            "D231458907<<<<<<<<<<<<<<<" + "3407127" + "9507122" + "<<<<<<<<<<<"
        assertEquals('2', computeCheckDigit(compositeInput))
    }

    @Test
    fun computes_part_3_appendix_a_example_5_td2_composite_specimen() {
        // Appendix A Example 5: TD2 lower line HA672242<6YTO5802254M9601086<<<<<<<8,
        // composite input = positions 1-10, 14-20, 22-35 (1-indexed). Composite check digit = 8.
        val compositeInput = "HA672242<6" + "5802254" + "9601086<<<<<<<"
        assertEquals('8', computeCheckDigit(compositeInput))
    }
}
