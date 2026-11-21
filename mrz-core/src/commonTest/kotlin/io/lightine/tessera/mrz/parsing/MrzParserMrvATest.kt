package io.lightine.tessera.mrz.parsing

import io.lightine.tessera.mrz.Specimens
import io.lightine.tessera.mrz.formats.FieldSpec
import io.lightine.tessera.mrz.formats.MrvAFormatSpec
import io.lightine.tessera.mrz.model.MrvA
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

class MrzParserMrvATest {
    private val ref2026 = Instant.parse("2026-05-04T12:00:00Z")

    // The project's MRV-A specimen. ICAO Doc 9303 Part 7: 2 lines x 44 characters, document
    // type `V` (visa), issuing state UTO (fictional). Optional data is 16 chars of filler.
    // MRV-A has no composite check digit per Part 7.
    private val specimenLine1 = Specimens.MRV_A[0]
    private val specimenLine2 = Specimens.MRV_A[1]
    private val specimenLines = listOf(specimenLine1, specimenLine2)

    private fun line2With(
        field: FieldSpec,
        text: String,
    ): String = Specimens.MRV_A.overwrite(field, text)[1]

    // --- Happy path ---

    @Test
    fun parses_specimen_mrva_into_success_with_expected_common_fields() {
        val result = MrzParser.parseMRVA(specimenLines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        val mrvA = assertIs<MrvA>(success.document)

        assertEquals(MrzFormat.MRV_A, mrvA.format)
        assertEquals("V", mrvA.commonFields.documentType.rawCode)
        assertTrue(mrvA.commonFields.documentType.isRecognized)
        assertEquals("UTO", mrvA.commonFields.issuingState.rawCode)
        assertEquals("T82000153", mrvA.commonFields.documentNumber)
        assertEquals("UTO", mrvA.commonFields.nationality.rawCode)
        assertEquals(Sex.FEMALE, mrvA.commonFields.sex)
        assertEquals("SPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<", mrvA.commonFields.rawNameField)
        assertEquals("SPECIMEN", mrvA.commonFields.primaryIdentifier)
        assertEquals("ALEX MORGAN", mrvA.commonFields.secondaryIdentifier)
        assertEquals(false, mrvA.commonFields.nameTruncated)
    }

    @Test
    fun parses_specimen_mrva_into_success_with_expected_check_digits() {
        val result = MrzParser.parseMRVA(specimenLines, referenceTime = ref2026)
        val mrvA = assertIs<MrvA>(assertIs<ParseResult.Success>(result).document)
        val checks = mrvA.commonFields.checkDigits

        assertEquals('4', checks.documentNumber)
        assertEquals('2', checks.dateOfBirth)
        assertEquals('1', checks.dateOfExpiry)
        assertNull(checks.optionalData, "MRV-A has no per-field check digit on optional data")
        assertNull(checks.composite, "MRV-A has no composite check digit per ICAO Doc 9303 Part 7")
    }

    @Test
    fun parses_specimen_mrva_into_success_with_expected_optional_data() {
        val result = MrzParser.parseMRVA(specimenLines, referenceTime = ref2026)
        val mrvA = assertIs<MrvA>(assertIs<ParseResult.Success>(result).document)
        // 16 chars all filler in the specimen.
        assertEquals("<<<<<<<<<<<<<<<<", mrvA.optionalData)
        assertEquals(16, mrvA.optionalData.length)
    }

    @Test
    fun success_metadata_uses_backend_string_input_read_method() {
        val result = MrzParser.parseMRVA(specimenLines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        assertEquals(ReadMethod.BACKEND_STRING_INPUT, success.metadata.readMethod)
    }

    @Test
    fun success_metadata_has_no_validation_failures_for_clean_specimen() {
        // "V" IS in the starter set so no MrzUnknownDocumentTypeCode warning.
        val result = MrzParser.parseMRVA(specimenLines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        assertTrue(success.metadata.validationFailures.isEmpty())
        // UTO ("Utopia") is recognized as ICAO §5 Part G specimen code (category OTHER)
        // — no MrzUnknownCountryCode warnings expected for clean specimen.
        val unknownCountryWarnings = success.metadata.warnings.filterIsInstance<MrzUnknownCountryCode>()
        assertTrue(unknownCountryWarnings.isEmpty(), "Expected no warnings; got $unknownCountryWarnings")
    }

    @Test
    fun raw_lines_round_trip_through_the_parser_unchanged() {
        val result = MrzParser.parseMRVA(specimenLines, referenceTime = ref2026)
        val mrvA = assertIs<MrvA>(assertIs<ParseResult.Success>(result).document)
        assertEquals(specimenLines, mrvA.rawLines)
    }

    // --- Input forms ---

    @Test
    fun string_input_with_lf_line_separator_is_equivalent_to_list_input() {
        val joined = specimenLines.joinToString("\n")
        val fromString = MrzParser.parseMRVA(joined, referenceTime = ref2026)
        val fromList = MrzParser.parseMRVA(specimenLines, referenceTime = ref2026)
        assertEquals(fromList, fromString)
    }

    // --- Validation wiring ---

    @Test
    fun returns_partial_success_with_check_digit_mismatch_when_document_number_check_digit_is_corrupted() {
        val corrupted = line2With(MrvAFormatSpec.documentNumberCheckDigit, "7")
        val result = MrzParser.parseMRVA(listOf(specimenLine1, corrupted), referenceTime = ref2026)
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
    fun mutating_optional_data_does_not_invalidate_any_check_for_mrva() {
        // MRV-A has neither a per-field optional-data check digit nor a composite check digit
        // (ICAO Doc 9303 Part 7). Mutating the optional-data slot of an otherwise-clean MRV-A
        // input MUST NOT produce any MrzCheckDigitMismatch — the visa format simply has no
        // checks covering that slot.
        val mutated = line2With(MrvAFormatSpec.optionalData, "ABCDEFGHIJKLMNOP")
        val result = MrzParser.parseMRVA(listOf(specimenLine1, mutated), referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        val mismatches = success.metadata.validationFailures.filterIsInstance<MrzCheckDigitMismatch>()
        assertTrue(mismatches.isEmpty(), "MRV-A optional-data mutation must not produce check-digit mismatches; got $mismatches")
    }

    @Test
    fun returns_partial_success_with_invalid_sex_value_when_sex_character_is_not_in_allowed_set() {
        val line2WithInvalidSex = line2With(MrvAFormatSpec.sex, "Q")
        val result = MrzParser.parseMRVA(listOf(specimenLine1, line2WithInvalidSex), referenceTime = ref2026)
        val partial = assertIs<ParseResult.PartialSuccess>(result)
        val mrvA = assertIs<MrvA>(partial.document)

        assertEquals(Sex.UNSPECIFIED, mrvA.commonFields.sex)
        assertEquals('Q', mrvA.commonFields.rawSex)

        val invalid =
            partial.metadata.validationFailures
                .filterIsInstance<MrzInvalidSexValue>()
                .firstOrNull()
        assertTrue(invalid != null)
        assertEquals('Q', invalid.observed)
        // Sex position on MRV-A: line 1 index 20, global = 44 + 20 = 64
        assertEquals(64, invalid.position)
    }

    // --- Error paths ---

    @Test
    fun fails_with_mrz_invalid_length_when_input_has_one_line() {
        val result = MrzParser.parseMRVA(listOf(specimenLine1), referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val error = assertIs<MrzInvalidLength>(failure.error)

        assertEquals(MrzFormat.MRV_A, error.format)
        assertEquals(2, error.expectedLineCount)
        assertEquals(44, error.expectedLineLength)
        assertEquals(1, error.observedLineCount)
    }

    @Test
    fun fails_with_mrz_invalid_length_when_lines_have_td2_length_instead_of_mrva_length() {
        // MRV-A is 2 x 44. Passing a 2 x 36 TD2-shape input must fail length validation.
        val result = MrzParser.parseMRVA(Specimens.TD2, referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val error = assertIs<MrzInvalidLength>(failure.error)
        assertEquals(MrzFormat.MRV_A, error.format)
        assertEquals(44, error.expectedLineLength)
        assertEquals(listOf(36, 36), error.observedLineLengths)
    }

    @Test
    fun fails_with_mrz_character_set_violation_for_lowercase_in_line_one() {
        val line1WithLowercase = "v" + specimenLine1.drop(1)
        val result = MrzParser.parseMRVA(listOf(line1WithLowercase, specimenLine2), referenceTime = ref2026)
        val failure = assertIs<ParseResult.Failure>(result)
        val violation = assertIs<MrzCharacterSetViolation>(failure.error)
        assertEquals('v', violation.offendingCharacter)
        assertEquals(0, violation.position)
    }
}
