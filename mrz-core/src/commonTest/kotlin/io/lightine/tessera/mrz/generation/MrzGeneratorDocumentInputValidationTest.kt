package io.lightine.tessera.mrz.generation

import io.lightine.tessera.mrz.model.MrvA
import io.lightine.tessera.mrz.model.MrvB
import io.lightine.tessera.mrz.model.MrzDocument
import io.lightine.tessera.mrz.model.TD1
import io.lightine.tessera.mrz.model.TD2
import io.lightine.tessera.mrz.model.TD3
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.mrz.transliteration.AzeTransliterationProfile
import io.lightine.tessera.mrz.transliteration.IcaoDefaultTransliterationProfile
import io.lightine.tessera.mrz.transliteration.TransliterationProfile
import io.lightine.tessera.mrz.transliteration.TransliterationResult
import io.lightine.tessera.types.errors.MrzGenerationFieldOverflow
import io.lightine.tessera.types.errors.MrzGenerationFieldTooShort
import io.lightine.tessera.types.errors.MrzGenerationInvalidSexValue
import io.lightine.tessera.types.errors.MrzGenerationNumericInNameField
import io.lightine.tessera.types.errors.MrzGenerationUnsupportedCharacters
import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.Sex
import io.lightine.tessera.types.vocabulary.UnmappedCharacter
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // --- TES-217: the sex character on the document path ---

    private fun MrzDocument.withCommonFields(
        transform: (io.lightine.tessera.mrz.model.CommonFields) -> io.lightine.tessera.mrz.model.CommonFields,
    ): MrzDocument {
        val cf = transform(commonFields)
        return when (this) {
            is TD1 -> copy(commonFields = cf)
            is TD2 -> copy(commonFields = cf)
            is TD3 -> copy(commonFields = cf)
            is MrvA -> copy(commonFields = cf)
            is MrvB -> copy(commonFields = cf)
        }
    }

    private fun MrzDocument.withRawSex(rawSex: Char): MrzDocument = withCommonFields { it.copy(rawSex = rawSex) }

    private fun MrzDocument.withRawNameField(rawNameField: String): MrzDocument = withCommonFields { it.copy(rawNameField = rawNameField) }

    @Test
    fun sex_other_than_m_f_or_filler_fails_in_every_format() {
        // ICAO Doc 9303 allows X only in the visual zone, so the generator rejects it too.
        for (rawSex in listOf('A', '1', 'X')) {
            for (document in allSpecimens()) {
                val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(document.withRawSex(rawSex)))
                val error = assertIs<MrzGenerationInvalidSexValue>(failure.error)
                assertEquals(document.format, error.format)
                assertEquals(rawSex, error.observed)
            }
        }
    }

    @Test
    fun sex_other_than_m_f_or_filler_fails_through_every_per_format_overload() {
        for (document in allSpecimens()) {
            val result =
                when (val bad = document.withRawSex('X')) {
                    is TD1 -> MrzGenerator.generate(bad)
                    is TD2 -> MrzGenerator.generate(bad)
                    is TD3 -> MrzGenerator.generate(bad)
                    is MrvA -> MrzGenerator.generate(bad)
                    is MrvB -> MrzGenerator.generate(bad)
                }
            assertIs<MrzGenerationInvalidSexValue>(assertIs<GenerationResult.Failure>(result).error)
        }
    }

    @Test
    fun sex_m_f_and_filler_round_trip_in_every_format() {
        for (rawSex in listOf('M', 'F', '<')) {
            for (document in allSpecimens()) {
                val success = assertIs<GenerationResult.Success>(MrzGenerator.generate(document.withRawSex(rawSex)))
                val reparsed = assertIs<ParseResult.Success>(MrzParser.parse(success.mrz, referenceTime = ref2026))
                assertEquals(rawSex, reparsed.document.commonFields.rawSex)
            }
        }
    }

    @Test
    fun date_error_is_reported_before_a_bad_sex_value() {
        for (document in allSpecimens()) {
            val bad = document.withBirthYear("ab").withRawSex('X')
            val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(bad))
            assertIs<MrzGenerationUnsupportedCharacters>(failure.error)
        }
    }

    @Test
    fun bad_sex_value_fails_on_the_overload_with_a_profile() {
        for (document in allSpecimens()) {
            val failure =
                assertIs<GenerationResult.Failure>(MrzGenerator.generate(document.withRawSex('X'), IcaoDefaultTransliterationProfile))
            assertIs<MrzGenerationInvalidSexValue>(failure.error)
        }
    }

    // --- TES-217: a transliteration profile on the document path ---

    private val dob = LocalDate(1990, 1, 15)
    private val doe = LocalDate(2031, 1, 15)

    // The same document through the field-value function for its format.
    private fun generateFromFields(
        format: MrzFormat,
        primary: String,
        secondary: String,
        profile: TransliterationProfile?,
    ): GenerationResult =
        when (format) {
            MrzFormat.TD3 -> {
                MrzGenerator.generateTD3("P", "UTO", "D23145890", primary, secondary, "UTO", dob, Sex.FEMALE, doe, "", profile)
            }

            MrzFormat.TD2 -> {
                MrzGenerator.generateTD2("I", "UTO", "D23145890", primary, secondary, "UTO", dob, Sex.FEMALE, doe, "", profile)
            }

            MrzFormat.TD1 -> {
                MrzGenerator.generateTD1("I", "UTO", "D23145890", primary, secondary, "UTO", dob, Sex.FEMALE, doe, "", "", profile)
            }

            MrzFormat.MRV_A -> {
                MrzGenerator.generateMrvA("V", "UTO", "D23145890", primary, secondary, "UTO", dob, Sex.FEMALE, doe, "", profile)
            }

            MrzFormat.MRV_B -> {
                MrzGenerator.generateMrvB("V", "UTO", "D23145890", primary, secondary, "UTO", dob, Sex.FEMALE, doe, "", profile)
            }
        }

    // A document whose raw name field holds the name in its source script: the parsed field-value
    // output with the name field replaced by hand.
    private fun sourceScriptDocument(
        format: MrzFormat,
        rawNameField: String,
    ): MrzDocument {
        val ascii = assertIs<GenerationResult.Success>(generateFromFields(format, "SAHIN", "ULKU", null))
        return parse(ascii.mrz).withRawNameField(rawNameField)
    }

    private val formats = listOf(MrzFormat.TD3, MrzFormat.TD2, MrzFormat.TD1, MrzFormat.MRV_A, MrzFormat.MRV_B)

    @Test
    fun profile_overload_matches_the_field_value_functions_in_every_format() {
        for (profile in listOf(IcaoDefaultTransliterationProfile, AzeTransliterationProfile)) {
            for (format in formats) {
                val expected = assertIs<GenerationResult.Success>(generateFromFields(format, "ŞAHİN", "ÜLKÜ", profile))
                val document = sourceScriptDocument(format, "ŞAHİN<<ÜLKÜ")
                val success = assertIs<GenerationResult.Success>(MrzGenerator.generate(document, profile))
                assertEquals(expected.mrz, success.mrz, "$format ${profile.identifier}")

                val details = assertNotNull(success.metadata.transliterationDetails)
                assertEquals(profile.identifier, details.profileIdentifier)
                val entry = details.transliteratedFields.single()
                assertEquals(MrzField.NAME_FIELD, entry.field)
                assertEquals("ŞAHİN<<ÜLKÜ", entry.originalInput)
                val expectedName = if (profile === AzeTransliterationProfile) "SHAHIN<<ULKU" else "SAHIN<<ULKU"
                assertEquals(expectedName, entry.transliteratedOutput)
            }
        }
    }

    @Test
    fun per_format_profile_overloads_match_the_document_overload() {
        for (format in formats) {
            val document = sourceScriptDocument(format, "ŞAHİN<<ÜLKÜ")
            val viaDocument = MrzGenerator.generate(document, AzeTransliterationProfile)
            val viaFormat =
                when (document) {
                    is TD1 -> MrzGenerator.generate(document, AzeTransliterationProfile)
                    is TD2 -> MrzGenerator.generate(document, AzeTransliterationProfile)
                    is TD3 -> MrzGenerator.generate(document, AzeTransliterationProfile)
                    is MrvA -> MrzGenerator.generate(document, AzeTransliterationProfile)
                    is MrvB -> MrzGenerator.generate(document, AzeTransliterationProfile)
                }
            assertEquals(viaDocument, viaFormat)
        }
    }

    @Test
    fun one_argument_overload_still_applies_no_profile() {
        val document = sourceScriptDocument(MrzFormat.TD3, "ŞAHİN<<ÜLKÜ")
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(document))
        val error = assertIs<MrzGenerationUnsupportedCharacters>(failure.error)
        assertEquals(MrzField.NAME_FIELD, error.field)
        assertNull(failure.metadata.transliterationDetails)
    }

    @Test
    fun digit_in_name_field_is_reported_before_the_profile_runs() {
        // This profile would fail on every input; the digit check comes first.
        val failingProfile =
            object : TransliterationProfile {
                override val identifier: String = "TEST"

                override fun toMrzAlphabet(normalizedInput: String): TransliterationResult =
                    TransliterationResult.Failure(unmappedCharacters = listOf(UnmappedCharacter(normalizedInput.first(), 0)))
            }
        for (format in formats) {
            val document = sourceScriptDocument(format, "ŞAH1N<<ÜLKÜ")
            val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(document, failingProfile))
            val error = assertIs<MrzGenerationNumericInNameField>(failure.error)
            assertEquals("ŞAH1N<<ÜLKÜ", error.observedValue)
            assertEquals(listOf('1'), error.numericCharacters)
        }
    }

    @Test
    fun profile_failure_returns_unsupported_characters_for_the_name_field() {
        val failingProfile =
            object : TransliterationProfile {
                override val identifier: String = "TEST"

                override fun toMrzAlphabet(normalizedInput: String): TransliterationResult =
                    TransliterationResult.Failure(unmappedCharacters = listOf(UnmappedCharacter('Ş', 0)))
            }
        val document = sourceScriptDocument(MrzFormat.TD3, "ŞAHİN<<ÜLKÜ")
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(document, failingProfile))
        val error = assertIs<MrzGenerationUnsupportedCharacters>(failure.error)
        assertEquals(MrzField.NAME_FIELD, error.field)
        assertEquals(listOf(UnmappedCharacter('Ş', 0)), error.unmappedCharacters)
    }

    @Test
    fun aze_expansion_past_the_field_width_returns_field_overflow() {
        // 30 characters fit a TD1 name field; each Ş becomes SH, so the output is 35.
        val rawNameField = "ŞŞŞŞŞ<<" + "A".repeat(23)
        assertEquals(30, rawNameField.length)
        val document = sourceScriptDocument(MrzFormat.TD1, rawNameField)
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(document, AzeTransliterationProfile))
        val error = assertIs<MrzGenerationFieldOverflow>(failure.error)
        assertEquals(MrzFormat.TD1, error.format)
        assertEquals(MrzField.NAME_FIELD, error.field)
        assertEquals(30, error.maxLength)
        assertEquals(35, error.observedLength)
        assertNotNull(failure.metadata.transliterationDetails)
    }

    @Test
    fun aze_profile_changes_mrz_letters_in_a_parsed_document() {
        // The documented caveat: a profile also maps letters already in the MRZ alphabet, so it
        // must not be passed for a document that came from parsing. A parsed name field keeps its
        // trailing filler and so has the full width: under AZE a 1-to-1 mapping (C to J) changes the
        // name silently, and an expanding one (J to ZH) no longer fits.
        val td3 = specimenTd3()

        fun parsedWithName(name: String): MrzDocument {
            val lines =
                assertIs<GenerationResult.Success>(
                    MrzGenerator.generate(td3.copy(commonFields = td3.commonFields.copy(rawNameField = name))),
                )
            return parse(lines.mrz)
        }

        val parsedC = parsedWithName("CARLSON<<ANNA")
        val changed = assertIs<GenerationResult.Success>(MrzGenerator.generate(parsedC, AzeTransliterationProfile))
        assertEquals("JARLSON<<ANNA", changed.mrz[0].substring(5).trimEnd('<'))

        val parsedJ = parsedWithName("JONES<<ANNA")
        val overflow = assertIs<GenerationResult.Failure>(MrzGenerator.generate(parsedJ, AzeTransliterationProfile))
        val error = assertIs<MrzGenerationFieldOverflow>(overflow.error)
        assertEquals(MrzField.NAME_FIELD, error.field)
        assertEquals(40, error.observedLength)

        // Without the filler, J becomes ZH.
        val handBuilt = parsedJ.withRawNameField("JONES<<ANNA")
        val zh = assertIs<GenerationResult.Success>(MrzGenerator.generate(handBuilt, AzeTransliterationProfile))
        assertEquals("ZHONES<<ANNA", zh.mrz[0].substring(5).trimEnd('<'))

        // The ICAO default leaves MRZ letters as they are.
        val icao = assertIs<GenerationResult.Success>(MrzGenerator.generate(parsedJ, IcaoDefaultTransliterationProfile))
        assertEquals(assertIs<GenerationResult.Success>(MrzGenerator.generate(parsedJ)).mrz, icao.mrz)
    }
}
