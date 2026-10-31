package io.lightine.tessera.mrz.generation

import io.lightine.tessera.mrz.model.CommonFields
import io.lightine.tessera.mrz.model.MrzCheckDigits
import io.lightine.tessera.mrz.model.MrzDate
import io.lightine.tessera.mrz.model.TD3
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.mrz.recognition.CountryCode
import io.lightine.tessera.mrz.recognition.DocumentType
import io.lightine.tessera.types.errors.MrzGenerationFieldOverflow
import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.ReadMethod
import io.lightine.tessera.types.vocabulary.Sex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class MrzGeneratorTd3Test {
    private val ref2026 = Instant.parse("2026-05-04T12:00:00Z")

    private val specimenLine1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    private val specimenLine2 = "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"
    private val specimenLines = listOf(specimenLine1, specimenLine2)

    private fun specimenTd3(): TD3 {
        val result = MrzParser.parseTD3(specimenLines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        return assertIs<TD3>(success.document)
    }

    // --- Round-trip happy path ---

    @Test
    fun generate_then_compare_to_specimen_yields_identical_lines() {
        val td3 = specimenTd3()
        val result = MrzGenerator.generate(td3)
        val success = assertIs<GenerationResult.Success>(result)
        assertEquals(specimenLines, success.mrz)
    }

    @Test
    fun parse_then_generate_round_trip_preserves_specimen_lines() {
        val td3 = specimenTd3()
        val regenerated = MrzGenerator.generate(td3)
        val success = assertIs<GenerationResult.Success>(regenerated)
        // Round-trip equality at the raw-line level for the clean specimen
        assertEquals(specimenLines.joinToString("\n"), success.mrz.joinToString("\n"))
    }

    @Test
    fun generate_then_parse_round_trip_preserves_raw_fields() {
        val td3 = specimenTd3()
        val regenerated = assertIs<GenerationResult.Success>(MrzGenerator.generate(td3))
        val reparsed = MrzParser.parseTD3(regenerated.mrz, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(reparsed)
        val td3RoundTripped = assertIs<TD3>(success.document)

        // Equality at the raw-field level (Principle 1: faithful round-trip on raw values)
        assertEquals(td3.commonFields.documentType.rawCode, td3RoundTripped.commonFields.documentType.rawCode)
        assertEquals(td3.commonFields.issuingState.rawCode, td3RoundTripped.commonFields.issuingState.rawCode)
        assertEquals(td3.commonFields.rawNameField, td3RoundTripped.commonFields.rawNameField)
        assertEquals(td3.commonFields.documentNumber, td3RoundTripped.commonFields.documentNumber)
        assertEquals(td3.commonFields.nationality.rawCode, td3RoundTripped.commonFields.nationality.rawCode)
        assertEquals(td3.commonFields.dateOfBirth.rawYear, td3RoundTripped.commonFields.dateOfBirth.rawYear)
        assertEquals(td3.commonFields.dateOfBirth.rawMonth, td3RoundTripped.commonFields.dateOfBirth.rawMonth)
        assertEquals(td3.commonFields.dateOfBirth.rawDay, td3RoundTripped.commonFields.dateOfBirth.rawDay)
        assertEquals(td3.commonFields.rawSex, td3RoundTripped.commonFields.rawSex)
        assertEquals(td3.commonFields.dateOfExpiry.rawYear, td3RoundTripped.commonFields.dateOfExpiry.rawYear)
        assertEquals(td3.personalNumber, td3RoundTripped.personalNumber)
    }

    @Test
    fun generated_lines_have_correct_dimensions() {
        val td3 = specimenTd3()
        val success = assertIs<GenerationResult.Success>(MrzGenerator.generate(td3))
        assertEquals(2, success.mrz.size)
        assertEquals(44, success.mrz[0].length)
        assertEquals(44, success.mrz[1].length)
    }

    @Test
    fun generated_metadata_uses_backend_string_input_read_method() {
        val td3 = specimenTd3()
        val success = assertIs<GenerationResult.Success>(MrzGenerator.generate(td3))
        // Pragmatic placeholder: see MrzGenerator comment on the ReadMethod choice for generator
        // results. May change in a future slice that refines the result-metadata shape for
        // generation vs. parsing.
        assertEquals(ReadMethod.BACKEND_STRING_INPUT, success.metadata.readMethod)
    }

    // --- Generator recomputes check digits from the field data ---

    @Test
    fun generator_recomputes_check_digits_even_when_input_carries_corrupted_values() {
        // Build a TD3 with deliberately wrong check digits on the model. Generator must
        // recompute (Principle 7: strict ICAO conformance over faithful round-trip of bad inputs)
        // and emit the correct values.
        val td3 = specimenTd3()
        val corrupted =
            td3.copy(
                commonFields =
                    td3.commonFields.copy(
                        checkDigits =
                            MrzCheckDigits(
                                documentNumber = '0',
                                dateOfBirth = '0',
                                dateOfExpiry = '0',
                                optionalData = '0',
                                composite = '0',
                            ),
                    ),
                personalNumberCheckDigit = '0',
            )
        val regenerated = assertIs<GenerationResult.Success>(MrzGenerator.generate(corrupted))
        // The regenerated MRZ matches the original (clean) specimen, not the corrupted digits.
        assertEquals(specimenLines, regenerated.mrz)
    }

    // --- Field overflow errors ---

    @Test
    fun fails_with_overflow_when_document_type_rawcode_is_longer_than_two_characters() {
        val td3 = specimenTd3()
        val overflowing = td3.copy(commonFields = td3.commonFields.copy(documentType = DocumentType("PPP")))
        val result = MrzGenerator.generate(overflowing)
        val failure = assertIs<GenerationResult.Failure>(result)
        val error = assertIs<MrzGenerationFieldOverflow>(failure.error)
        assertEquals(MrzFormat.TD3, error.format)
        assertEquals(MrzField.DOCUMENT_TYPE, error.field)
        assertEquals(2, error.maxLength)
        assertEquals(3, error.observedLength)
        assertEquals("PPP", error.observedValue)
    }

    @Test
    fun fails_with_overflow_when_issuing_state_is_longer_than_three_characters() {
        val td3 = specimenTd3()
        val overflowing = td3.copy(commonFields = td3.commonFields.copy(issuingState = CountryCode("LONG")))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(overflowing))
        val error = assertIs<MrzGenerationFieldOverflow>(failure.error)
        assertEquals(MrzField.ISSUING_STATE, error.field)
        assertEquals(3, error.maxLength)
        assertEquals(4, error.observedLength)
    }

    @Test
    fun fails_with_overflow_when_name_field_is_longer_than_thirty_nine_characters() {
        val td3 = specimenTd3()
        val overflowing = td3.copy(commonFields = td3.commonFields.copy(rawNameField = "A".repeat(40)))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(overflowing))
        val error = assertIs<MrzGenerationFieldOverflow>(failure.error)
        assertEquals(MrzField.NAME_FIELD, error.field)
        assertEquals(39, error.maxLength)
        assertEquals(40, error.observedLength)
    }

    @Test
    fun fails_with_overflow_when_document_number_is_longer_than_nine_characters() {
        // Long document numbers (>9 chars) on TD3 are supposed to spill into the personal-number
        // field per the ICAO "long document number" extension. That extension is deferred to a
        // future slice; for now, the generator fails with overflow for >9-character doc numbers.
        val td3 = specimenTd3()
        val overflowing = td3.copy(commonFields = td3.commonFields.copy(documentNumber = "L898902C<<X"))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(overflowing))
        val error = assertIs<MrzGenerationFieldOverflow>(failure.error)
        assertEquals(MrzField.DOCUMENT_NUMBER, error.field)
        assertEquals(9, error.maxLength)
    }

    @Test
    fun fails_with_overflow_when_personal_number_is_longer_than_fourteen_characters() {
        val td3 = specimenTd3()
        val overflowing = td3.copy(personalNumber = "X".repeat(15))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(overflowing))
        val error = assertIs<MrzGenerationFieldOverflow>(failure.error)
        assertEquals(MrzField.OPTIONAL_DATA, error.field)
        assertEquals(14, error.maxLength)
        assertEquals(15, error.observedLength)
    }

    // --- Padding for short field values ---

    @Test
    fun pads_short_document_type_with_filler() {
        // Single-character document type "P" should round-trip as "P<" in the MRZ line.
        // Parser then trims trailing '<' back to "P". Round-trip works.
        val td3 = specimenTd3()
        val regenerated = assertIs<GenerationResult.Success>(MrzGenerator.generate(td3))
        // Specimen already uses "P" + "<" pattern; the generator produces the same.
        assertEquals('P', regenerated.mrz[0][0])
        assertEquals('<', regenerated.mrz[0][1])
    }

    @Test
    fun pads_empty_document_type_with_two_fillers() {
        val td3 = specimenTd3()
        val emptyDocType = td3.copy(commonFields = td3.commonFields.copy(documentType = DocumentType("")))
        val regenerated = assertIs<GenerationResult.Success>(MrzGenerator.generate(emptyDocType))
        assertEquals('<', regenerated.mrz[0][0])
        assertEquals('<', regenerated.mrz[0][1])
    }

    // --- Sex character round-trip ---

    @Test
    fun sex_is_emitted_verbatim_from_raw_sex_character() {
        // Only 'M', 'F', and '<' are accepted; any other character fails (see
        // MrzGeneratorDocumentInputValidationTest).
        for (rawSex in listOf('M', 'F', '<')) {
            val td3 = specimenTd3()
            val withSex =
                td3.copy(
                    commonFields = td3.commonFields.copy(rawSex = rawSex, sex = Sex.UNSPECIFIED),
                )
            val regenerated = assertIs<GenerationResult.Success>(MrzGenerator.generate(withSex))
            // Sex slot is at line 2 index 20
            assertEquals(rawSex, regenerated.mrz[1][20], "Generated MRZ should carry rawSex=$rawSex verbatim at line 2 position 20")
        }
    }

    // --- Round-trip for a different specimen (recognized passport code PP) ---

    @Test
    fun round_trips_a_two_character_document_type_code() {
        // PP (ordinary passport) is in the recognized set. Build a synthetic TD3 with "PP".
        val customLines =
            listOf(
                "PPUTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<",
                "L898902C<3UTO6908061F9406236ZE184226B<<<<<14",
            )
        val parsed = assertIs<ParseResult.Success>(MrzParser.parseTD3(customLines, referenceTime = ref2026))
        val td3 = assertIs<TD3>(parsed.document)

        val regenerated = assertIs<GenerationResult.Success>(MrzGenerator.generate(td3))
        assertEquals(customLines, regenerated.mrz)
        assertEquals("PP", td3.commonFields.documentType.rawCode)
    }

    // --- Date raw-component pass-through ---

    @Test
    fun date_raw_components_are_emitted_verbatim() {
        val td3 = specimenTd3()
        val regenerated = assertIs<GenerationResult.Success>(MrzGenerator.generate(td3))
        // DOB at line 2 positions 13-19; specimen has 690806
        assertEquals("690806", regenerated.mrz[1].substring(13, 19))
        // DOE at line 2 positions 21-27; specimen has 940623
        assertEquals("940623", regenerated.mrz[1].substring(21, 27))
    }

    // --- Custom MrzDate with non-default fields preserves rawYear/Month/Day ---

    @Test
    fun custom_built_MrzDate_round_trips_through_generator() {
        val td3 = specimenTd3()
        val customDob = MrzDate(rawYear = "85", rawMonth = "12", rawDay = "01")
        val customExpiry = MrzDate(rawYear = "30", rawMonth = "06", rawDay = "15")
        val customized =
            td3.copy(
                commonFields = td3.commonFields.copy(dateOfBirth = customDob, dateOfExpiry = customExpiry),
            )
        val regenerated = assertIs<GenerationResult.Success>(MrzGenerator.generate(customized))
        assertEquals("851201", regenerated.mrz[1].substring(13, 19))
        assertEquals("300615", regenerated.mrz[1].substring(21, 27))

        // Parse-back equality on the raw date components
        val reparsed = assertIs<ParseResult.Success>(MrzParser.parseTD3(regenerated.mrz, referenceTime = ref2026))
        val reparsedTd3 = assertIs<TD3>(reparsed.document)
        assertEquals("85", reparsedTd3.commonFields.dateOfBirth.rawYear)
        assertEquals("12", reparsedTd3.commonFields.dateOfBirth.rawMonth)
        assertEquals("01", reparsedTd3.commonFields.dateOfBirth.rawDay)
        assertEquals("30", reparsedTd3.commonFields.dateOfExpiry.rawYear)
        assertEquals("06", reparsedTd3.commonFields.dateOfExpiry.rawMonth)
        assertEquals("15", reparsedTd3.commonFields.dateOfExpiry.rawDay)
    }

    // --- Note on inputs untested at this slice ---

    // Unused: CommonFields.copy ergonomics — the data class is too wide to define a
    // synthetic specimen helper without parsing first. Future slices may add a builder.

    @Suppress("unused")
    private fun specimenCommonFields(): CommonFields = specimenTd3().commonFields
}
