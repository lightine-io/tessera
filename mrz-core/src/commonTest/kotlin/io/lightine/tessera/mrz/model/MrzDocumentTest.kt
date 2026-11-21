package io.lightine.tessera.mrz.model

import io.lightine.tessera.mrz.recognition.CountryCode
import io.lightine.tessera.mrz.recognition.DocumentType
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.Sex
import kotlin.test.Test
import kotlin.test.assertEquals

class MrzDocumentTest {
    private val specimenCommonFields =
        CommonFields(
            documentType = DocumentType("P"),
            issuingState = CountryCode("UTO"),
            primaryIdentifier = "SPECIMEN",
            secondaryIdentifier = "ALEX MORGAN",
            nameTruncated = false,
            rawNameField = "SPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<",
            documentNumber = "T22000129",
            nationality = CountryCode("UTO"),
            dateOfBirth = MrzDate(rawYear = "90", rawMonth = "03", rawDay = "15"),
            sex = Sex.FEMALE,
            rawSex = 'F',
            dateOfExpiry = MrzDate(rawYear = "34", rawMonth = "03", rawDay = "14"),
            checkDigits =
                MrzCheckDigits(
                    documentNumber = '3',
                    dateOfBirth = '2',
                    dateOfExpiry = '1',
                    optionalData = '2',
                    composite = '0',
                ),
        )

    private val specimenTd3 =
        TD3(
            rawLines =
                listOf(
                    "P<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<",
                    "T220001293UTO9003152F3403141S98765432<<<<<20",
                ),
            commonFields = specimenCommonFields,
            personalNumber = "S98765432<<<<<",
            personalNumberCheckDigit = '2',
        )

    @Test
    fun td3_specimen_constructs_and_exposes_common_fields_verbatim() {
        assertEquals("P", specimenTd3.commonFields.documentType.rawCode)
        assertEquals("UTO", specimenTd3.commonFields.issuingState.rawCode)
        assertEquals("SPECIMEN", specimenTd3.commonFields.primaryIdentifier)
        assertEquals("ALEX MORGAN", specimenTd3.commonFields.secondaryIdentifier)
        assertEquals(false, specimenTd3.commonFields.nameTruncated)
        assertEquals("T22000129", specimenTd3.commonFields.documentNumber)
        assertEquals("UTO", specimenTd3.commonFields.nationality.rawCode)
        assertEquals(Sex.FEMALE, specimenTd3.commonFields.sex)
    }

    @Test
    fun td3_specimen_exposes_dates_as_raw_year_month_day() {
        val dob = specimenTd3.commonFields.dateOfBirth
        assertEquals("90", dob.rawYear)
        assertEquals("03", dob.rawMonth)
        assertEquals("15", dob.rawDay)

        val expiry = specimenTd3.commonFields.dateOfExpiry
        assertEquals("34", expiry.rawYear)
        assertEquals("03", expiry.rawMonth)
        assertEquals("14", expiry.rawDay)
    }

    @Test
    fun td3_specimen_exposes_check_digits_verbatim() {
        val checks = specimenTd3.commonFields.checkDigits
        assertEquals('3', checks.documentNumber)
        assertEquals('2', checks.dateOfBirth)
        assertEquals('1', checks.dateOfExpiry)
        assertEquals('2', checks.optionalData)
        assertEquals('0', checks.composite)
    }

    @Test
    fun td3_specimen_exposes_personal_number_and_its_check_digit() {
        assertEquals("S98765432<<<<<", specimenTd3.personalNumber)
        assertEquals('2', specimenTd3.personalNumberCheckDigit)
    }

    @Test
    fun td3_format_is_always_td3() {
        assertEquals(MrzFormat.TD3, specimenTd3.format)
    }

    @Test
    fun td3_raw_lines_round_trip_unchanged() {
        assertEquals(
            listOf(
                "P<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<",
                "T220001293UTO9003152F3403141S98765432<<<<<20",
            ),
            specimenTd3.rawLines,
        )
    }

    @Test
    fun two_td3_with_identical_field_values_are_equal() {
        val other =
            TD3(
                rawLines = specimenTd3.rawLines,
                commonFields = specimenCommonFields,
                personalNumber = "S98765432<<<<<",
                personalNumberCheckDigit = '2',
            )
        assertEquals(specimenTd3, other)
    }

    @Test
    fun sealed_hierarchy_supports_exhaustive_when_matching_across_known_variants() {
        val td1: MrzDocument =
            TD1(
                rawLines = listOf("", "", ""),
                commonFields = specimenCommonFields,
                optionalData1 = "",
                optionalData2 = "",
            )
        val td2: MrzDocument =
            TD2(
                rawLines = listOf("", ""),
                commonFields = specimenCommonFields,
                optionalData = "",
            )
        val td3: MrzDocument = specimenTd3
        val mrvA: MrzDocument =
            MrvA(
                rawLines = listOf("", ""),
                commonFields = specimenCommonFields,
                optionalData = "",
            )
        val mrvB: MrzDocument =
            MrvB(
                rawLines = listOf("", ""),
                commonFields = specimenCommonFields,
                optionalData = "",
            )

        val labels =
            listOf(td1, td2, td3, mrvA, mrvB).map { doc ->
                when (doc) {
                    is TD1 -> "TD1"
                    is TD2 -> "TD2"
                    is TD3 -> "TD3"
                    is MrvA -> "MrvA"
                    is MrvB -> "MrvB"
                }
            }
        assertEquals(listOf("TD1", "TD2", "TD3", "MrvA", "MrvB"), labels)
    }
}
