package io.lightine.tessera.mrz.parsing

import io.lightine.tessera.mrz.Specimens
import io.lightine.tessera.mrz.formats.FieldSpec
import io.lightine.tessera.mrz.formats.Td2FormatSpec
import io.lightine.tessera.mrz.model.TD2
import io.lightine.tessera.mrz.overwrite
import io.lightine.tessera.types.errors.MrzCharacterSetViolation
import io.lightine.tessera.types.errors.MrzCheckDigitMismatch
import io.lightine.tessera.types.errors.MrzDateNotInCalendar
import io.lightine.tessera.types.errors.MrzInvalidLength
import io.lightine.tessera.types.errors.MrzInvalidSexValue
import io.lightine.tessera.types.errors.MrzUnknownCountryCode
import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.ReadMethod
import io.lightine.tessera.types.vocabulary.Sex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class MrzParserTd2Test {
    private val ref2026 = Instant.parse("2026-05-04T12:00:00Z")

    // The project's TD2 specimen. ICAO Doc 9303 Part 6: 2 lines × 36 characters. Document type
    // `I` (identity card), issuing state UTO (fictional). Document number T62000145, DOB 900315,
    // expiry 340314, sex F, optional data all-filler.
    private val specimenLines = Specimens.TD2
    private val specimenLine1 = specimenLines[0]
    private val specimenLine2 = specimenLines[1]

    private fun line2With(
        field: FieldSpec,
        text: String,
    ): String = specimenLines.overwrite(field, text)[1]

    // --- Happy path ---

    @Test
    fun parses_specimen_td2_into_success_with_expected_common_fields() {
        val result = MrzParser.parseTD2(specimenLines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        val td2 = assertIs<TD2>(success.document)

        assertEquals(MrzFormat.TD2, td2.format)
        assertEquals("I", td2.commonFields.documentType.rawCode)
        assertEquals("UTO", td2.commonFields.issuingState.rawCode)
        assertEquals("T62000145", td2.commonFields.documentNumber)
        assertEquals("UTO", td2.commonFields.nationality.rawCode)
        assertEquals(Sex.FEMALE, td2.commonFields.sex)
        assertEquals("SPECIMEN<<ALEX<MORGAN<<<<<<<<<<", td2.commonFields.rawNameField)
        assertEquals("SPECIMEN", td2.commonFields.primaryIdentifier)
        assertEquals("ALEX MORGAN", td2.commonFields.secondaryIdentifier)
        assertEquals(false, td2.commonFields.nameTruncated)
    }

    @Test
    fun parses_specimen_td2_into_success_with_expected_check_digits() {
        val result = MrzParser.parseTD2(specimenLines, referenceTime = ref2026)
        val td2 = assertIs<TD2>(assertIs<ParseResult.Success>(result).document)
        val checks = td2.commonFields.checkDigits

        assertEquals('7', checks.documentNumber)
        assertEquals('2', checks.dateOfBirth)
        assertEquals('1', checks.dateOfExpiry)
        assertNull(checks.optionalData, "TD2 has no per-field check digit on optional data")
        assertEquals('8', checks.composite)
    }

    @Test
    fun parses_specimen_td2_into_success_with_expected_optional_data() {
        val result = MrzParser.parseTD2(specimenLines, referenceTime = ref2026)
        val td2 = assertIs<TD2>(assertIs<ParseResult.Success>(result).document)
        assertEquals("<<<<<<<", td2.optionalData)
    }

    @Test
    fun success_metadata_uses_backend_string_input_read_method() {
        val result = MrzParser.parseTD2(specimenLines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        assertEquals(ReadMethod.BACKEND_STRING_INPUT, success.metadata.readMethod)
    }

    @Test
    fun success_metadata_has_no_validation_failures_for_clean_specimen() {
        // The fictional country code "UTO" is not in the SDK's deliberate starter set, so
        // the validator emits two MrzUnknownCountryCode warnings (issuingState, nationality).
        // Warnings do not downgrade Success to PartialSuccess; only validationFailures do.
        val result = MrzParser.parseTD2(specimenLines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        assertTrue(success.metadata.validationFailures.isEmpty())
        // UTO ("Utopia") is recognized as ICAO §5 Part G specimen code (category OTHER)
        // — no MrzUnknownCountryCode warnings expected for clean specimen.
        val unknownCountryWarnings = success.metadata.warnings.filterIsInstance<MrzUnknownCountryCode>()
        assertTrue(unknownCountryWarnings.isEmpty(), "Expected no warnings; got $unknownCountryWarnings")
    }

    @Test
    fun raw_lines_round_trip_through_the_parser_unchanged() {
        val result = MrzParser.parseTD2(specimenLines, referenceTime = ref2026)
        val td2 = assertIs<TD2>(assertIs<ParseResult.Success>(result).document)
        assertEquals(specimenLines, td2.rawLines)
    }

    // --- Input forms ---

    @Test
    fun string_input_with_lf_line_separator_is_equivalent_to_list_input() {
        val joined = specimenLines.joinToString("\n")
        val fromString = MrzParser.parseTD2(joined, referenceTime = ref2026)
        val fromList = MrzParser.parseTD2(specimenLines, referenceTime = ref2026)
        assertEquals(fromList, fromString)
    }

    // --- Validation wiring (parser → validator) ---

    @Test
    fun returns_partial_success_with_check_digit_mismatch_when_document_number_check_digit_is_corrupted() {
        // Flip docCheck '7' to '0'. Composite digit input is also affected; the test asserts the
        // per-field finding narrowly.
        val corrupted = line2With(Td2FormatSpec.documentNumberCheckDigit, "0")
        val result = MrzParser.parseTD2(listOf(specimenLine1, corrupted), referenceTime = ref2026)
        val partial = assertIs<ParseResult.PartialSuccess>(result)

        val mismatch =
            partial.metadata.validationFailures
                .filterIsInstance<MrzCheckDigitMismatch>()
                .firstOrNull { it.field == MrzField.DOCUMENT_NUMBER }
        assertTrue(mismatch != null, "Expected MrzCheckDigitMismatch for DOCUMENT_NUMBER; got ${partial.metadata.validationFailures}")
        assertEquals('7', mismatch.expected)
        assertEquals('0', mismatch.observed)
    }

    @Test
    fun returns_partial_success_with_composite_check_digit_mismatch_when_optional_data_is_corrupted() {
        // TD2 has no per-field check digit on optional data — the composite digit is the only
        // check on the optional-data slot. Corrupting the optional data invalidates ONLY the
        // composite digit (no MrzCheckDigitMismatch for MrzField.OPTIONAL_DATA exists for TD2).
        val corruptedLine2 = line2With(Td2FormatSpec.optionalData, "ABCDEFG")
        val result = MrzParser.parseTD2(listOf(specimenLine1, corruptedLine2), referenceTime = ref2026)
        val partial = assertIs<ParseResult.PartialSuccess>(result)

        val compositeMismatch =
            partial.metadata.validationFailures
                .filterIsInstance<MrzCheckDigitMismatch>()
                .firstOrNull { it.field == MrzField.COMPOSITE }
        assertTrue(compositeMismatch != null, "Expected MrzCheckDigitMismatch for COMPOSITE; got ${partial.metadata.validationFailures}")
        assertEquals('8', compositeMismatch.observed)

        val optionalDataMismatch =
            partial.metadata.validationFailures
                .filterIsInstance<MrzCheckDigitMismatch>()
                .firstOrNull { it.field == MrzField.OPTIONAL_DATA }
        assertNull(optionalDataMismatch, "TD2 has no OPTIONAL_DATA check digit; the validator must not produce a per-field mismatch for it")
    }

    @Test
    fun returns_partial_success_with_invalid_sex_value_when_sex_character_is_not_in_allowed_set() {
        val line2WithInvalidSex = line2With(Td2FormatSpec.sex, "Q")
        val result = MrzParser.parseTD2(listOf(specimenLine1, line2WithInvalidSex), referenceTime = ref2026)
        val partial = assertIs<ParseResult.PartialSuccess>(result)
        val td2 = assertIs<TD2>(partial.document)

        assertEquals(Sex.UNSPECIFIED, td2.commonFields.sex)
        assertEquals('Q', td2.commonFields.rawSex)

        val invalid =
            partial.metadata.validationFailures
                .filterIsInstance<MrzInvalidSexValue>()
                .firstOrNull()
        assertTrue(invalid != null)
        assertEquals('Q', invalid.observed)
        // Sex position on TD2: line 1 index 20, global = 36 + 20 = 56
        assertEquals(56, invalid.position)
    }

    @Test
    fun returns_partial_success_with_MrzDateNotInCalendar_when_birth_date_is_calendar_invalid() {
        // Birth date "900230" — Feb 30 doesn't exist. Birth and composite check digits also fail.
        val line2 = line2With(Td2FormatSpec.dateOfBirth, "900230")
        val result = MrzParser.parseTD2(listOf(specimenLine1, line2), referenceTime = ref2026)
        val partial = assertIs<ParseResult.PartialSuccess>(result)

        val failure =
            partial.metadata.validationFailures
                .filterIsInstance<MrzDateNotInCalendar>()
                .firstOrNull { it.field == MrzField.DATE_OF_BIRTH }
        assertTrue(failure != null)
        assertEquals("90", failure.rawYear)
        assertEquals("02", failure.rawMonth)
        assertEquals("30", failure.rawDay)
        // DOB position on TD2: line 1 index 13, global = 36 + 13 = 49
        assertEquals(49, failure.position)
    }

    // --- Error paths ---

    @Test
    fun fails_with_mrz_invalid_length_when_input_has_one_line() {
        val result = MrzParser.parseTD2(listOf(specimenLine1), referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val error = assertIs<MrzInvalidLength>(failure.error)

        assertEquals(MrzFormat.TD2, error.format)
        assertEquals(2, error.expectedLineCount)
        assertEquals(36, error.expectedLineLength)
        assertEquals(1, error.observedLineCount)
    }

    @Test
    fun fails_with_mrz_invalid_length_when_a_line_is_too_short() {
        val shortLine2 = specimenLine2.dropLast(1)
        val result = MrzParser.parseTD2(listOf(specimenLine1, shortLine2), referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val error = assertIs<MrzInvalidLength>(failure.error)

        assertEquals(MrzFormat.TD2, error.format)
        assertEquals(listOf(36, 35), error.observedLineLengths)
    }

    @Test
    fun fails_with_mrz_invalid_length_when_lines_have_td3_length_instead_of_td2_length() {
        // TD3-shape input (2 × 44) must fail TD2's line-shape validation rather than being
        // accepted with the wrong slicing.
        val result = MrzParser.parseTD2(Specimens.TD3, referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val error = assertIs<MrzInvalidLength>(failure.error)
        assertEquals(MrzFormat.TD2, error.format)
        assertEquals(36, error.expectedLineLength)
        assertEquals(listOf(44, 44), error.observedLineLengths)
    }

    @Test
    fun fails_with_mrz_character_set_violation_for_lowercase_in_line_one() {
        val line1WithLowercase = "i" + specimenLine1.drop(1)
        val result = MrzParser.parseTD2(listOf(line1WithLowercase, specimenLine2), referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val violation = assertIs<MrzCharacterSetViolation>(failure.error)

        assertEquals('i', violation.offendingCharacter)
        assertEquals(0, violation.position)
    }

    @Test
    fun mrz_character_set_violation_records_global_position_for_violation_in_line_two() {
        // Replace position 0 of line 2 with a space; global position = 36 + 0 = 36
        val line2WithSpace = " " + specimenLine2.drop(1)
        val result = MrzParser.parseTD2(listOf(specimenLine1, line2WithSpace), referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val violation = assertIs<MrzCharacterSetViolation>(failure.error)

        assertEquals(' ', violation.offendingCharacter)
        assertEquals(36, violation.position)
    }
}
