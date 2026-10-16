package io.lightine.tessera.mrz.validation

import io.lightine.tessera.mrz.model.CommonFields
import io.lightine.tessera.mrz.model.MrzCheckDigits
import io.lightine.tessera.mrz.model.MrzDate
import io.lightine.tessera.mrz.model.TD1
import io.lightine.tessera.mrz.recognition.CountryCode
import io.lightine.tessera.mrz.recognition.DocumentType
import io.lightine.tessera.types.errors.MrzCheckDigitMismatch
import io.lightine.tessera.types.errors.MrzInvalidLength
import io.lightine.tessera.types.errors.MrzInvalidSexValue
import io.lightine.tessera.types.errors.MrzUnknownCountryCode
import io.lightine.tessera.types.errors.MrzUnknownDocumentTypeCode
import io.lightine.tessera.types.errors.MrzValidationError
import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.Sex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MrzValidatorTd1Test {
    private val specimenLine1 = "I<UTOL898902C<3<<<<<<<<<<<<<<<"
    private val specimenLine2 = "6908061F3008063UTO<<<<<<<<<<<2"
    private val specimenLine3 = "ERIKSSON<<ANNA<MARIA<<<<<<<<<<"

    private fun specimenCommonFields(
        rawSex: Char = 'F',
        sex: Sex = Sex.FEMALE,
        documentType: DocumentType = DocumentType("I"),
        checkDigits: MrzCheckDigits =
            MrzCheckDigits(
                documentNumber = '3',
                dateOfBirth = '1',
                dateOfExpiry = '3',
                optionalData = null,
                composite = '2',
            ),
    ): CommonFields =
        CommonFields(
            documentType = documentType,
            issuingState = CountryCode("UTO"),
            primaryIdentifier = "",
            secondaryIdentifier = "",
            nameTruncated = false,
            rawNameField = "ERIKSSON<<ANNA<MARIA<<<<<<<<<<",
            documentNumber = "L898902C<",
            nationality = CountryCode("UTO"),
            dateOfBirth = MrzDate(rawYear = "69", rawMonth = "08", rawDay = "06"),
            sex = sex,
            rawSex = rawSex,
            dateOfExpiry = MrzDate(rawYear = "30", rawMonth = "08", rawDay = "06"),
            checkDigits = checkDigits,
        )

    private fun specimenTd1(
        rawLines: List<String> = listOf(specimenLine1, specimenLine2, specimenLine3),
        commonFields: CommonFields = specimenCommonFields(),
    ): TD1 =
        TD1(
            rawLines = rawLines,
            commonFields = commonFields,
            optionalData1 = "<<<<<<<<<<<<<<<",
            optionalData2 = "<<<<<<<<<<<",
        )

    // --- Happy path ---

    @Test
    fun specimen_passes_all_check_digits_and_sex_validation() {
        val result = MrzValidator.validate(specimenTd1())
        assertTrue(result.validationFailures.isEmpty(), "Expected no failures; got ${result.validationFailures}")
        // "I" is in the starter set; only UTO country warnings expected.
        assertTrue(
            result.warnings.none { it is MrzUnknownDocumentTypeCode },
            "I is in the starter set; no MrzUnknownDocumentTypeCode warning expected — got ${result.warnings}",
        )
        val nonCountryWarnings = result.warnings.filterNot { it is MrzUnknownCountryCode }
        assertTrue(nonCountryWarnings.isEmpty(), "Expected no warnings beyond MrzUnknownCountryCode; got $nonCountryWarnings")
    }

    // --- Per-field check digit failures ---

    @Test
    fun reports_document_number_check_digit_mismatch_at_td1_position() {
        val td1 =
            specimenTd1(
                commonFields =
                    specimenCommonFields(
                        checkDigits =
                            MrzCheckDigits(
                                documentNumber = '0',
                                dateOfBirth = '1',
                                dateOfExpiry = '3',
                                optionalData = null,
                                composite = '2',
                            ),
                    ),
            )
        val result = MrzValidator.validate(td1)
        val mismatch = result.validationFailures.firstOfField(MrzField.DOCUMENT_NUMBER)
        assertEquals('3', mismatch.expected)
        assertEquals('0', mismatch.observed)
        // TD1 doc number check digit position: line 1 index 14, global = 14
        assertEquals(14, mismatch.position)
    }

    @Test
    fun reports_date_of_birth_check_digit_mismatch_at_td1_position() {
        val td1 =
            specimenTd1(
                commonFields =
                    specimenCommonFields(
                        checkDigits =
                            MrzCheckDigits(
                                documentNumber = '3',
                                dateOfBirth = '0',
                                dateOfExpiry = '3',
                                optionalData = null,
                                composite = '2',
                            ),
                    ),
            )
        val result = MrzValidator.validate(td1)
        val mismatch = result.validationFailures.firstOfField(MrzField.DATE_OF_BIRTH)
        assertEquals('1', mismatch.expected)
        assertEquals('0', mismatch.observed)
        // TD1 DOB check digit position: line 2 index 6, global = 30 + 6 = 36
        assertEquals(36, mismatch.position)
    }

    @Test
    fun reports_date_of_expiry_check_digit_mismatch_at_td1_position() {
        val td1 =
            specimenTd1(
                commonFields =
                    specimenCommonFields(
                        checkDigits =
                            MrzCheckDigits(
                                documentNumber = '3',
                                dateOfBirth = '1',
                                dateOfExpiry = '0',
                                optionalData = null,
                                composite = '2',
                            ),
                    ),
            )
        val result = MrzValidator.validate(td1)
        val mismatch = result.validationFailures.firstOfField(MrzField.DATE_OF_EXPIRY)
        assertEquals('3', mismatch.expected)
        assertEquals('0', mismatch.observed)
        // TD1 expiry check digit position: line 2 index 14, global = 30 + 14 = 44
        assertEquals(44, mismatch.position)
    }

    @Test
    fun reports_composite_check_digit_mismatch_at_td1_position() {
        val td1 =
            specimenTd1(
                commonFields =
                    specimenCommonFields(
                        checkDigits =
                            MrzCheckDigits(
                                documentNumber = '3',
                                dateOfBirth = '1',
                                dateOfExpiry = '3',
                                optionalData = null,
                                composite = '0',
                            ),
                    ),
            )
        val result = MrzValidator.validate(td1)
        val mismatch = result.validationFailures.firstOfField(MrzField.COMPOSITE)
        assertEquals('2', mismatch.expected)
        assertEquals('0', mismatch.observed)
        // TD1 composite check digit position: line 2 index 29, global = 30 + 29 = 59
        assertEquals(59, mismatch.position)
    }

    @Test
    fun does_not_report_optional_data_check_digit_mismatch_for_td1() {
        // TD1 has no per-field check digit on either optional-data slot. Even with all other
        // per-field digits set to obvious wrong values, the validator must never produce an
        // OPTIONAL_DATA mismatch for a TD1 document.
        val td1 =
            specimenTd1(
                commonFields =
                    specimenCommonFields(
                        checkDigits =
                            MrzCheckDigits(
                                documentNumber = '0',
                                dateOfBirth = '0',
                                dateOfExpiry = '0',
                                optionalData = null,
                                composite = '0',
                            ),
                    ),
            )
        val result = MrzValidator.validate(td1)
        val optionalDataMismatch =
            result.validationFailures
                .filterIsInstance<MrzCheckDigitMismatch>()
                .firstOrNull { it.field == MrzField.OPTIONAL_DATA }
        assertNull(optionalDataMismatch, "TD1 has no OPTIONAL_DATA check digit")
    }

    @Test
    fun composite_check_digit_input_includes_both_optional_data_slots_for_td1() {
        // TD1 composite covers line 1 [5,30) and line 2 [0,7), [8,15), [18,29) per ICAO Doc 9303
        // Part 5. Mutating optional data 2 in raw line 2 invalidates ONLY the composite digit;
        // no per-field optional-data digit exists for TD1.
        val mutatedLine2 = specimenLine2.substring(0, 18) + "ABCDEFGHIJK" + specimenLine2.substring(29)
        val td1 = specimenTd1(rawLines = listOf(specimenLine1, mutatedLine2, specimenLine3))
        val result = MrzValidator.validate(td1)

        val compositeMismatch = result.validationFailures.firstOfField(MrzField.COMPOSITE)
        assertEquals('2', compositeMismatch.observed)
        assertTrue(compositeMismatch.expected != '2', "Mutated optional data 2 should change the composite expected digit")

        val optionalFailure =
            result.validationFailures
                .filterIsInstance<MrzCheckDigitMismatch>()
                .firstOrNull { it.field == MrzField.OPTIONAL_DATA }
        assertNull(optionalFailure)
    }

    // --- Sex validation ---

    @Test
    fun reports_invalid_sex_character_at_td1_position() {
        val td1 = specimenTd1(commonFields = specimenCommonFields(rawSex = 'Q', sex = Sex.UNSPECIFIED))
        val result = MrzValidator.validate(td1)
        val invalid =
            assertIs<MrzInvalidSexValue>(
                result.validationFailures.first { it is MrzInvalidSexValue },
            )
        assertEquals('Q', invalid.observed)
        // TD1 sex position: line 2 index 7, global = 30 + 7 = 37
        assertEquals(37, invalid.position)
    }

    // --- Name truncation warning (warning carries TD1 name-field position: 60) ---

    @Test
    fun emits_name_truncated_warning_at_td1_position_when_name_field_is_truncated() {
        val td1 =
            specimenTd1(
                commonFields = specimenCommonFields().copy(nameTruncated = true),
            )
        val result = MrzValidator.validate(td1)
        val warning =
            result.warnings.firstOrNull { it is io.lightine.tessera.types.errors.MrzNameTruncated }
        assertTrue(warning != null, "Expected MrzNameTruncated; got ${result.warnings}")
        val truncated = assertIs<io.lightine.tessera.types.errors.MrzNameTruncated>(warning)
        // TD1 name field starts on line 3 index 0, global = 2 * 30 + 0 = 60
        assertEquals(60, truncated.position)
    }

    // --- Raw lines that do not fit the format (TES-203) ---

    @Test
    fun empty_raw_lines_report_MrzInvalidLength_with_TD1_shape_instead_of_throwing() {
        val result = MrzValidator.validate(specimenTd1(rawLines = emptyList()))
        assertEquals(
            MrzInvalidLength(
                format = MrzFormat.TD1,
                expectedLineCount = 3,
                expectedLineLength = 30,
                observedLineCount = 0,
                observedLineLengths = emptyList(),
            ),
            result.structuralFailure,
        )
        assertTrue(result.validationFailures.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    private fun List<MrzValidationError>.firstOfField(field: MrzField): MrzCheckDigitMismatch =
        assertIs<MrzCheckDigitMismatch>(
            first { it is MrzCheckDigitMismatch && it.field == field },
        )
}
