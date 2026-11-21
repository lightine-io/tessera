package io.lightine.tessera.mrz.model

import io.lightine.tessera.mrz.recognition.CountryCode
import io.lightine.tessera.mrz.recognition.DocumentType
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.Sex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TD1Test {
    // The project's TD1 specimen.
    private val syntheticCommonFields =
        CommonFields(
            documentType = DocumentType("I"),
            issuingState = CountryCode("UTO"),
            primaryIdentifier = "SPECIMEN",
            secondaryIdentifier = "ALEX MORGAN",
            nameTruncated = false,
            rawNameField = "SPECIMEN<<ALEX<MORGAN<<<<<<<<<",
            documentNumber = "T42000137",
            nationality = CountryCode("UTO"),
            dateOfBirth = MrzDate(rawYear = "90", rawMonth = "03", rawDay = "15"),
            sex = Sex.FEMALE,
            rawSex = 'F',
            dateOfExpiry = MrzDate(rawYear = "34", rawMonth = "03", rawDay = "14"),
            checkDigits =
                MrzCheckDigits(
                    documentNumber = '0',
                    dateOfBirth = '2',
                    dateOfExpiry = '1',
                    optionalData = null,
                    composite = '2',
                ),
        )

    private val syntheticTd1 =
        TD1(
            rawLines =
                listOf(
                    "I<UTOT420001370<<<<<<<<<<<<<<<",
                    "9003152F3403141UTO<<<<<<<<<<<2",
                    "SPECIMEN<<ALEX<MORGAN<<<<<<<<<",
                ),
            commonFields = syntheticCommonFields,
            optionalData1 = "<<<<<<<<<<<<<<<",
            optionalData2 = "<<<<<<<<<<<",
        )

    @Test
    fun td1_synthetic_constructs_and_exposes_common_fields_verbatim() {
        assertEquals("I", syntheticTd1.commonFields.documentType.rawCode)
        assertEquals("UTO", syntheticTd1.commonFields.issuingState.rawCode)
        assertEquals("SPECIMEN", syntheticTd1.commonFields.primaryIdentifier)
        assertEquals("ALEX MORGAN", syntheticTd1.commonFields.secondaryIdentifier)
        assertEquals("T42000137", syntheticTd1.commonFields.documentNumber)
        assertEquals(Sex.FEMALE, syntheticTd1.commonFields.sex)
    }

    @Test
    fun td1_exposes_both_optional_data_fields() {
        assertEquals("<<<<<<<<<<<<<<<", syntheticTd1.optionalData1)
        assertEquals("<<<<<<<<<<<", syntheticTd1.optionalData2)
    }

    @Test
    fun td1_check_digits_have_no_optional_data_position() {
        // TD1 does not define a separate optional-data check digit position;
        // MrzCheckDigits.optionalData is null for this format.
        assertNull(syntheticTd1.commonFields.checkDigits.optionalData)
    }

    @Test
    fun td1_format_is_always_td1() {
        assertEquals(MrzFormat.TD1, syntheticTd1.format)
    }

    @Test
    fun td1_raw_lines_round_trip_unchanged() {
        assertEquals(
            listOf(
                "I<UTOT420001370<<<<<<<<<<<<<<<",
                "9003152F3403141UTO<<<<<<<<<<<2",
                "SPECIMEN<<ALEX<MORGAN<<<<<<<<<",
            ),
            syntheticTd1.rawLines,
        )
    }

    @Test
    fun td1_raw_lines_are_three_lines_of_thirty_characters() {
        assertEquals(3, syntheticTd1.rawLines.size)
        syntheticTd1.rawLines.forEach { line ->
            assertEquals(30, line.length, "Each TD1 line should be 30 characters; got '$line'")
        }
    }

    @Test
    fun two_td1_with_identical_field_values_are_equal() {
        val other =
            TD1(
                rawLines = syntheticTd1.rawLines,
                commonFields = syntheticCommonFields,
                optionalData1 = "<<<<<<<<<<<<<<<",
                optionalData2 = "<<<<<<<<<<<",
            )
        assertEquals(syntheticTd1, other)
    }
}
