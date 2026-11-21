package io.lightine.tessera.mrz.parsing

import io.lightine.tessera.mrz.Specimens
import io.lightine.tessera.mrz.formats.FieldSpec
import io.lightine.tessera.mrz.formats.MrvBFormatSpec
import io.lightine.tessera.mrz.model.MrvB
import io.lightine.tessera.mrz.overwrite
import io.lightine.tessera.types.errors.MrzCharacterSetViolation
import io.lightine.tessera.types.errors.MrzCheckDigitMismatch
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

class MrzParserMrvBTest {
    private val ref2026 = Instant.parse("2026-05-04T12:00:00Z")

    // The project's MRV-B specimen. ICAO Doc 9303 Part 7: 2 lines x 36 characters, document
    // type `V` (visa), issuing state UTO (fictional). Optional data is 8 chars of filler.
    // MRV-B has no composite check digit per Part 7.
    private val specimenLine1 = Specimens.MRV_B[0]
    private val specimenLine2 = Specimens.MRV_B[1]
    private val specimenLines = listOf(specimenLine1, specimenLine2)

    private fun line2With(
        field: FieldSpec,
        text: String,
    ): String = Specimens.MRV_B.overwrite(field, text)[1]

    // --- Happy path ---

    @Test
    fun parses_specimen_mrvb_into_success_with_expected_common_fields() {
        val result = MrzParser.parseMRVB(specimenLines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        val mrvB = assertIs<MrvB>(success.document)

        assertEquals(MrzFormat.MRV_B, mrvB.format)
        assertEquals("V", mrvB.commonFields.documentType.rawCode)
        assertTrue(mrvB.commonFields.documentType.isRecognized)
        assertEquals("UTO", mrvB.commonFields.issuingState.rawCode)
        assertEquals("T82000153", mrvB.commonFields.documentNumber)
        assertEquals("UTO", mrvB.commonFields.nationality.rawCode)
        assertEquals(Sex.FEMALE, mrvB.commonFields.sex)
        assertEquals("SPECIMEN<<ALEX<MORGAN<<<<<<<<<<", mrvB.commonFields.rawNameField)
        assertEquals("SPECIMEN", mrvB.commonFields.primaryIdentifier)
        assertEquals("ALEX MORGAN", mrvB.commonFields.secondaryIdentifier)
        assertEquals(false, mrvB.commonFields.nameTruncated)
    }

    @Test
    fun parses_specimen_mrvb_into_success_with_expected_check_digits() {
        val result = MrzParser.parseMRVB(specimenLines, referenceTime = ref2026)
        val mrvB = assertIs<MrvB>(assertIs<ParseResult.Success>(result).document)
        val checks = mrvB.commonFields.checkDigits

        assertEquals('4', checks.documentNumber)
        assertEquals('2', checks.dateOfBirth)
        assertEquals('1', checks.dateOfExpiry)
        assertNull(checks.optionalData, "MRV-B has no per-field check digit on optional data")
        assertNull(checks.composite, "MRV-B has no composite check digit per ICAO Doc 9303 Part 7")
    }

    @Test
    fun parses_specimen_mrvb_into_success_with_expected_optional_data() {
        val result = MrzParser.parseMRVB(specimenLines, referenceTime = ref2026)
        val mrvB = assertIs<MrvB>(assertIs<ParseResult.Success>(result).document)
        // 8 chars all filler in the specimen.
        assertEquals("<<<<<<<<", mrvB.optionalData)
        assertEquals(8, mrvB.optionalData.length)
    }

    @Test
    fun success_metadata_uses_backend_string_input_read_method() {
        val result = MrzParser.parseMRVB(specimenLines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        assertEquals(ReadMethod.BACKEND_STRING_INPUT, success.metadata.readMethod)
    }

    @Test
    fun success_metadata_has_no_validation_failures_for_clean_specimen() {
        val result = MrzParser.parseMRVB(specimenLines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        assertTrue(success.metadata.validationFailures.isEmpty())
        // UTO ("Utopia") is recognized as ICAO §5 Part G specimen code (category OTHER)
        // — no MrzUnknownCountryCode warnings expected for clean specimen.
        val unknownCountryWarnings = success.metadata.warnings.filterIsInstance<MrzUnknownCountryCode>()
        assertTrue(unknownCountryWarnings.isEmpty(), "Expected no warnings; got $unknownCountryWarnings")
    }

    @Test
    fun raw_lines_round_trip_through_the_parser_unchanged() {
        val result = MrzParser.parseMRVB(specimenLines, referenceTime = ref2026)
        val mrvB = assertIs<MrvB>(assertIs<ParseResult.Success>(result).document)
        assertEquals(specimenLines, mrvB.rawLines)
    }

    // --- Input forms ---

    @Test
    fun string_input_with_lf_line_separator_is_equivalent_to_list_input() {
        val joined = specimenLines.joinToString("\n")
        val fromString = MrzParser.parseMRVB(joined, referenceTime = ref2026)
        val fromList = MrzParser.parseMRVB(specimenLines, referenceTime = ref2026)
        assertEquals(fromList, fromString)
    }

    // --- Validation wiring ---

    @Test
    fun returns_partial_success_with_check_digit_mismatch_when_document_number_check_digit_is_corrupted() {
        val corrupted = line2With(MrvBFormatSpec.documentNumberCheckDigit, "7")
        val result = MrzParser.parseMRVB(listOf(specimenLine1, corrupted), referenceTime = ref2026)
        val partial = assertIs<ParseResult.PartialSuccess>(result)

        val mismatch =
            partial.metadata.validationFailures
                .filterIsInstance<MrzCheckDigitMismatch>()
                .firstOrNull { it.field == MrzField.DOCUMENT_NUMBER }
        assertTrue(mismatch != null)
        assertEquals('4', mismatch.expected)
        assertEquals('7', mismatch.observed)
    }

    @Test
    fun mutating_optional_data_does_not_invalidate_any_check_for_mrvb() {
        // MRV-B has neither a per-field optional-data check digit nor a composite check digit
        // (ICAO Doc 9303 Part 7). Mutating the optional-data slot of an otherwise-clean MRV-B
        // input MUST NOT produce any MrzCheckDigitMismatch.
        val mutated = line2With(MrvBFormatSpec.optionalData, "ABCDEFGH")
        val result = MrzParser.parseMRVB(listOf(specimenLine1, mutated), referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        val mismatches = success.metadata.validationFailures.filterIsInstance<MrzCheckDigitMismatch>()
        assertTrue(mismatches.isEmpty(), "MRV-B optional-data mutation must not produce check-digit mismatches; got $mismatches")
    }

    @Test
    fun returns_partial_success_with_invalid_sex_value_when_sex_character_is_not_in_allowed_set() {
        val line2WithInvalidSex = line2With(MrvBFormatSpec.sex, "Q")
        val result = MrzParser.parseMRVB(listOf(specimenLine1, line2WithInvalidSex), referenceTime = ref2026)
        val partial = assertIs<ParseResult.PartialSuccess>(result)
        val mrvB = assertIs<MrvB>(partial.document)

        assertEquals(Sex.UNSPECIFIED, mrvB.commonFields.sex)
        assertEquals('Q', mrvB.commonFields.rawSex)

        val invalid =
            partial.metadata.validationFailures
                .filterIsInstance<MrzInvalidSexValue>()
                .firstOrNull()
        assertTrue(invalid != null)
        assertEquals('Q', invalid.observed)
        // Sex position on MRV-B: line 1 index 20, global = 36 + 20 = 56
        assertEquals(56, invalid.position)
    }

    // --- Error paths ---

    @Test
    fun fails_with_mrz_invalid_length_when_input_has_one_line() {
        val result = MrzParser.parseMRVB(listOf(specimenLine1), referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val error = assertIs<MrzInvalidLength>(failure.error)

        assertEquals(MrzFormat.MRV_B, error.format)
        assertEquals(2, error.expectedLineCount)
        assertEquals(36, error.expectedLineLength)
        assertEquals(1, error.observedLineCount)
    }

    @Test
    fun fails_with_mrz_invalid_length_when_lines_have_mrva_length_instead_of_mrvb_length() {
        // MRV-B is 2 x 36. Passing a 2 x 44 MRV-A-shape input must fail length validation —
        // the parser does not attempt format detection in the format-specific entry point.
        val result = MrzParser.parseMRVB(Specimens.MRV_A, referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val error = assertIs<MrzInvalidLength>(failure.error)
        assertEquals(MrzFormat.MRV_B, error.format)
        assertEquals(36, error.expectedLineLength)
        assertEquals(listOf(44, 44), error.observedLineLengths)
    }

    @Test
    fun fails_with_mrz_character_set_violation_for_lowercase_in_line_one() {
        val line1WithLowercase = "v" + specimenLine1.drop(1)
        val result = MrzParser.parseMRVB(listOf(line1WithLowercase, specimenLine2), referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val violation = assertIs<MrzCharacterSetViolation>(failure.error)
        assertEquals('v', violation.offendingCharacter)
        assertEquals(0, violation.position)
    }
}
