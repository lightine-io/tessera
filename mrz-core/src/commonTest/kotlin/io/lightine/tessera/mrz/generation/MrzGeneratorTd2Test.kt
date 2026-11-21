package io.lightine.tessera.mrz.generation

import io.lightine.tessera.mrz.Specimens
import io.lightine.tessera.mrz.model.TD2
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.mrz.recognition.DocumentType
import io.lightine.tessera.types.errors.MrzGenerationFieldOverflow
import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class MrzGeneratorTd2Test {
    private val ref2026 = Instant.parse("2026-05-04T12:00:00Z")

    // The project's TD2 specimen. Document type `I`, doc number T62000145, DOB 900315,
    // expiry 340314, sex F, optional data all filler.
    private val specimenLines = Specimens.TD2

    private fun specimenTd2(): TD2 {
        val result = MrzParser.parseTD2(specimenLines, referenceTime = ref2026)
        return assertIs<TD2>(assertIs<ParseResult.Success>(result).document)
    }

    // --- Round-trip happy path ---

    @Test
    fun generate_produces_specimen_lines_verbatim() {
        val regenerated = assertIs<GenerationResult.Success>(MrzGenerator.generate(specimenTd2()))
        assertEquals(specimenLines, regenerated.mrz)
    }

    @Test
    fun generate_then_parse_round_trips_raw_fields() {
        val td2 = specimenTd2()
        val regenerated = assertIs<GenerationResult.Success>(MrzGenerator.generate(td2))
        val reparsed = assertIs<ParseResult.Success>(MrzParser.parseTD2(regenerated.mrz, referenceTime = ref2026))
        val td2RoundTripped = assertIs<TD2>(reparsed.document)

        assertEquals(td2.commonFields.documentType.rawCode, td2RoundTripped.commonFields.documentType.rawCode)
        assertEquals(td2.commonFields.issuingState.rawCode, td2RoundTripped.commonFields.issuingState.rawCode)
        assertEquals(td2.commonFields.rawNameField, td2RoundTripped.commonFields.rawNameField)
        assertEquals(td2.commonFields.documentNumber, td2RoundTripped.commonFields.documentNumber)
        assertEquals(td2.commonFields.nationality.rawCode, td2RoundTripped.commonFields.nationality.rawCode)
        assertEquals(td2.commonFields.dateOfBirth.rawYear, td2RoundTripped.commonFields.dateOfBirth.rawYear)
        assertEquals(td2.commonFields.rawSex, td2RoundTripped.commonFields.rawSex)
        assertEquals(td2.optionalData, td2RoundTripped.optionalData)
    }

    @Test
    fun generated_lines_have_correct_dimensions() {
        val success = assertIs<GenerationResult.Success>(MrzGenerator.generate(specimenTd2()))
        assertEquals(2, success.mrz.size)
        success.mrz.forEach { assertEquals(36, it.length) }
    }

    // --- TD2-specific: optional data is 7 chars + composite at position 35 ---

    @Test
    fun optional_data_is_seven_characters_at_positions_28_through_34() {
        val success = assertIs<GenerationResult.Success>(MrzGenerator.generate(specimenTd2()))
        assertEquals("<<<<<<<", success.mrz[1].substring(28, 35))
    }

    @Test
    fun composite_check_digit_is_at_position_35_of_line_2() {
        val success = assertIs<GenerationResult.Success>(MrzGenerator.generate(specimenTd2()))
        assertEquals('8', success.mrz[1][35])
    }

    // --- Field overflow ---

    @Test
    fun fails_with_overflow_when_optional_data_exceeds_seven_characters() {
        val td2 = specimenTd2().copy(optionalData = "X".repeat(8))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(td2))
        val error = assertIs<MrzGenerationFieldOverflow>(failure.error)
        assertEquals(MrzFormat.TD2, error.format)
        assertEquals(MrzField.OPTIONAL_DATA, error.field)
        assertEquals(7, error.maxLength)
        assertEquals(8, error.observedLength)
    }

    @Test
    fun fails_with_overflow_when_name_field_exceeds_thirty_one_characters() {
        val td2 = specimenTd2()
        val overflowing = td2.copy(commonFields = td2.commonFields.copy(rawNameField = "A".repeat(32)))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(overflowing))
        val error = assertIs<MrzGenerationFieldOverflow>(failure.error)
        assertEquals(MrzField.NAME_FIELD, error.field)
        assertEquals(31, error.maxLength)
    }

    @Test
    fun fails_with_overflow_when_document_type_exceeds_two_characters() {
        val td2 = specimenTd2()
        val overflowing = td2.copy(commonFields = td2.commonFields.copy(documentType = DocumentType("XYZ")))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(overflowing))
        val error = assertIs<MrzGenerationFieldOverflow>(failure.error)
        assertEquals(MrzField.DOCUMENT_TYPE, error.field)
    }
}
