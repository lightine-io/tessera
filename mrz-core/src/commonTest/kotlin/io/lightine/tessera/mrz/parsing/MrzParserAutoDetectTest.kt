package io.lightine.tessera.mrz.parsing

import io.lightine.tessera.mrz.Specimens
import io.lightine.tessera.mrz.model.MrvA
import io.lightine.tessera.mrz.model.MrvB
import io.lightine.tessera.mrz.model.TD1
import io.lightine.tessera.mrz.model.TD2
import io.lightine.tessera.mrz.model.TD3
import io.lightine.tessera.types.errors.MrzCharacterSetViolation
import io.lightine.tessera.types.errors.MrzFormatNotDetected
import io.lightine.tessera.types.vocabulary.MrzFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class MrzParserAutoDetectTest {
    private val ref2026 = Instant.parse("2026-05-04T12:00:00Z")

    // The project's specimens for each format.
    private val td1Lines = Specimens.TD1
    private val td2Lines = Specimens.TD2
    private val td3Lines = Specimens.TD3
    private val mrvALines = Specimens.MRV_A
    private val mrvBLines = Specimens.MRV_B

    // --- Happy path: each format dispatches to the right typed document ---

    @Test
    fun auto_detect_dispatches_td1_input_to_parseTD1() {
        val result = MrzParser.parse(td1Lines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        val td1 = assertIs<TD1>(success.document)
        assertEquals(MrzFormat.TD1, td1.format)
    }

    @Test
    fun auto_detect_dispatches_td2_input_to_parseTD2() {
        val result = MrzParser.parse(td2Lines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        val td2 = assertIs<TD2>(success.document)
        assertEquals(MrzFormat.TD2, td2.format)
    }

    @Test
    fun auto_detect_dispatches_td3_input_to_parseTD3() {
        val result = MrzParser.parse(td3Lines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        val td3 = assertIs<TD3>(success.document)
        assertEquals(MrzFormat.TD3, td3.format)
    }

    @Test
    fun auto_detect_dispatches_mrva_input_to_parseMRVA() {
        val result = MrzParser.parse(mrvALines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        val mrvA = assertIs<MrvA>(success.document)
        assertEquals(MrzFormat.MRV_A, mrvA.format)
    }

    @Test
    fun auto_detect_dispatches_mrvb_input_to_parseMRVB() {
        val result = MrzParser.parse(mrvBLines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        val mrvB = assertIs<MrvB>(success.document)
        assertEquals(MrzFormat.MRV_B, mrvB.format)
    }

    // --- String input equivalence ---

    @Test
    fun string_input_is_equivalent_to_list_input_for_each_format() {
        for (lines in listOf(td1Lines, td2Lines, td3Lines, mrvALines, mrvBLines)) {
            val fromString = MrzParser.parse(lines.joinToString("\n"), referenceTime = ref2026)
            val fromList = MrzParser.parse(lines, referenceTime = ref2026)
            assertEquals(
                fromList,
                fromString,
                "Auto-detect must produce the same result for string vs list input on ${lines.size}x${lines.first().length}",
            )
        }
    }

    // --- Disambiguation: 2x44 with V vs non-V leading character ---

    @Test
    fun two_lines_of_44_with_leading_V_dispatches_to_MRV_A_not_TD3() {
        val visaShaped = mrvALines
        val result = MrzParser.parse(visaShaped, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        assertIs<MrvA>(success.document)
    }

    @Test
    fun two_lines_of_44_with_non_V_leading_dispatches_to_TD3_not_MRV_A() {
        val passportShaped = td3Lines
        val result = MrzParser.parse(passportShaped, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        assertIs<TD3>(success.document)
    }

    // --- Disambiguation: 2x36 with V vs non-V leading character ---

    @Test
    fun two_lines_of_36_with_leading_V_dispatches_to_MRV_B_not_TD2() {
        val result = MrzParser.parse(mrvBLines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        assertIs<MrvB>(success.document)
    }

    @Test
    fun two_lines_of_36_with_non_V_leading_dispatches_to_TD2_not_MRV_B() {
        val result = MrzParser.parse(td2Lines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        assertIs<TD2>(success.document)
    }

    // --- Disambiguation edge cases ---

    @Test
    fun two_character_visa_code_VA_in_2x44_input_dispatches_to_MRV_A() {
        // "VA" as a two-character visa code starts with V; dispatch follows leading character only.
        val line1 = "VA" + td3Lines[0].drop(2)
        val result = MrzParser.parse(listOf(line1, mrvALines[1]), referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result) // line 2 still has valid digits
        assertIs<MrvA>(success.document)
    }

    @Test
    fun two_character_passport_code_PP_in_2x44_input_dispatches_to_TD3() {
        val line1 = "PP" + td3Lines[0].drop(2)
        val result = MrzParser.parse(listOf(line1, td3Lines[1]), referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        assertIs<TD3>(success.document)
    }

    @Test
    fun unrecognized_leading_character_in_2x44_input_dispatches_to_TD3() {
        // Document type "Z<" — not V, so dispatch is TD3. The validator surfaces
        // MrzUnknownDocumentTypeCode as a warning; the document still parses as TD3.
        val line1 = "Z<" + td3Lines[0].drop(2)
        val result = MrzParser.parse(listOf(line1, td3Lines[1]), referenceTime = ref2026)
        // Result may be Success or PartialSuccess depending on check-digit alignment, but the
        // document type is TD3.
        when (result) {
            is ParseResult.Success -> assertIs<TD3>(result.document)
            is ParseResult.PartialSuccess -> assertIs<TD3>(result.document)
            else -> error("Expected TD3 dispatch; got ${result::class.simpleName}")
        }
    }

    @Test
    fun filler_leading_character_in_2x44_input_dispatches_to_TD3() {
        // "<<" is the malformed leading-filler case for the document-type slot. Auto-detect
        // dispatches to TD3 (not visa, because line[0][0] is '<' not 'V').
        val line1 = "<<" + td3Lines[0].drop(2)
        val result = MrzParser.parse(listOf(line1, td3Lines[1]), referenceTime = ref2026)
        when (result) {
            is ParseResult.Success -> assertIs<TD3>(result.document)
            is ParseResult.PartialSuccess -> assertIs<TD3>(result.document)
            else -> error("Expected TD3 dispatch; got ${result::class.simpleName}")
        }
    }

    // --- Failure path: format not detected ---

    @Test
    fun fails_with_MrzFormatNotDetected_for_empty_input() {
        val result = MrzParser.parse("", referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val error = assertIs<MrzFormatNotDetected>(failure.error)
        assertEquals(0, error.observedLineCount)
        assertEquals(emptyList<Int>(), error.observedLineLengths)
    }

    @Test
    fun fails_with_MrzFormatNotDetected_for_single_line_input() {
        val result = MrzParser.parse(listOf(td3Lines[0]), referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val error = assertIs<MrzFormatNotDetected>(failure.error)
        assertEquals(1, error.observedLineCount)
        assertEquals(listOf(44), error.observedLineLengths)
    }

    @Test
    fun fails_with_MrzFormatNotDetected_for_four_line_input() {
        // No format supports 4 lines.
        val result = MrzParser.parse(listOf("aaa", "bbb", "ccc", "ddd"), referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val error = assertIs<MrzFormatNotDetected>(failure.error)
        assertEquals(4, error.observedLineCount)
    }

    @Test
    fun fails_with_MrzFormatNotDetected_for_2_lines_of_40_chars() {
        // 2x40 is not a supported shape (TD2 is 2x36, TD3 is 2x44).
        val line = td3Lines[0].take(40)
        val result = MrzParser.parse(listOf(line, line), referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val error = assertIs<MrzFormatNotDetected>(failure.error)
        assertEquals(2, error.observedLineCount)
        assertEquals(listOf(40, 40), error.observedLineLengths)
    }

    @Test
    fun fails_with_MrzFormatNotDetected_for_2_lines_of_mixed_lengths() {
        // Line 1 is 44 chars (TD3 shape), line 2 is 36 chars (TD2 shape). Mixed shapes
        // do not match any format — the dispatcher requires every line at the expected length.
        val result = MrzParser.parse(listOf(td3Lines[0], td2Lines[1]), referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val error = assertIs<MrzFormatNotDetected>(failure.error)
        assertEquals(2, error.observedLineCount)
        assertEquals(listOf(44, 36), error.observedLineLengths)
    }

    @Test
    fun fails_with_MrzFormatNotDetected_for_3_lines_of_44_chars() {
        // 3 lines but length 44 — TD1 expects 30, no format expects 3x44.
        val line = td3Lines[0]
        val result = MrzParser.parse(listOf(line, line, line), referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val error = assertIs<MrzFormatNotDetected>(failure.error)
        assertEquals(3, error.observedLineCount)
        assertEquals(listOf(44, 44, 44), error.observedLineLengths)
    }

    @Test
    fun failure_metadata_uses_backend_string_input_read_method() {
        val result = MrzParser.parse("", referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        assertEquals(io.lightine.tessera.types.vocabulary.ReadMethod.BACKEND_STRING_INPUT, failure.metadata.readMethod)
        assertTrue(failure.metadata.warnings.isEmpty())
        assertTrue(failure.metadata.validationFailures.isEmpty())
    }

    // --- Auto-detect does not pre-check alphabet; format-specific parser surfaces violations ---

    @Test
    fun auto_detect_dispatches_then_format_specific_parser_surfaces_character_set_violation() {
        // Lowercase in line 1 is a character-set violation. Auto-detect dispatches by shape and
        // leading character (the lowercase 'p' is non-V → dispatches to TD3); parseTD3 then
        // reports the violation. The result is Failure with MrzCharacterSetViolation, NOT
        // MrzFormatNotDetected — the format WAS detected; the input was malformed for the
        // detected format.
        val line1 = "p<" + td3Lines[0].drop(2)
        val result = MrzParser.parse(listOf(line1, td3Lines[1]), referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        assertIs<MrzCharacterSetViolation>(failure.error)
    }
}
