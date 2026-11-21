package io.lightine.tessera.mrz.validation

import io.lightine.tessera.mrz.Specimens
import io.lightine.tessera.mrz.formats.Td2FormatSpec
import io.lightine.tessera.mrz.formats.Td3FormatSpec
import io.lightine.tessera.mrz.overwrite
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.mrz.withCheckDigitsRecomputed
import io.lightine.tessera.types.errors.MrzExpiryDateAfterSingleLetterPassportLimit
import io.lightine.tessera.types.errors.MrzExpiryDateCenturyNotInferred
import io.lightine.tessera.types.errors.MrzWarning
import io.lightine.tessera.types.vocabulary.MrzFormat
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * `MrzExpiryDateAfterSingleLetterPassportLimit` (TES-303): ICAO Doc 9303 Part 4 (8th ed.), 4.4. A
 * passport issued without a second document code letter shall expire before 1 January 2038.
 *
 * Fixtures are the project's own specimen (SPECIMEN, ALEX MORGAN, Utopia) with the document code
 * or the expiry date changed and the check digits recomputed. Every reference time is fixed.
 */
class MrzExpiryDateAfterSingleLetterPassportLimitValidationTest {
    private val referenceTime = Instant.parse("2026-11-21T00:00:00Z")

    private fun td3(
        code: String = "P<",
        expiry: String,
    ): List<String> =
        Specimens.TD3
            .overwrite(Td3FormatSpec.documentType, code)
            .overwrite(Td3FormatSpec.dateOfExpiry, expiry)
            .withCheckDigitsRecomputed(Td3FormatSpec)

    private fun limitWarnings(warnings: List<MrzWarning>): List<MrzWarning> =
        warnings.filterIsInstance<MrzExpiryDateAfterSingleLetterPassportLimit>()

    private fun parseWarnings(
        lines: List<String>,
        at: Instant = referenceTime,
    ): List<MrzWarning> {
        val result = MrzParser.parse(lines, at)
        assertIs<ParseResult.Success>(result, "parse result: $result")
        return result.metadata.warnings
    }

    @Test
    fun a_one_letter_passport_expiring_in_2040_is_reported_on_every_entry_point() {
        val lines = td3(expiry = "400314")
        val expected = listOf(MrzExpiryDateAfterSingleLetterPassportLimit(rawDocumentCode = "P<", expiryDate = LocalDate(2040, 3, 14)))
        val parsed = MrzParser.parse(lines, referenceTime)
        val document = assertIs<ParseResult.Success>(parsed).document
        assertEquals(expected, limitWarnings(parsed.metadata.warnings), "parse")
        assertEquals(expected, limitWarnings(MrzValidator.validate(lines, referenceTime).warnings), "lines")
        assertEquals(expected, limitWarnings(MrzValidator.validate(lines.joinToString("\n"), referenceTime).warnings), "string")
        assertEquals(expected, limitWarnings(MrzValidator.validate(lines, MrzFormat.TD3, referenceTime).warnings), "lines and format")
        assertEquals(expected, limitWarnings(MrzValidator.validate(document, referenceTime).warnings), "document")
    }

    @Test
    fun the_first_day_of_2038_is_reported() {
        val warnings = limitWarnings(parseWarnings(td3(expiry = "380101")))
        assertEquals(listOf<MrzWarning>(MrzExpiryDateAfterSingleLetterPassportLimit("P<", LocalDate(2038, 1, 1))), warnings)
    }

    @Test
    fun the_last_day_of_2037_is_not_reported() {
        assertEquals(emptyList(), limitWarnings(parseWarnings(td3(expiry = "371231"))))
    }

    @Test
    fun the_unchanged_specimen_expiring_in_2034_is_not_reported() {
        assertEquals(emptyList(), limitWarnings(parseWarnings(Specimens.TD3)))
    }

    @Test
    fun a_two_letter_passport_code_is_not_reported() {
        listOf("PP", "PD", "PX").forEach { code ->
            assertEquals(emptyList(), limitWarnings(parseWarnings(td3(code = code, expiry = "400314"))), code)
        }
    }

    @Test
    fun a_td3_whose_code_does_not_start_with_p_is_not_reported() {
        // The validator reads any TD3; the 4.4 rule is for passports only.
        val result = MrzValidator.validate(td3(code = "I<", expiry = "400314"), MrzFormat.TD3, referenceTime)
        assertEquals(emptyList(), limitWarnings(result.warnings))
    }

    @Test
    fun another_format_with_a_p_code_is_not_reported() {
        // TD2 sized: Part 4 covers TD3 passports only.
        val lines =
            Specimens.TD2
                .overwrite(Td2FormatSpec.documentType, "P<")
                .overwrite(Td2FormatSpec.dateOfExpiry, "400314")
                .withCheckDigitsRecomputed(Td2FormatSpec)
        val result = MrzValidator.validate(lines, MrzFormat.TD2, referenceTime)
        assertEquals(emptyList(), limitWarnings(result.warnings))
    }

    @Test
    fun an_expiry_whose_century_is_unknown_is_not_judged() {
        // At 1980 the year 40 fits neither 2040 (60 years ahead) nor 1940 (40 years back).
        val at = Instant.parse("1980-06-15T00:00:00Z")
        val warnings = parseWarnings(td3(expiry = "400314"), at)
        assertTrue(warnings.any { it is MrzExpiryDateCenturyNotInferred }, "premise: century unknown, got $warnings")
        assertEquals(emptyList(), limitWarnings(warnings))
    }

    @Test
    fun never_together_with_a_century_unknown_expiry() {
        val lines = td3(expiry = "400314")
        for (year in 1900..2150) {
            val warnings = parseWarnings(lines, Instant.parse("$year-06-15T00:00:00Z"))
            val both = warnings.any { it is MrzExpiryDateCenturyNotInferred } && limitWarnings(warnings).isNotEmpty()
            assertTrue(!both, "at $year: $warnings")
        }
    }
}
