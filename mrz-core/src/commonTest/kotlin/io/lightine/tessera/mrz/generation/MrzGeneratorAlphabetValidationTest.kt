package io.lightine.tessera.mrz.generation

import io.lightine.tessera.mrz.Specimens
import io.lightine.tessera.mrz.model.TD3
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.mrz.recognition.CountryCode
import io.lightine.tessera.mrz.recognition.DocumentType
import io.lightine.tessera.types.errors.MrzGenerationUnsupportedCharacters
import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.UnmappedCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class MrzGeneratorAlphabetValidationTest {
    private val ref2026 = Instant.parse("2026-05-04T12:00:00Z")

    // The project's TD3 specimen.
    private val specimenLines = Specimens.TD3

    private fun specimenTd3(): TD3 {
        val result = MrzParser.parseTD3(specimenLines, referenceTime = ref2026)
        val success = assertIs<ParseResult.Success>(result)
        return assertIs<TD3>(success.document)
    }

    @Test
    fun non_mrz_character_in_name_field_returns_unsupported_characters_error() {
        val td3 = specimenTd3()
        val withDiacritic = td3.copy(commonFields = td3.commonFields.copy(rawNameField = "MÜLLER<<HANS<<<<<<<<<<<<<<<<<<<<<<<<<<<"))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(withDiacritic))
        val error = assertIs<MrzGenerationUnsupportedCharacters>(failure.error)
        assertEquals(MrzFormat.TD3, error.format)
        assertEquals(MrzField.NAME_FIELD, error.field)
        assertEquals(listOf(UnmappedCharacter('Ü', 1)), error.unmappedCharacters)
    }

    @Test
    fun non_mrz_character_in_document_number_returns_unsupported_characters_error() {
        val td3 = specimenTd3()
        val withBadDocNum = td3.copy(commonFields = td3.commonFields.copy(documentNumber = "T22000ñ29"))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(withBadDocNum))
        val error = assertIs<MrzGenerationUnsupportedCharacters>(failure.error)
        assertEquals(MrzField.DOCUMENT_NUMBER, error.field)
        assertEquals(1, error.unmappedCharacters.size)
        assertEquals('ñ', error.unmappedCharacters[0].character)
        assertEquals(6, error.unmappedCharacters[0].position)
    }

    @Test
    fun non_mrz_character_in_personal_number_returns_unsupported_characters_error() {
        val td3 = specimenTd3()
        val withBadPersonal = td3.copy(personalNumber = "S987é5432")
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(withBadPersonal))
        val error = assertIs<MrzGenerationUnsupportedCharacters>(failure.error)
        assertEquals(MrzField.OPTIONAL_DATA, error.field)
        assertEquals('é', error.unmappedCharacters[0].character)
    }

    @Test
    fun non_mrz_character_in_issuing_state_returns_unsupported_characters_error() {
        val td3 = specimenTd3()
        val withBadState = td3.copy(commonFields = td3.commonFields.copy(issuingState = CountryCode("Uñ ")))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(withBadState))
        val error = assertIs<MrzGenerationUnsupportedCharacters>(failure.error)
        assertEquals(MrzField.ISSUING_STATE, error.field)
    }

    @Test
    fun non_mrz_character_in_document_type_returns_unsupported_characters_error() {
        val td3 = specimenTd3()
        val withBadType = td3.copy(commonFields = td3.commonFields.copy(documentType = DocumentType("Ä")))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(withBadType))
        val error = assertIs<MrzGenerationUnsupportedCharacters>(failure.error)
        assertEquals(MrzField.DOCUMENT_TYPE, error.field)
    }

    @Test
    fun all_mrz_alphabet_input_still_succeeds() {
        // Sanity: the validation gap closure does not break the existing happy path.
        val td3 = specimenTd3()
        val success = assertIs<GenerationResult.Success>(MrzGenerator.generate(td3))
        assertEquals(specimenLines, success.mrz)
    }

    @Test
    fun multiple_unmapped_characters_in_one_field_are_all_reported() {
        val td3 = specimenTd3()
        val withMultiBad = td3.copy(commonFields = td3.commonFields.copy(rawNameField = "MÜLLÄR<<HANS<<<<<<<<<<<<<<<<<<<<<<<<<<<"))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(withMultiBad))
        val error = assertIs<MrzGenerationUnsupportedCharacters>(failure.error)
        assertEquals(2, error.unmappedCharacters.size)
        assertEquals(UnmappedCharacter('Ü', 1), error.unmappedCharacters[0])
        assertEquals(UnmappedCharacter('Ä', 4), error.unmappedCharacters[1])
    }
}
