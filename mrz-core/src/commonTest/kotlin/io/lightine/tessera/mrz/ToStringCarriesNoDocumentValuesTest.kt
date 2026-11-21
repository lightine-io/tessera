package io.lightine.tessera.mrz

import io.lightine.tessera.mrz.formats.Td3FormatSpec
import io.lightine.tessera.mrz.generation.GenerationResult
import io.lightine.tessera.mrz.generation.MrzGenerator
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.mrz.recognition.CountryCode
import io.lightine.tessera.mrz.recognition.DocumentType
import io.lightine.tessera.mrz.transliteration.IcaoDefaultTransliterationProfile
import io.lightine.tessera.mrz.transliteration.TransliteratedField
import io.lightine.tessera.mrz.transliteration.TransliterationDetails
import io.lightine.tessera.mrz.transliteration.TransliterationResult
import io.lightine.tessera.mrz.transliteration.transliterate
import io.lightine.tessera.types.vocabulary.Sex
import io.lightine.tessera.types.vocabulary.UnmappedCharacter
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Every public type of this module that holds document values prints shape, never the values.
 *
 * Each instance is built from the project's specimen data (the SDK's own synthetic person and its
 * generator, never a real document), and each string it prints must contain none of the specimen's
 * values: a name part, a document number, a date, the personal number, the issuing state, or a
 * whole raw line.
 */
class ToStringCarriesNoDocumentValuesTest {
    private val referenceTime = Instant.parse("2026-05-04T12:00:00Z")

    // The specimen's values: the name parts, every document number, both dates as printed and as
    // computed, the personal number, the issuing state, and every raw line of every format.
    private val forbidden: List<String> =
        listOf(
            "SPECIMEN",
            "ALEX",
            "MORGAN",
            "T22000129",
            "T42000137",
            "T62000145",
            "T82000153",
            "S98765432",
            "900315",
            "340314",
            "1990",
            "2034",
            "03-15",
            "03-14",
            "UTO",
        ) + Specimens.TD3 + Specimens.TD2 + Specimens.TD1 + Specimens.MRV_A + Specimens.MRV_B

    @Test
    fun parse_results_and_their_documents_print_no_document_values() {
        val success =
            assertIs<ParseResult.Success>(
                MrzParser.parseTD3(Specimens.TD3, referenceTime = referenceTime),
            )
        val tampered = Specimens.TD3.overwrite(Td3FormatSpec.documentNumberCheckDigit, "0")
        val partial =
            assertIs<ParseResult.PartialSuccess>(
                MrzParser.parseTD3(tampered, referenceTime = referenceTime),
            )
        val failure =
            assertIs<ParseResult.Failure>(
                MrzParser.parse(listOf("not an mrz", "no format fits this"), referenceTime = referenceTime),
            )

        val commonFields = success.document.commonFields
        val rendered =
            listOf(
                success.toString(),
                partial.toString(),
                failure.toString(),
                success.document.toString(),
                partial.document.toString(),
                success.metadata.toString(),
                failure.metadata.toString(),
                commonFields.toString(),
                commonFields.dateOfBirth.toString(),
                commonFields.dateOfExpiry.toString(),
                commonFields.issuingState.toString(),
                commonFields.nationality.toString(),
                commonFields.documentType.toString(),
                commonFields.checkDigits.toString(),
            )
        rendered.forEach { string -> assertCarriesNoneOfTheSpecimenValues(string) }
    }

    @Test
    fun every_document_format_prints_no_document_values() {
        val results =
            listOf(
                MrzParser.parseTD1(Specimens.TD1, referenceTime = referenceTime),
                MrzParser.parseTD2(Specimens.TD2, referenceTime = referenceTime),
                MrzParser.parseTD3(Specimens.TD3, referenceTime = referenceTime),
                MrzParser.parseMRVA(Specimens.MRV_A, referenceTime = referenceTime),
                MrzParser.parseMRVB(Specimens.MRV_B, referenceTime = referenceTime),
            )
        val documents =
            results.map { result ->
                assertIs<ParseResult.Success>(result).document
            }
        documents.forEach { document -> assertCarriesNoneOfTheSpecimenValues(document.toString()) }
    }

    @Test
    fun generation_results_and_transliteration_print_no_document_values() {
        val generated =
            assertIs<GenerationResult.Success>(
                MrzGenerator.generateTD3(
                    documentType = "P",
                    issuingState = "UTO",
                    documentNumber = "T22000129",
                    primaryIdentifier = "SPECIMEN",
                    secondaryIdentifier = "ALEX MORGAN",
                    nationality = "UTO",
                    dateOfBirth = LocalDate(1990, 3, 15),
                    sex = Sex.FEMALE,
                    dateOfExpiry = LocalDate(2034, 3, 14),
                    personalNumber = "S98765432",
                    transliteration = IcaoDefaultTransliterationProfile,
                ),
            )
        val overflow =
            assertIs<GenerationResult.Failure>(
                MrzGenerator.generateTD3(
                    documentType = "P",
                    issuingState = "UTO",
                    documentNumber = "T22000129999",
                    primaryIdentifier = "SPECIMEN",
                    secondaryIdentifier = "",
                    nationality = "UTO",
                    dateOfBirth = LocalDate(1990, 3, 15),
                    sex = Sex.FEMALE,
                    dateOfExpiry = LocalDate(2034, 3, 14),
                    personalNumber = "S98765432",
                ),
            )
        val details = assertIs<TransliterationDetails>(generated.metadata.transliterationDetails)
        val field = assertIs<TransliteratedField>(details.transliteratedFields.first())
        val outcome = IcaoDefaultTransliterationProfile.transliterate("SPECIMEN ALEX MORGAN")
        val unmapped = TransliterationResult.Failure(listOf(UnmappedCharacter('Ж', 2)))

        val rendered =
            listOf(
                generated.toString(),
                overflow.toString(),
                generated.metadata.toString(),
                details.toString(),
                field.toString(),
                outcome.result.toString(),
                unmapped.toString(),
            )
        rendered.forEach { string -> assertCarriesNoneOfTheSpecimenValues(string) }
    }

    @Test
    fun to_string_shows_only_shape() {
        val success =
            assertIs<ParseResult.Success>(
                MrzParser.parseTD3(Specimens.TD3, referenceTime = referenceTime),
            )
        assertEquals("TD3(format=TD3, lines=2)", success.document.toString())
        assertEquals("CommonFields(nameTruncated=false)", success.document.commonFields.toString())
        assertEquals(
            "MrzDate(inferenceMethod=SLIDING_WINDOW_BIRTH, componentsFormCalendarDate=true)",
            success.document.commonFields.dateOfBirth
                .toString(),
        )
        assertEquals(
            "MrzCheckDigits(optionalData=true, composite=true)",
            success.document.commonFields.checkDigits
                .toString(),
        )
        assertEquals("CountryCode", CountryCode("UTO").toString())
        assertEquals("DocumentType", DocumentType("P").toString())
        assertTrue(
            success.toString().startsWith("Success(format=TD3, warnings=0, validationFailures=0, checks="),
            "the format and the metadata counts must show: $success",
        )
    }

    private fun assertCarriesNoneOfTheSpecimenValues(rendered: String) {
        forbidden.forEach { value ->
            assertFalse(value in rendered, "toString carries the specimen value '$value': $rendered")
        }
    }
}
