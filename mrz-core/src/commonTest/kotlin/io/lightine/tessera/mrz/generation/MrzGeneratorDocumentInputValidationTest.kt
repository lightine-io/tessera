package io.lightine.tessera.mrz.generation

import io.lightine.tessera.mrz.model.MrvA
import io.lightine.tessera.mrz.model.MrvB
import io.lightine.tessera.mrz.model.MrzDocument
import io.lightine.tessera.mrz.model.TD1
import io.lightine.tessera.mrz.model.TD2
import io.lightine.tessera.mrz.model.TD3
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.errors.MrzGenerationFieldOverflow
import io.lightine.tessera.types.errors.MrzGenerationFieldTooShort
import io.lightine.tessera.types.errors.MrzGenerationNumericInNameField
import io.lightine.tessera.types.errors.MrzGenerationUnsupportedCharacters
import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.UnmappedCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

// TES-199: generate(document) applies the checks the field-value functions make, and never
// throws for a document built from the public types.
class MrzGeneratorDocumentInputValidationTest {
    private val ref2026 = Instant.parse("2026-05-04T12:00:00Z")

    private val td3Lines =
        listOf(
            "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<",
            "L898902C<3UTO6908061F9406236ZE184226B<<<<<14",
        )
    private val td1Lines =
        listOf(
            "I<UTOL898902C<3<<<<<<<<<<<<<<<",
            "6908061F3008063UTO<<<<<<<<<<<2",
            "ERIKSSON<<ANNA<MARIA<<<<<<<<<<",
        )
    private val td2Lines =
        listOf(
            "I<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<",
            "D231458907UTO6908061F3008063<<<<<<<4",
        )
    private val mrvALines =
        listOf(
            "V<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<",
            "L898902C<3UTO6908061F3008063<<<<<<<<<<<<<<<<",
        )
    private val mrvBLines =
        listOf(
            "V<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<",
            "L898902C<3UTO6908061F3008063<<<<<<<<",
        )

    private fun parse(lines: List<String>): MrzDocument =
        assertIs<ParseResult.Success>(MrzParser.parse(lines, referenceTime = ref2026)).document

    private fun specimenTd3(): TD3 = assertIs<TD3>(parse(td3Lines))

    private fun allSpecimens(): List<MrzDocument> = listOf(td1Lines, td2Lines, td3Lines, mrvALines, mrvBLines).map(::parse)

    private fun MrzDocument.withBirthYear(rawYear: String): MrzDocument {
        val cf = commonFields.copy(dateOfBirth = commonFields.dateOfBirth.copy(rawYear = rawYear))
        return when (this) {
            is TD1 -> copy(commonFields = cf)
            is TD2 -> copy(commonFields = cf)
            is TD3 -> copy(commonFields = cf)
            is MrvA -> copy(commonFields = cf)
            is MrvB -> copy(commonFields = cf)
        }
    }

    @Test
    fun lowercase_date_part_returns_unsupported_characters_not_an_exception() {
        val td3 = specimenTd3()
        val bad = td3.copy(commonFields = td3.commonFields.copy(dateOfBirth = td3.commonFields.dateOfBirth.copy(rawYear = "ab")))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(bad))
        val error = assertIs<MrzGenerationUnsupportedCharacters>(failure.error)
        assertEquals(MrzFormat.TD3, error.format)
        assertEquals(MrzField.DATE_OF_BIRTH, error.field)
        assertEquals(listOf(UnmappedCharacter('a', 0), UnmappedCharacter('b', 1)), error.unmappedCharacters)
        assertEquals("ab0806", error.observedValue)
    }

    @Test
    fun bad_expiry_day_reports_its_position_in_the_six_character_date() {
        val td3 = specimenTd3()
        val bad = td3.copy(commonFields = td3.commonFields.copy(dateOfExpiry = td3.commonFields.dateOfExpiry.copy(rawDay = "2?")))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(bad))
        val error = assertIs<MrzGenerationUnsupportedCharacters>(failure.error)
        assertEquals(MrzField.DATE_OF_EXPIRY, error.field)
        assertEquals(listOf(UnmappedCharacter('?', 5)), error.unmappedCharacters)
    }

    @Test
    fun date_part_longer_than_two_characters_returns_field_overflow() {
        val td3 = specimenTd3()
        val bad = td3.copy(commonFields = td3.commonFields.copy(dateOfBirth = td3.commonFields.dateOfBirth.copy(rawMonth = "008")))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(bad))
        val error = assertIs<MrzGenerationFieldOverflow>(failure.error)
        assertEquals(MrzField.DATE_OF_BIRTH, error.field)
        assertEquals(2, error.maxLength)
        assertEquals(3, error.observedLength)
        assertEquals("008", error.observedValue)
    }

    @Test
    fun one_character_or_empty_birth_date_part_returns_field_too_short_in_every_format() {
        // A 5-character birth date used to give an unparseable line, and an empty part could
        // throw. The generator never pads a date part: `<` in a date means "unknown".
        for (rawYear in listOf("6", "")) {
            for (document in allSpecimens()) {
                val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(document.withBirthYear(rawYear)))
                val error = assertIs<MrzGenerationFieldTooShort>(failure.error)
                assertEquals(document.format, error.format)
                assertEquals(MrzField.DATE_OF_BIRTH, error.field)
                assertEquals(2, error.expectedLength)
                assertEquals(rawYear.length, error.observedLength)
                assertEquals(rawYear, error.observedValue)
            }
        }
    }

    @Test
    fun one_character_or_empty_expiry_date_part_returns_field_too_short() {
        val td3 = specimenTd3()
        for (rawDay in listOf("3", "")) {
            val bad = td3.copy(commonFields = td3.commonFields.copy(dateOfExpiry = td3.commonFields.dateOfExpiry.copy(rawDay = rawDay)))
            val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(bad))
            val error = assertIs<MrzGenerationFieldTooShort>(failure.error)
            assertEquals(MrzFormat.TD3, error.format)
            assertEquals(MrzField.DATE_OF_EXPIRY, error.field)
            assertEquals(2, error.expectedLength)
            assertEquals(rawDay.length, error.observedLength)
            assertEquals(rawDay, error.observedValue)
        }
    }

    @Test
    fun filler_and_uppercase_letters_in_a_date_part_are_written_as_they_are() {
        // `<` and `A-Z` are in the MRZ alphabet, so the line parses and round-trips verbatim.
        for (rawYear in listOf("<<", "6O")) {
            for (document in allSpecimens()) {
                val success = assertIs<GenerationResult.Success>(MrzGenerator.generate(document.withBirthYear(rawYear)))
                val reparsed = assertIs<ParseResult.Success>(MrzParser.parse(success.mrz, referenceTime = ref2026))
                assertEquals(rawYear, reparsed.document.commonFields.dateOfBirth.rawYear)
            }
        }
    }

    @Test
    fun lowercase_date_part_fails_in_every_format() {
        for (document in allSpecimens()) {
            val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(document.withBirthYear("ab")))
            val error = assertIs<MrzGenerationUnsupportedCharacters>(failure.error)
            assertEquals(MrzField.DATE_OF_BIRTH, error.field)
        }
    }

    @Test
    fun digit_in_name_field_returns_numeric_in_name_field() {
        val td3 = specimenTd3()
        val bad = td3.copy(commonFields = td3.commonFields.copy(rawNameField = "ERIKSS0N<<ANNA<MAR1A"))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(bad))
        val error = assertIs<MrzGenerationNumericInNameField>(failure.error)
        assertEquals(MrzFormat.TD3, error.format)
        assertEquals("ERIKSS0N<<ANNA<MAR1A", error.observedValue)
        assertEquals(listOf('0', '1'), error.numericCharacters)
    }

    @Test
    fun digit_in_name_field_is_reported_before_an_overflow_as_the_field_path_does() {
        // The field-value functions reject a digit before any other check; the document path
        // matches that order.
        val td3 = specimenTd3()
        val bad = td3.copy(commonFields = td3.commonFields.copy(rawNameField = "ERIKSS0N<<" + "A".repeat(40)))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(bad))
        assertIs<MrzGenerationNumericInNameField>(failure.error)
    }

    @Test
    fun digit_in_name_field_fails_in_every_format() {
        for (document in allSpecimens()) {
            val cf = document.commonFields.copy(rawNameField = "ERIKSS0N<<ANNA")
            val bad =
                when (document) {
                    is TD1 -> document.copy(commonFields = cf)
                    is TD2 -> document.copy(commonFields = cf)
                    is TD3 -> document.copy(commonFields = cf)
                    is MrvA -> document.copy(commonFields = cf)
                    is MrvB -> document.copy(commonFields = cf)
                }
            val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(bad))
            assertIs<MrzGenerationNumericInNameField>(failure.error)
        }
    }
}
