package io.lightine.tessera.mrz.parsing

import io.lightine.tessera.mrz.Specimens
import io.lightine.tessera.mrz.formats.FieldSpec
import io.lightine.tessera.mrz.formats.Td3FormatSpec
import io.lightine.tessera.mrz.model.MrzDateInferenceMethod
import io.lightine.tessera.mrz.model.TD3
import io.lightine.tessera.mrz.overwrite
import io.lightine.tessera.mrz.withCheckDigitsRecomputed
import io.lightine.tessera.types.errors.MrzCharacterSetViolation
import io.lightine.tessera.types.errors.MrzCheckDigitMismatch
import io.lightine.tessera.types.errors.MrzDateNotInCalendar
import io.lightine.tessera.types.errors.MrzExpiryDatePast
import io.lightine.tessera.types.errors.MrzInvalidLength
import io.lightine.tessera.types.errors.MrzInvalidSexValue
import io.lightine.tessera.types.errors.MrzUnknownCountryCode
import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.ReadMethod
import io.lightine.tessera.types.vocabulary.Sex
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant

class MrzParserTest {
    private val ref2026 = Instant.parse("2026-05-04T12:00:00Z")

    // The project's TD3 specimen.
    private val specimenLines = Specimens.TD3
    private val specimenLine1 = specimenLines[0]
    private val specimenLine2 = specimenLines[1]

    private fun line2With(
        field: FieldSpec,
        text: String,
    ): String = specimenLines.overwrite(field, text)[1]

    // --- Happy path ---

    @Test
    fun parses_specimen_td3_into_success_with_expected_common_fields() {
        val result = MrzParser.parseTD3(specimenLines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        val td3 = assertIs<TD3>(success.document)

        assertEquals(MrzFormat.TD3, td3.format)
        assertEquals("P", td3.commonFields.documentType.rawCode)
        assertEquals("UTO", td3.commonFields.issuingState.rawCode)
        assertEquals("T22000129", td3.commonFields.documentNumber)
        assertEquals("UTO", td3.commonFields.nationality.rawCode)
        assertEquals(Sex.FEMALE, td3.commonFields.sex)
        assertEquals("SPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<", td3.commonFields.rawNameField)
        assertEquals("SPECIMEN", td3.commonFields.primaryIdentifier)
        assertEquals("ALEX MORGAN", td3.commonFields.secondaryIdentifier)
        assertEquals(false, td3.commonFields.nameTruncated)
    }

    @Test
    fun parses_specimen_td3_into_success_with_expected_dates() {
        val result = MrzParser.parseTD3(specimenLines, referenceTime = ref2026)
        val td3 = assertIs<TD3>(assertIs<ParseResult.Success>(result).document)

        // DOB 900315 with ref 2026 → SLIDING_WINDOW_BIRTH yields 1990
        assertEquals(1990, td3.commonFields.dateOfBirth.computedYear)
        assertEquals(MrzDateInferenceMethod.SLIDING_WINDOW_BIRTH, td3.commonFields.dateOfBirth.inferenceMethod)

        // Expiry 340314 with ref 2026 → inside [refYear-10, refYear+50] → 2034
        assertEquals(2034, td3.commonFields.dateOfExpiry.computedYear)
        assertEquals(MrzDateInferenceMethod.SLIDING_WINDOW_EXPIRY, td3.commonFields.dateOfExpiry.inferenceMethod)

        // A variant expiring 940623, check digits recomputed: with ref 2026 it falls outside
        // [refYear-10, refYear+50] → RAW_ONLY (locked behavior; see EXPIRY_PAST_WINDOW_YEARS in MrzDate)
        val oldExpiry = specimenLines.overwrite(Td3FormatSpec.dateOfExpiry, "940623").withCheckDigitsRecomputed(Td3FormatSpec)
        val oldTd3 = assertIs<TD3>(assertIs<ParseResult.Success>(MrzParser.parseTD3(oldExpiry, referenceTime = ref2026)).document)
        assertEquals(MrzDateInferenceMethod.RAW_ONLY, oldTd3.commonFields.dateOfExpiry.inferenceMethod)
        assertEquals("94", oldTd3.commonFields.dateOfExpiry.rawYear)
    }

    @Test
    fun parses_specimen_td3_into_success_with_expected_check_digits() {
        val result = MrzParser.parseTD3(specimenLines, referenceTime = ref2026)
        val td3 = assertIs<TD3>(assertIs<ParseResult.Success>(result).document)
        val checks = td3.commonFields.checkDigits

        assertEquals('3', checks.documentNumber)
        assertEquals('2', checks.dateOfBirth)
        assertEquals('1', checks.dateOfExpiry)
        assertEquals('2', checks.optionalData)
        assertEquals('0', checks.composite)
    }

    @Test
    fun parses_specimen_td3_into_success_with_expected_personal_number() {
        val result = MrzParser.parseTD3(specimenLines, referenceTime = ref2026)
        val td3 = assertIs<TD3>(assertIs<ParseResult.Success>(result).document)

        assertEquals("S98765432<<<<<", td3.personalNumber)
        assertEquals('2', td3.personalNumberCheckDigit)
    }

    @Test
    fun success_metadata_uses_backend_string_input_read_method() {
        val result = MrzParser.parseTD3(specimenLines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        assertEquals(ReadMethod.BACKEND_STRING_INPUT, success.metadata.readMethod)
    }

    @Test
    fun success_metadata_has_no_validation_failures_for_clean_specimen() {
        // The project's specimen uses code UTO (Utopia) per Doc 9303 Part 3 §5 Part G;
        // recognized as OTHER category after the country-code table expansion. Clean
        // specimen produces no validation failures and no MrzUnknownCountryCode warnings.
        val result = MrzParser.parseTD3(specimenLines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        assertTrue(success.metadata.validationFailures.isEmpty())
        val unknownCountryWarnings = success.metadata.warnings.filterIsInstance<MrzUnknownCountryCode>()
        assertTrue(
            unknownCountryWarnings.isEmpty(),
            "UTO is in Part G of ICAO §5; expected no MrzUnknownCountryCode warnings, got $unknownCountryWarnings",
        )
    }

    @Test
    fun raw_lines_round_trip_through_the_parser_unchanged() {
        val result = MrzParser.parseTD3(specimenLines, referenceTime = ref2026)
        val td3 = assertIs<TD3>(assertIs<ParseResult.Success>(result).document)
        assertEquals(specimenLines, td3.rawLines)
    }

    // --- Input forms ---

    @Test
    fun string_input_with_lf_line_separator_is_equivalent_to_list_input() {
        val joined = specimenLines.joinToString("\n")
        val fromString = MrzParser.parseTD3(joined, referenceTime = ref2026)
        val fromList = MrzParser.parseTD3(specimenLines, referenceTime = ref2026)
        assertEquals(fromList, fromString)
    }

    @Test
    fun string_input_with_crlf_line_separator_normalizes_to_same_result() {
        val joined = specimenLines.joinToString("\r\n")
        val result = MrzParser.parseTD3(joined, referenceTime = ref2026)
        assertIs<ParseResult.Success>(result)
    }

    @Test
    fun string_input_with_trailing_whitespace_is_accepted() {
        val joined = specimenLines.joinToString("\n") + "   \n  "
        val result = MrzParser.parseTD3(joined, referenceTime = ref2026)
        assertIs<ParseResult.Success>(result)
    }

    @Test
    fun string_input_with_leading_blank_lines_is_accepted() {
        val joined = "\n\n" + specimenLines.joinToString("\n")
        val result = MrzParser.parseTD3(joined, referenceTime = ref2026)
        assertIs<ParseResult.Success>(result)
    }

    // --- Format-specific field handling ---

    @Test
    fun parses_two_character_document_type_code_pp_into_recognized_document_type() {
        val line1 = specimenLines.overwrite(Td3FormatSpec.documentType, "PP")[0]
        val result = MrzParser.parseTD3(listOf(line1, specimenLine2), referenceTime = ref2026)
        val td3 = assertIs<TD3>(assertIs<ParseResult.Success>(result).document)

        assertEquals("PP", td3.commonFields.documentType.rawCode)
        assertTrue(td3.commonFields.documentType.isRecognized)
    }

    @Test
    fun parses_unrecognized_document_type_code_into_unrecognized_value_class() {
        val line1 = specimenLines.overwrite(Td3FormatSpec.documentType, "ZZ")[0]
        val result = MrzParser.parseTD3(listOf(line1, specimenLine2), referenceTime = ref2026)
        val td3 = assertIs<TD3>(assertIs<ParseResult.Success>(result).document)

        assertEquals("ZZ", td3.commonFields.documentType.rawCode)
        assertTrue(!td3.commonFields.documentType.isRecognized)
    }

    @Test
    fun parses_male_sex_character() {
        val line2 = line2With(Td3FormatSpec.sex, "M")
        val result = MrzParser.parseTD3(listOf(specimenLine1, line2), referenceTime = ref2026)
        val td3 = assertIs<TD3>(assertIs<ParseResult.Success>(result).document)
        assertEquals(Sex.MALE, td3.commonFields.sex)
    }

    @Test
    fun parses_filler_sex_character_as_unspecified() {
        val line2 = line2With(Td3FormatSpec.sex, "<")
        val result = MrzParser.parseTD3(listOf(specimenLine1, line2), referenceTime = ref2026)
        val td3 = assertIs<TD3>(assertIs<ParseResult.Success>(result).document)
        assertEquals(Sex.UNSPECIFIED, td3.commonFields.sex)
    }

    // --- Validation wiring (parser → validator) ---

    @Test
    fun returns_partial_success_with_check_digit_mismatch_when_document_number_check_digit_is_corrupted() {
        // Specimen line 2 with the document-number check digit changed from '3' to '7'.
        // Composite digit must also be invalidated to remain consistent — so we corrupt only
        // the per-field digit and accept that the composite digit will also fail. The test
        // asserts the per-field finding is present.
        val corrupted = line2With(Td3FormatSpec.documentNumberCheckDigit, "7")
        val result = MrzParser.parseTD3(listOf(specimenLine1, corrupted), referenceTime = ref2026)
        val partial = assertIs<ParseResult.PartialSuccess>(result)

        val mismatch =
            partial.metadata.validationFailures
                .filterIsInstance<MrzCheckDigitMismatch>()
                .firstOrNull { it.field == MrzField.DOCUMENT_NUMBER }
        assertTrue(mismatch != null, "Expected MrzCheckDigitMismatch for DOCUMENT_NUMBER; got ${partial.metadata.validationFailures}")
        assertEquals('3', mismatch.expected)
        assertEquals('7', mismatch.observed)
    }

    @Test
    fun returns_partial_success_with_invalid_sex_value_when_sex_character_is_not_in_allowed_set() {
        val line2WithInvalidSex = line2With(Td3FormatSpec.sex, "Q")
        val result = MrzParser.parseTD3(listOf(specimenLine1, line2WithInvalidSex), referenceTime = ref2026)
        val partial = assertIs<ParseResult.PartialSuccess>(result)
        val td3 = assertIs<TD3>(partial.document)

        // sex enum still defaults to UNSPECIFIED for unrecognized characters; raw character
        // is preserved on CommonFields and surfaced via MrzInvalidSexValue.
        assertEquals(Sex.UNSPECIFIED, td3.commonFields.sex)
        assertEquals('Q', td3.commonFields.rawSex)

        val invalid =
            partial.metadata.validationFailures
                .filterIsInstance<MrzInvalidSexValue>()
                .firstOrNull()
        assertTrue(invalid != null, "Expected MrzInvalidSexValue; got ${partial.metadata.validationFailures}")
        assertEquals('Q', invalid.observed)
        assertEquals(64, invalid.position)
    }

    @Test
    fun returns_partial_success_with_MrzDateNotInCalendar_when_birth_date_is_calendar_invalid() {
        // Birth date "900230" — Feb 30 doesn't exist in any year. Birth-date and composite check
        // digits also mismatch; this test asserts the date-in-calendar failure narrowly.
        val line2 = line2With(Td3FormatSpec.dateOfBirth, "900230")
        val result = MrzParser.parseTD3(listOf(specimenLine1, line2), referenceTime = ref2026)
        val partial = assertIs<ParseResult.PartialSuccess>(result)

        val failure =
            partial.metadata.validationFailures
                .filterIsInstance<MrzDateNotInCalendar>()
                .firstOrNull { it.field == MrzField.DATE_OF_BIRTH }
        assertTrue(
            failure != null,
            "Expected MrzDateNotInCalendar for DATE_OF_BIRTH; got ${partial.metadata.validationFailures}",
        )
        assertEquals("90", failure.rawYear)
        assertEquals("02", failure.rawMonth)
        assertEquals("30", failure.rawDay)
        assertEquals(57, failure.position)
    }

    @Test
    fun returns_partial_success_with_MrzDateNotInCalendar_when_expiry_date_is_calendar_invalid() {
        // Expiry "301301" — month 13 doesn't exist. The expiry and composite check digits will
        // also mismatch; this test asserts the date-in-calendar failure narrowly.
        val line2 = line2With(Td3FormatSpec.dateOfExpiry, "301301")
        val result = MrzParser.parseTD3(listOf(specimenLine1, line2), referenceTime = ref2026)
        val partial = assertIs<ParseResult.PartialSuccess>(result)

        val failure =
            partial.metadata.validationFailures
                .filterIsInstance<MrzDateNotInCalendar>()
                .firstOrNull { it.field == MrzField.DATE_OF_EXPIRY }
        assertTrue(
            failure != null,
            "Expected MrzDateNotInCalendar for DATE_OF_EXPIRY; got ${partial.metadata.validationFailures}",
        )
        assertEquals("30", failure.rawYear)
        assertEquals("13", failure.rawMonth)
        assertEquals("01", failure.rawDay)
        assertEquals(65, failure.position)
    }

    @Test
    fun specimen_with_valid_check_digits_and_sex_returns_success_not_partial_success() {
        val result = MrzParser.parseTD3(specimenLines, referenceTime = ref2026)
        assertIs<ParseResult.Success>(result)
    }

    @Test
    fun preserves_raw_sex_character_verbatim_for_each_recognized_value() {
        val line2Female = line2With(Td3FormatSpec.sex, "F")
        val line2Male = line2With(Td3FormatSpec.sex, "M")
        val line2Filler = line2With(Td3FormatSpec.sex, "<")

        val female = assertIs<TD3>(assertIs<ParseResult.Success>(MrzParser.parseTD3(listOf(specimenLine1, line2Female), ref2026)).document)
        val male = assertIs<TD3>(assertIs<ParseResult.Success>(MrzParser.parseTD3(listOf(specimenLine1, line2Male), ref2026)).document)
        val filler = assertIs<TD3>(assertIs<ParseResult.Success>(MrzParser.parseTD3(listOf(specimenLine1, line2Filler), ref2026)).document)

        assertEquals('F', female.commonFields.rawSex)
        assertEquals('M', male.commonFields.rawSex)
        assertEquals('<', filler.commonFields.rawSex)
    }

    @Test
    fun returns_success_with_expiry_past_warning_when_reference_time_is_after_expiry() {
        // A specimen variant expiring 1994-06-23 (check digits recomputed). Reference 1995-01-01 is inside the parser's [-10y, +50y]
        // window so the expiry parses to a real LocalDate; the validator then sees the date as
        // before the reference and emits MrzExpiryDatePast. Warnings do not downgrade Success
        // to PartialSuccess — only validationFailures do — so the result must remain Success.
        val ref1995 = Instant.parse("1995-01-01T00:00:00Z")
        val expired1994 = specimenLines.overwrite(Td3FormatSpec.dateOfExpiry, "940623").withCheckDigitsRecomputed(Td3FormatSpec)
        val result = MrzParser.parseTD3(expired1994, referenceTime = ref1995)
        val success = assertIs<ParseResult.Success>(result)
        assertTrue(success.metadata.validationFailures.isEmpty())

        val past =
            success.metadata.warnings
                .filterIsInstance<MrzExpiryDatePast>()
                .firstOrNull()
        assertTrue(past != null, "Expected MrzExpiryDatePast in warnings; got ${success.metadata.warnings}")
        assertEquals(LocalDate(1994, 6, 23), past.expiryDate)
        assertEquals(LocalDate(1995, 1, 1), past.referenceDate)
    }

    // --- Error paths ---

    @Test
    fun fails_with_mrz_invalid_length_when_input_has_one_line() {
        val result = MrzParser.parseTD3(listOf(specimenLine1), referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val error = assertIs<MrzInvalidLength>(failure.error)

        assertEquals(MrzFormat.TD3, error.format)
        assertEquals(2, error.expectedLineCount)
        assertEquals(1, error.observedLineCount)
    }

    @Test
    fun fails_with_mrz_invalid_length_when_a_line_is_too_short() {
        val shortLine2 = specimenLine2.dropLast(1)
        val result = MrzParser.parseTD3(listOf(specimenLine1, shortLine2), referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val error = assertIs<MrzInvalidLength>(failure.error)

        assertEquals(listOf(44, 43), error.observedLineLengths)
    }

    @Test
    fun fails_with_mrz_invalid_length_for_empty_input() {
        val result = MrzParser.parseTD3("", referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        assertIs<MrzInvalidLength>(failure.error)
    }

    @Test
    fun fails_with_mrz_character_set_violation_for_lowercase_in_line_one() {
        val line1WithLowercase = "p" + specimenLine1.drop(1)
        val result = MrzParser.parseTD3(listOf(line1WithLowercase, specimenLine2), referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val violation = assertIs<MrzCharacterSetViolation>(failure.error)

        assertEquals('p', violation.offendingCharacter)
        assertEquals(0, violation.position)
    }

    @Test
    fun mrz_character_set_violation_records_global_position_for_violation_in_line_two() {
        // Replace position 0 of line 2 with a space; global position = 44 + 0 = 44
        val line2WithSpace = " " + specimenLine2.drop(1)
        val result = MrzParser.parseTD3(listOf(specimenLine1, line2WithSpace), referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val violation = assertIs<MrzCharacterSetViolation>(failure.error)

        assertEquals(' ', violation.offendingCharacter)
        assertEquals(44, violation.position)
    }

    @Test
    fun parser_does_not_bubble_illegal_argument_exception_from_check_digit_primitive() {
        // The translation-owed item from prior slices: lowercase input must surface as
        // MrzCharacterSetViolation, never as the IllegalArgumentException that
        // computeCheckDigit would throw for non-MRZ-alphabet input.
        val line1WithLowercase = "p" + specimenLine1.drop(1)
        try {
            val result = MrzParser.parseTD3(listOf(line1WithLowercase, specimenLine2), referenceTime = ref2026)
            assertIs<ParseResult.Failure>(result)
            assertIs<MrzCharacterSetViolation>(result.error)
        } catch (e: IllegalArgumentException) {
            fail("Parser should not bubble IllegalArgumentException for non-MRZ-alphabet input; got: ${e.message}")
        }
    }
}
