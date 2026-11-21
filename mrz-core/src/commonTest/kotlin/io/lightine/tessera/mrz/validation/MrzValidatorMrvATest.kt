package io.lightine.tessera.mrz.validation

import io.lightine.tessera.mrz.Specimens
import io.lightine.tessera.mrz.model.CommonFields
import io.lightine.tessera.mrz.model.MrvA
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

class MrzValidatorMrvATest {
    // The project's MRV-A specimen.
    private val specimenLine1 = Specimens.MRV_A[0]
    private val specimenLine2 = Specimens.MRV_A[1]

    private fun specimenCommonFields(
        rawSex: Char = 'F',
        sex: Sex = Sex.FEMALE,
        documentType: DocumentType = DocumentType("V"),
        checkDigits: MrzCheckDigits =
            MrzCheckDigits(
                documentNumber = '4',
                dateOfBirth = '2',
                dateOfExpiry = '1',
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
            rawNameField = "SPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<",
            documentNumber = "T82000153",
            nationality = CountryCode("UTO"),
            dateOfBirth = MrzDate(rawYear = "90", rawMonth = "03", rawDay = "15"),
            sex = sex,
            rawSex = rawSex,
            dateOfExpiry = MrzDate(rawYear = "34", rawMonth = "03", rawDay = "14"),
            checkDigits = checkDigits,
        )

    private fun specimenMrvA(
        line2: String = specimenLine2,
        commonFields: CommonFields = specimenCommonFields(),
    ): MrvA =
        MrvA(
            rawLines = listOf(specimenLine1, line2),
            commonFields = commonFields,
            optionalData = "<<<<<<<<<<<<<<<<",
        )

    // --- Happy path ---

    @Test
    fun specimen_passes_all_check_digits_and_sex_validation() {
        val result = MrzValidator.validate(specimenMrvA())
        assertTrue(result.validationFailures.isEmpty(), "Expected no failures; got ${result.validationFailures}")
        // "V" IS recognized in the SDK's starter set; only the UTO country warnings should fire.
        assertTrue(
            result.warnings.none { it is MrzUnknownDocumentTypeCode },
            "V is in the starter set; no MrzUnknownDocumentTypeCode warning expected — got ${result.warnings}",
        )
        val nonCountryWarnings = result.warnings.filterNot { it is MrzUnknownCountryCode }
        assertTrue(nonCountryWarnings.isEmpty(), "Expected no warnings beyond MrzUnknownCountryCode; got $nonCountryWarnings")
    }

    // --- Per-field check digit failures ---

    @Test
    fun reports_document_number_check_digit_mismatch_at_mrva_position() {
        val mrvA =
            specimenMrvA(
                commonFields =
                    specimenCommonFields(
                        checkDigits =
                            MrzCheckDigits(
                                documentNumber = '0',
                                dateOfBirth = '2',
                                dateOfExpiry = '1',
                                optionalData = null,
                                composite = null,
                            ),
                    ),
            )
        val result = MrzValidator.validate(mrvA)
        val mismatch = result.validationFailures.firstOfField(MrzField.DOCUMENT_NUMBER)
        assertEquals('4', mismatch.expected)
        assertEquals('0', mismatch.observed)
        // MRV-A doc number check digit position: line 1 index 9, global = 44 + 9 = 53
        assertEquals(53, mismatch.position)
    }

    @Test
    fun reports_date_of_birth_check_digit_mismatch_at_mrva_position() {
        val mrvA =
            specimenMrvA(
                commonFields =
                    specimenCommonFields(
                        checkDigits =
                            MrzCheckDigits(
                                documentNumber = '4',
                                dateOfBirth = '0',
                                dateOfExpiry = '1',
                                optionalData = null,
                                composite = null,
                            ),
                    ),
            )
        val result = MrzValidator.validate(mrvA)
        val mismatch = result.validationFailures.firstOfField(MrzField.DATE_OF_BIRTH)
        assertEquals('2', mismatch.expected)
        assertEquals('0', mismatch.observed)
        // MRV-A DOB check digit position: line 1 index 19, global = 44 + 19 = 63
        assertEquals(63, mismatch.position)
    }

    @Test
    fun reports_date_of_expiry_check_digit_mismatch_at_mrva_position() {
        val mrvA =
            specimenMrvA(
                commonFields =
                    specimenCommonFields(
                        checkDigits =
                            MrzCheckDigits(
                                documentNumber = '4',
                                dateOfBirth = '2',
                                dateOfExpiry = '0',
                                optionalData = null,
                                composite = null,
                            ),
                    ),
            )
        val result = MrzValidator.validate(mrvA)
        val mismatch = result.validationFailures.firstOfField(MrzField.DATE_OF_EXPIRY)
        assertEquals('1', mismatch.expected)
        assertEquals('0', mismatch.observed)
        // MRV-A expiry check digit position: line 1 index 27, global = 44 + 27 = 71
        assertEquals(71, mismatch.position)
    }

    @Test
    fun does_not_report_optional_data_or_composite_check_digit_mismatch_for_mrva() {
        // MRV-A has no per-field check digit on optional data and no composite check digit
        // (ICAO Doc 9303 Part 7). The validator must never produce OPTIONAL_DATA or COMPOSITE
        // mismatches for an MRV-A document, even if every other digit is wrong.
        val mrvA =
            specimenMrvA(
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
        val result = MrzValidator.validate(mrvA)
        val optionalDataMismatch =
            result.validationFailures
                .filterIsInstance<MrzCheckDigitMismatch>()
                .firstOrNull { it.field == MrzField.OPTIONAL_DATA }
        assertNull(optionalDataMismatch, "MRV-A has no OPTIONAL_DATA check digit")
        val compositeMismatch =
            result.validationFailures
                .filterIsInstance<MrzCheckDigitMismatch>()
                .firstOrNull { it.field == MrzField.COMPOSITE }
        assertNull(compositeMismatch, "MRV-A has no COMPOSITE check digit")
    }

    // --- Sex validation ---

    @Test
    fun reports_invalid_sex_character_at_mrva_position() {
        val mrvA = specimenMrvA(commonFields = specimenCommonFields(rawSex = 'Q', sex = Sex.UNSPECIFIED))
        val result = MrzValidator.validate(mrvA)
        val invalid =
            assertIs<MrzInvalidSexValue>(
                result.validationFailures.first { it is MrzInvalidSexValue },
            )
        assertEquals('Q', invalid.observed)
        // MRV-A sex position: line 1 index 20, global = 44 + 20 = 64
        assertEquals(64, invalid.position)
    }

    // --- Recognition warnings ---

    @Test
    fun emits_unknown_document_type_warning_when_document_type_is_not_recognized() {
        // Use "ZZ" — not in the SDK's starter set.
        val mrvA = specimenMrvA(commonFields = specimenCommonFields(documentType = DocumentType("ZZ")))
        val result = MrzValidator.validate(mrvA)
        val warning = result.warnings.filterIsInstance<MrzUnknownDocumentTypeCode>().firstOrNull()
        assertTrue(warning != null, "Expected MrzUnknownDocumentTypeCode; got ${result.warnings}")
        assertEquals("ZZ", warning.rawCode)
        // Document type position on MRV-A: line 0 index 0, global = 0
        assertEquals(0, warning.position)
    }

    // --- Raw lines that do not fit the format (TES-203) ---

    @Test
    fun empty_raw_lines_report_MrzInvalidLength_with_MRV_A_shape_instead_of_throwing() {
        val result = MrzValidator.validate(specimenMrvA().copy(rawLines = emptyList()))
        assertEquals(
            MrzInvalidLength(
                format = MrzFormat.MRV_A,
                expectedLineCount = 2,
                expectedLineLength = 44,
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
