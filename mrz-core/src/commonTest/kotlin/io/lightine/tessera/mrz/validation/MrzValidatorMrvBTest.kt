package io.lightine.tessera.mrz.validation

import io.lightine.tessera.mrz.model.CommonFields
import io.lightine.tessera.mrz.model.MrvB
import io.lightine.tessera.mrz.model.MrzCheckDigits
import io.lightine.tessera.mrz.model.MrzDate
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

class MrzValidatorMrvBTest {
    private val specimenLine1 = "V<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<"
    private val specimenLine2 = "L898902C<3UTO6908061F3008063<<<<<<<<"

    private fun specimenCommonFields(
        rawSex: Char = 'F',
        sex: Sex = Sex.FEMALE,
        documentType: DocumentType = DocumentType("V"),
        checkDigits: MrzCheckDigits =
            MrzCheckDigits(
                documentNumber = '3',
                dateOfBirth = '1',
                dateOfExpiry = '3',
                optionalData = null,
                composite = null,
            ),
    ): CommonFields =
        CommonFields(
            documentType = documentType,
            issuingState = CountryCode("UTO"),
            primaryIdentifier = "",
            secondaryIdentifier = "",
            nameTruncated = false,
            rawNameField = "ERIKSSON<<ANNA<MARIA<<<<<<<<<<<",
            documentNumber = "L898902C<",
            nationality = CountryCode("UTO"),
            dateOfBirth = MrzDate(rawYear = "69", rawMonth = "08", rawDay = "06"),
            sex = sex,
            rawSex = rawSex,
            dateOfExpiry = MrzDate(rawYear = "30", rawMonth = "08", rawDay = "06"),
            checkDigits = checkDigits,
        )

    private fun specimenMrvB(
        line2: String = specimenLine2,
        commonFields: CommonFields = specimenCommonFields(),
    ): MrvB =
        MrvB(
            rawLines = listOf(specimenLine1, line2),
            commonFields = commonFields,
            optionalData = "<<<<<<<<",
        )

    // --- Happy path ---

    @Test
    fun specimen_passes_all_check_digits_and_sex_validation() {
        val result = MrzValidator.validate(specimenMrvB())
        assertTrue(result.validationFailures.isEmpty(), "Expected no failures; got ${result.validationFailures}")
        assertTrue(
            result.warnings.none { it is MrzUnknownDocumentTypeCode },
            "V is in the starter set; no MrzUnknownDocumentTypeCode warning expected — got ${result.warnings}",
        )
        val nonCountryWarnings = result.warnings.filterNot { it is MrzUnknownCountryCode }
        assertTrue(nonCountryWarnings.isEmpty(), "Expected no warnings beyond MrzUnknownCountryCode; got $nonCountryWarnings")
    }

    // --- Per-field check digit failures ---

    @Test
    fun reports_document_number_check_digit_mismatch_at_mrvb_position() {
        val mrvB =
            specimenMrvB(
                commonFields =
                    specimenCommonFields(
                        checkDigits =
                            MrzCheckDigits(
                                documentNumber = '0',
                                dateOfBirth = '1',
                                dateOfExpiry = '3',
                                optionalData = null,
                                composite = null,
                            ),
                    ),
            )
        val result = MrzValidator.validate(mrvB)
        val mismatch = result.validationFailures.firstOfField(MrzField.DOCUMENT_NUMBER)
        assertEquals('3', mismatch.expected)
        assertEquals('0', mismatch.observed)
        // MRV-B doc number check digit position: line 1 index 9, global = 36 + 9 = 45
        assertEquals(45, mismatch.position)
    }

    @Test
    fun reports_date_of_birth_check_digit_mismatch_at_mrvb_position() {
        val mrvB =
            specimenMrvB(
                commonFields =
                    specimenCommonFields(
                        checkDigits =
                            MrzCheckDigits(
                                documentNumber = '3',
                                dateOfBirth = '0',
                                dateOfExpiry = '3',
                                optionalData = null,
                                composite = null,
                            ),
                    ),
            )
        val result = MrzValidator.validate(mrvB)
        val mismatch = result.validationFailures.firstOfField(MrzField.DATE_OF_BIRTH)
        assertEquals('1', mismatch.expected)
        assertEquals('0', mismatch.observed)
        // MRV-B DOB check digit position: line 1 index 19, global = 36 + 19 = 55
        assertEquals(55, mismatch.position)
    }

    @Test
    fun reports_date_of_expiry_check_digit_mismatch_at_mrvb_position() {
        val mrvB =
            specimenMrvB(
                commonFields =
                    specimenCommonFields(
                        checkDigits =
                            MrzCheckDigits(
                                documentNumber = '3',
                                dateOfBirth = '1',
                                dateOfExpiry = '0',
                                optionalData = null,
                                composite = null,
                            ),
                    ),
            )
        val result = MrzValidator.validate(mrvB)
        val mismatch = result.validationFailures.firstOfField(MrzField.DATE_OF_EXPIRY)
        assertEquals('3', mismatch.expected)
        assertEquals('0', mismatch.observed)
        // MRV-B expiry check digit position: line 1 index 27, global = 36 + 27 = 63
        assertEquals(63, mismatch.position)
    }

    @Test
    fun does_not_report_optional_data_or_composite_check_digit_mismatch_for_mrvb() {
        // MRV-B has no per-field check digit on optional data and no composite check digit
        // (ICAO Doc 9303 Part 7). The validator must never produce OPTIONAL_DATA or COMPOSITE
        // mismatches for an MRV-B document, even if every other digit is wrong.
        val mrvB =
            specimenMrvB(
                commonFields =
                    specimenCommonFields(
                        checkDigits =
                            MrzCheckDigits(
                                documentNumber = '0',
                                dateOfBirth = '0',
                                dateOfExpiry = '0',
                                optionalData = null,
                                composite = null,
                            ),
                    ),
            )
        val result = MrzValidator.validate(mrvB)
        val optionalDataMismatch =
            result.validationFailures
                .filterIsInstance<MrzCheckDigitMismatch>()
                .firstOrNull { it.field == MrzField.OPTIONAL_DATA }
        assertNull(optionalDataMismatch, "MRV-B has no OPTIONAL_DATA check digit")
        val compositeMismatch =
            result.validationFailures
                .filterIsInstance<MrzCheckDigitMismatch>()
                .firstOrNull { it.field == MrzField.COMPOSITE }
        assertNull(compositeMismatch, "MRV-B has no COMPOSITE check digit")
    }

    // --- Sex validation ---

    @Test
    fun reports_invalid_sex_character_at_mrvb_position() {
        val mrvB = specimenMrvB(commonFields = specimenCommonFields(rawSex = 'Q', sex = Sex.UNSPECIFIED))
        val result = MrzValidator.validate(mrvB)
        val invalid =
            assertIs<MrzInvalidSexValue>(
                result.validationFailures.first { it is MrzInvalidSexValue },
            )
        assertEquals('Q', invalid.observed)
        // MRV-B sex position: line 1 index 20, global = 36 + 20 = 56
        assertEquals(56, invalid.position)
    }

    // --- Recognition warnings ---

    @Test
    fun emits_unknown_document_type_warning_when_document_type_is_not_recognized() {
        val mrvB = specimenMrvB(commonFields = specimenCommonFields(documentType = DocumentType("ZZ")))
        val result = MrzValidator.validate(mrvB)
        val warning = result.warnings.filterIsInstance<MrzUnknownDocumentTypeCode>().firstOrNull()
        assertTrue(warning != null, "Expected MrzUnknownDocumentTypeCode; got ${result.warnings}")
        assertEquals("ZZ", warning.rawCode)
        assertEquals(0, warning.position)
    }

    // --- Raw lines that do not fit the format (TES-203) ---

    @Test
    fun empty_raw_lines_report_MrzInvalidLength_with_MRV_B_shape_instead_of_throwing() {
        val result = MrzValidator.validate(specimenMrvB().copy(rawLines = emptyList()))
        assertEquals(
            MrzInvalidLength(
                format = MrzFormat.MRV_B,
                expectedLineCount = 2,
                expectedLineLength = 36,
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
