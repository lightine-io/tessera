package io.lightine.tessera.mrz.validation

import io.lightine.tessera.mrz.model.CommonFields
import io.lightine.tessera.mrz.model.MrzCheckDigits
import io.lightine.tessera.mrz.model.MrzDate
import io.lightine.tessera.mrz.model.TD2
import io.lightine.tessera.mrz.recognition.CountryCode
import io.lightine.tessera.mrz.recognition.DocumentType
import io.lightine.tessera.types.errors.MrzCheckDigitMismatch
import io.lightine.tessera.types.errors.MrzInvalidLength
import io.lightine.tessera.types.errors.MrzInvalidSexValue
import io.lightine.tessera.types.errors.MrzUnknownCountryCode
import io.lightine.tessera.types.errors.MrzValidationError
import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.Sex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MrzValidatorTd2Test {
    private val specimenLine1 = "I<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<"
    private val specimenLine2 = "D231458907UTO6908061F3008063<<<<<<<4"

    private fun specimenCommonFields(
        rawSex: Char = 'F',
        sex: Sex = Sex.FEMALE,
        checkDigits: MrzCheckDigits =
            MrzCheckDigits(
                documentNumber = '7',
                dateOfBirth = '1',
                dateOfExpiry = '3',
                optionalData = null,
                composite = '4',
            ),
    ): CommonFields =
        CommonFields(
            documentType = DocumentType("I"),
            issuingState = CountryCode("UTO"),
            primaryIdentifier = "",
            secondaryIdentifier = "",
            nameTruncated = false,
            rawNameField = "ERIKSSON<<ANNA<MARIA<<<<<<<<<<<",
            documentNumber = "D23145890",
            nationality = CountryCode("UTO"),
            dateOfBirth = MrzDate(rawYear = "69", rawMonth = "08", rawDay = "06"),
            sex = sex,
            rawSex = rawSex,
            dateOfExpiry = MrzDate(rawYear = "30", rawMonth = "08", rawDay = "06"),
            checkDigits = checkDigits,
        )

    private fun specimenTd2(
        line2: String = specimenLine2,
        commonFields: CommonFields = specimenCommonFields(),
    ): TD2 =
        TD2(
            rawLines = listOf(specimenLine1, line2),
            commonFields = commonFields,
            optionalData = "<<<<<<<",
        )

    // --- Happy path ---

    @Test
    fun specimen_passes_all_check_digits_and_sex_validation() {
        val result = MrzValidator.validate(specimenTd2())
        assertTrue(result.validationFailures.isEmpty(), "Expected no failures; got ${result.validationFailures}")
        // Fictional UTO produces two MrzUnknownCountryCode warnings (issuingState + nationality);
        // no other warnings are expected.
        val nonCountryWarnings = result.warnings.filterNot { it is MrzUnknownCountryCode }
        assertTrue(nonCountryWarnings.isEmpty(), "Expected no warnings beyond MrzUnknownCountryCode; got $nonCountryWarnings")
    }

    // --- Per-field check digit failures ---

    @Test
    fun reports_document_number_check_digit_mismatch_at_td2_position() {
        val td2 =
            specimenTd2(
                commonFields =
                    specimenCommonFields(
                        checkDigits =
                            MrzCheckDigits(
                                documentNumber = '0',
                                dateOfBirth = '1',
                                dateOfExpiry = '3',
                                optionalData = null,
                                composite = '4',
                            ),
                    ),
            )
        val result = MrzValidator.validate(td2)
        val mismatch = result.validationFailures.firstOfField(MrzField.DOCUMENT_NUMBER)
        assertEquals('7', mismatch.expected)
        assertEquals('0', mismatch.observed)
        // TD2 doc number check digit position: line 1 index 9, global = 36 + 9 = 45
        assertEquals(45, mismatch.position)
    }

    @Test
    fun reports_date_of_birth_check_digit_mismatch_at_td2_position() {
        val td2 =
            specimenTd2(
                commonFields =
                    specimenCommonFields(
                        checkDigits =
                            MrzCheckDigits(
                                documentNumber = '7',
                                dateOfBirth = '0',
                                dateOfExpiry = '3',
                                optionalData = null,
                                composite = '4',
                            ),
                    ),
            )
        val result = MrzValidator.validate(td2)
        val mismatch = result.validationFailures.firstOfField(MrzField.DATE_OF_BIRTH)
        assertEquals('1', mismatch.expected)
        assertEquals('0', mismatch.observed)
        // TD2 DOB check digit position: line 1 index 19, global = 36 + 19 = 55
        assertEquals(55, mismatch.position)
    }

    @Test
    fun reports_date_of_expiry_check_digit_mismatch_at_td2_position() {
        val td2 =
            specimenTd2(
                commonFields =
                    specimenCommonFields(
                        checkDigits =
                            MrzCheckDigits(
                                documentNumber = '7',
                                dateOfBirth = '1',
                                dateOfExpiry = '0',
                                optionalData = null,
                                composite = '4',
                            ),
                    ),
            )
        val result = MrzValidator.validate(td2)
        val mismatch = result.validationFailures.firstOfField(MrzField.DATE_OF_EXPIRY)
        assertEquals('3', mismatch.expected)
        assertEquals('0', mismatch.observed)
        // TD2 expiry check digit position: line 1 index 27, global = 36 + 27 = 63
        assertEquals(63, mismatch.position)
    }

    @Test
    fun reports_composite_check_digit_mismatch_at_td2_position() {
        val td2 =
            specimenTd2(
                commonFields =
                    specimenCommonFields(
                        checkDigits =
                            MrzCheckDigits(
                                documentNumber = '7',
                                dateOfBirth = '1',
                                dateOfExpiry = '3',
                                optionalData = null,
                                composite = '0',
                            ),
                    ),
            )
        val result = MrzValidator.validate(td2)
        val mismatch = result.validationFailures.firstOfField(MrzField.COMPOSITE)
        assertEquals('4', mismatch.expected)
        assertEquals('0', mismatch.observed)
        // TD2 composite check digit position: line 1 index 35, global = 36 + 35 = 71
        assertEquals(71, mismatch.position)
    }

    @Test
    fun does_not_report_optional_data_check_digit_mismatch_for_td2() {
        // TD2 has no per-field check digit slot on optional data. Even with all per-field
        // check digits set to obviously-wrong values, the validator must never produce an
        // OPTIONAL_DATA mismatch for a TD2 document.
        val td2 =
            specimenTd2(
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
        val result = MrzValidator.validate(td2)
        val optionalDataMismatch =
            result.validationFailures
                .filterIsInstance<MrzCheckDigitMismatch>()
                .firstOrNull { it.field == MrzField.OPTIONAL_DATA }
        assertNull(optionalDataMismatch, "TD2 has no OPTIONAL_DATA check digit; got ${result.validationFailures}")
    }

    @Test
    fun composite_check_digit_input_includes_optional_data_for_td2() {
        // TD2 composite covers DOE + its check digit + optional data (positions 22-35 per ICAO
        // Doc 9303 Part 6). Mutating only the optional-data slot in the raw lines invalidates
        // ONLY the composite digit — no per-field check digit covers optional data in TD2.
        val mutatedLine2 = specimenLine2.substring(0, 28) + "ABCDEFG" + specimenLine2.substring(35)
        val td2 = specimenTd2(line2 = mutatedLine2)
        val result = MrzValidator.validate(td2)

        val compositeMismatch = result.validationFailures.firstOfField(MrzField.COMPOSITE)
        assertEquals('4', compositeMismatch.observed)
        assertTrue(compositeMismatch.expected != '4', "Mutated optional data should change the composite expected digit")

        // No per-field optional-data failure is produced.
        val optionalFailure =
            result.validationFailures
                .filterIsInstance<MrzCheckDigitMismatch>()
                .firstOrNull { it.field == MrzField.OPTIONAL_DATA }
        assertNull(optionalFailure)
    }

    // --- Sex validation ---

    @Test
    fun reports_invalid_sex_character_at_td2_position() {
        val td2 = specimenTd2(commonFields = specimenCommonFields(rawSex = 'Q', sex = Sex.UNSPECIFIED))
        val result = MrzValidator.validate(td2)
        val invalid =
            assertIs<MrzInvalidSexValue>(
                result.validationFailures.first { it is MrzInvalidSexValue },
            )
        assertEquals('Q', invalid.observed)
        // TD2 sex position: line 1 index 20, global = 36 + 20 = 56
        assertEquals(56, invalid.position)
    }

    // --- Raw lines that do not fit the format (TES-203) ---

    @Test
    fun empty_raw_lines_report_MrzInvalidLength_with_TD2_shape_instead_of_throwing() {
        val result = MrzValidator.validate(specimenTd2().copy(rawLines = emptyList()))
        assertEquals(
            MrzInvalidLength(
                format = MrzFormat.TD2,
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
