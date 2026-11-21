package io.lightine.tessera.mrz.generation

import io.lightine.tessera.mrz.Specimens
import io.lightine.tessera.mrz.model.TD1
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.errors.MrzGenerationFieldOverflow
import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class MrzGeneratorTd1Test {
    private val ref2026 = Instant.parse("2026-05-04T12:00:00Z")

    // The project's TD1 specimen.
    // Three lines of 30 characters; name field on line 3; doc number on line 1.
    private val specimenLines = Specimens.TD1

    private fun specimenTd1(): TD1 {
        val result = MrzParser.parseTD1(specimenLines, referenceTime = ref2026)
        return assertIs<TD1>(assertIs<ParseResult.Success>(result).document)
    }

    // --- Round-trip happy path ---

    @Test
    fun generate_produces_specimen_lines_verbatim() {
        val regenerated = assertIs<GenerationResult.Success>(MrzGenerator.generate(specimenTd1()))
        assertEquals(specimenLines, regenerated.mrz)
    }

    @Test
    fun generate_then_parse_round_trips_raw_fields_across_all_three_lines() {
        val td1 = specimenTd1()
        val regenerated = assertIs<GenerationResult.Success>(MrzGenerator.generate(td1))
        val reparsed = assertIs<ParseResult.Success>(MrzParser.parseTD1(regenerated.mrz, referenceTime = ref2026))
        val td1RoundTripped = assertIs<TD1>(reparsed.document)

        // Line 1 fields
        assertEquals(td1.commonFields.documentType.rawCode, td1RoundTripped.commonFields.documentType.rawCode)
        assertEquals(td1.commonFields.issuingState.rawCode, td1RoundTripped.commonFields.issuingState.rawCode)
        assertEquals(td1.commonFields.documentNumber, td1RoundTripped.commonFields.documentNumber)
        assertEquals(td1.optionalData1, td1RoundTripped.optionalData1)
        // Line 2 fields
        assertEquals(td1.commonFields.dateOfBirth.rawYear, td1RoundTripped.commonFields.dateOfBirth.rawYear)
        assertEquals(td1.commonFields.rawSex, td1RoundTripped.commonFields.rawSex)
        assertEquals(td1.commonFields.dateOfExpiry.rawYear, td1RoundTripped.commonFields.dateOfExpiry.rawYear)
        assertEquals(td1.commonFields.nationality.rawCode, td1RoundTripped.commonFields.nationality.rawCode)
        assertEquals(td1.optionalData2, td1RoundTripped.optionalData2)
        // Line 3 field
        assertEquals(td1.commonFields.rawNameField, td1RoundTripped.commonFields.rawNameField)
    }

    @Test
    fun generated_lines_have_correct_dimensions() {
        val success = assertIs<GenerationResult.Success>(MrzGenerator.generate(specimenTd1()))
        assertEquals(3, success.mrz.size)
        success.mrz.forEach { assertEquals(30, it.length) }
    }

    // --- TD1-specific: layout departs from the 2-line formats ---

    @Test
    fun name_field_is_on_line_three_not_line_one() {
        val success = assertIs<GenerationResult.Success>(MrzGenerator.generate(specimenTd1()))
        assertEquals("SPECIMEN<<ALEX<MORGAN<<<<<<<<<", success.mrz[2])
    }

    @Test
    fun document_number_is_on_line_one_at_positions_5_through_13() {
        val success = assertIs<GenerationResult.Success>(MrzGenerator.generate(specimenTd1()))
        assertEquals("T42000137", success.mrz[0].substring(5, 14))
        // Document number check digit at line 1 position 14.
        assertEquals('0', success.mrz[0][14])
    }

    @Test
    fun composite_check_digit_is_at_line_2_position_29() {
        val success = assertIs<GenerationResult.Success>(MrzGenerator.generate(specimenTd1()))
        assertEquals('2', success.mrz[1][29])
    }

    // --- Field overflow ---

    @Test
    fun fails_with_overflow_when_optional_data_1_exceeds_fifteen_characters() {
        val td1 = specimenTd1().copy(optionalData1 = "X".repeat(16))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(td1))
        val error = assertIs<MrzGenerationFieldOverflow>(failure.error)
        assertEquals(MrzFormat.TD1, error.format)
        assertEquals(MrzField.OPTIONAL_DATA, error.field)
        assertEquals(15, error.maxLength)
    }

    @Test
    fun fails_with_overflow_when_optional_data_2_exceeds_eleven_characters() {
        val td1 = specimenTd1().copy(optionalData2 = "X".repeat(12))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(td1))
        val error = assertIs<MrzGenerationFieldOverflow>(failure.error)
        assertEquals(MrzField.OPTIONAL_DATA, error.field)
        assertEquals(11, error.maxLength)
    }

    @Test
    fun fails_with_overflow_when_name_field_exceeds_thirty_characters() {
        val td1 = specimenTd1()
        val overflowing = td1.copy(commonFields = td1.commonFields.copy(rawNameField = "A".repeat(31)))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(overflowing))
        val error = assertIs<MrzGenerationFieldOverflow>(failure.error)
        assertEquals(MrzField.NAME_FIELD, error.field)
        assertEquals(30, error.maxLength)
    }

    // --- Composite digit covers BOTH data lines for TD1 (Part 5 specifies four ranges) ---

    @Test
    fun composite_digit_changes_when_optional_data_2_is_mutated() {
        // The composite-input range includes optional data 2 (line 2 [18, 29)). Changing it
        // changes the composite expected digit.
        val td1 = specimenTd1().copy(optionalData2 = "XXXXXXXXXXX")
        val regenerated = assertIs<GenerationResult.Success>(MrzGenerator.generate(td1))
        // The composite is no longer '2' (the specimen's composite) — the new value is
        // computed from the mutated input.
        assertEquals(true, regenerated.mrz[1][29] != '2' || td1.optionalData2 == specimenTd1().optionalData2)
    }

    @Test
    fun composite_digit_changes_when_optional_data_1_is_mutated() {
        // Optional data 1 is on line 1 [15, 30); composite input includes line 1 [5, 30).
        val td1 = specimenTd1().copy(optionalData1 = "XXXXXXXXXXXXXXX")
        val regenerated = assertIs<GenerationResult.Success>(MrzGenerator.generate(td1))
        assertEquals(true, regenerated.mrz[1][29] != '2' || td1.optionalData1 == specimenTd1().optionalData1)
    }
}
