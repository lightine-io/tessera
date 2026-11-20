package io.lightine.tessera.mrz.validation

import io.lightine.tessera.mrz.checkdigit.computeCheckDigit
import io.lightine.tessera.mrz.conformance.P3_F_1
import io.lightine.tessera.mrz.conformance.P4_F_1
import io.lightine.tessera.mrz.conformance.P5_F_1
import io.lightine.tessera.mrz.conformance.P6_F_1
import io.lightine.tessera.mrz.conformance.P7_F_1
import io.lightine.tessera.mrz.conformance.P7_F_2
import io.lightine.tessera.mrz.conformance.P7_F_3
import io.lightine.tessera.mrz.conformance.P7_F_4
import io.lightine.tessera.mrz.conformance.SpecimenVector
import io.lightine.tessera.mrz.model.MrzDate
import io.lightine.tessera.mrz.model.TD3
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.errors.MrzDateNotInCalendar
import io.lightine.tessera.types.errors.MrzExpiryDateCenturyNotInferred
import io.lightine.tessera.types.errors.MrzExpiryDateImplausiblyFar
import io.lightine.tessera.types.errors.MrzExpiryDatePast
import io.lightine.tessera.types.errors.MrzWarning
import io.lightine.tessera.types.vocabulary.MrzField
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * `MrzExpiryDateCenturyNotInferred`: reported when the expiry parts form a real date but no
 * century puts the year from 10 years before to 50 years after the reference year.
 *
 * Fixtures are the published ICAO Doc 9303 specimens. P3-F-1, P5-F-1 and P6-F-1 expire in `12`,
 * P7-F-1 and P7-F-2 in `96`, P7-F-3 and P7-F-4 in `94`. Every reference time is fixed.
 */
class MrzExpiryCenturyNotInferredValidationTest {
    private val referenceTime = Instant.parse("2026-11-19T00:00:00Z")
    private val referenceDate = LocalDate(2026, 11, 19)

    // One specimen per format, all with an expiry year outside the window at the reference time.
    private val outsideWindow: List<SpecimenVector> = listOf(P3_F_1, P5_F_1, P6_F_1, P7_F_1, P7_F_2, P7_F_3, P7_F_4)

    private fun expiryWarnings(warnings: List<MrzWarning>): List<MrzWarning> =
        warnings.filter { it is MrzExpiryDateCenturyNotInferred || it is MrzExpiryDatePast || it is MrzExpiryDateImplausiblyFar }

    private fun expectedFor(
        v: SpecimenVector,
        date: LocalDate,
    ): MrzExpiryDateCenturyNotInferred {
        val iso = v.dateOfExpiry.toString()
        return MrzExpiryDateCenturyNotInferred(
            rawYear = iso.substring(2, 4),
            rawMonth = iso.substring(5, 7),
            rawDay = iso.substring(8, 10),
            referenceDate = date,
        )
    }

    private fun warningsFromParse(
        lines: List<String>,
        at: Instant,
    ): List<MrzWarning> {
        val result = MrzParser.parse(lines, at)
        assertIs<ParseResult.Success>(result, "parse result: $result")
        return result.metadata.warnings
    }

    // --- Every format and every entry point ---

    @Test
    fun every_format_reports_it_on_every_entry_point() {
        outsideWindow.forEach { v ->
            val expected = listOf(expectedFor(v, referenceDate))
            val parsed = MrzParser.parse(v.lines, referenceTime)
            val document = assertIs<ParseResult.Success>(parsed, "${v.id} parse").document
            assertEquals(expected, expiryWarnings(parsed.metadata.warnings), "${v.id} parse")
            assertEquals(expected, expiryWarnings(MrzValidator.validate(v.lines, referenceTime).warnings), "${v.id} lines")
            assertEquals(
                expected,
                expiryWarnings(MrzValidator.validate(v.lines.joinToString("\n"), referenceTime).warnings),
                "${v.id} string",
            )
            assertEquals(
                expected,
                expiryWarnings(MrzValidator.validate(v.lines, v.format, referenceTime).warnings),
                "${v.id} lines and format",
            )
            assertEquals(expected, expiryWarnings(MrzValidator.validate(document, referenceTime).warnings), "${v.id} document")
        }
    }

    @Test
    fun a_year_inside_the_window_is_not_reported() {
        // P4-F-1 expires in 2034: inside the window at the reference time.
        val warnings = warningsFromParse(P4_F_1.lines, referenceTime)
        assertTrue(warnings.none { it is MrzExpiryDateCenturyNotInferred }, "got $warnings")
    }

    // --- Past edge: the window starts 10 years before the reference year ---

    @Test
    fun year_just_outside_the_past_edge_is_reported() {
        // 2012 is 11 years before 2023, and 1912 is further back: no century fits.
        val at = Instant.parse("2023-01-01T00:00:00Z")
        val warnings = expiryWarnings(warningsFromParse(P3_F_1.lines, at))
        assertEquals(listOf(expectedFor(P3_F_1, LocalDate(2023, 1, 1))), warnings)
    }

    @Test
    fun year_just_inside_the_past_edge_is_reported_as_past_instead() {
        // 2012 is 10 years before 2022: inside the window, and before the reference date.
        val at = Instant.parse("2022-12-31T23:59:59Z")
        val warnings = expiryWarnings(warningsFromParse(P3_F_1.lines, at))
        assertEquals(
            listOf<MrzWarning>(MrzExpiryDatePast(expiryDate = LocalDate(2012, 4, 15), referenceDate = LocalDate(2022, 12, 31))),
            warnings,
        )
    }

    // --- Future edge: the window ends 50 years after the reference year ---

    @Test
    fun year_beyond_the_future_edge_with_the_other_century_too_old_is_reported() {
        // 2094 is 51 years after 2043, and 1994 is 49 years before it: no century fits.
        val at = Instant.parse("2043-12-31T00:00:00Z")
        val warnings = expiryWarnings(warningsFromParse(P7_F_3.lines, at))
        assertEquals(listOf(expectedFor(P7_F_3, LocalDate(2043, 12, 31))), warnings)
    }

    @Test
    fun year_on_the_future_edge_is_reported_as_implausibly_far_instead() {
        // 2094 is 50 years after 2044: inside the window, and more than 10 years away.
        val at = Instant.parse("2044-01-01T00:00:00Z")
        val warnings = expiryWarnings(warningsFromParse(P7_F_3.lines, at))
        assertEquals(
            listOf<MrzWarning>(
                MrzExpiryDateImplausiblyFar(
                    expiryDate = LocalDate(2094, 6, 23),
                    referenceDate = LocalDate(2044, 1, 1),
                    thresholdYears = 10,
                ),
            ),
            warnings,
        )
    }

    // --- Parts that are not a date: existing behaviour kept ---

    @Test
    fun parts_that_form_no_calendar_date_give_only_date_not_in_calendar() {
        // The TD3 specimen P3-F-1 with expiry month 13 (`121315`). Check digits recomputed.
        val line2 = withTd3Expiry(P3_F_1.lines[1], "121315")
        val result = MrzValidator.validate(listOf(P3_F_1.lines[0], line2), referenceTime)
        assertTrue(result.validationFailures.none { it !is MrzDateNotInCalendar }, "failures: ${result.validationFailures}")
        val notInCalendar = assertIs<MrzDateNotInCalendar>(result.validationFailures.single())
        assertEquals(MrzField.DATE_OF_EXPIRY, notInCalendar.field)
        assertEquals(emptyList(), expiryWarnings(result.warnings))
    }

    @Test
    fun parts_that_are_not_digits_give_no_expiry_warning() {
        val parsed = assertIs<ParseResult.Success>(MrzParser.parse(P3_F_1.lines, referenceTime)).document
        val td3 = assertIs<TD3>(parsed)
        val nonDigits = MrzDate.parseExpiry("1<", "04", "15", referenceTime)
        assertEquals(null, nonDigits.componentsFormCalendarDate)
        val document = td3.copy(commonFields = td3.commonFields.copy(dateOfExpiry = nonDigits))
        val result = MrzValidator.validate(document, referenceTime)
        assertEquals(emptyList(), expiryWarnings(result.warnings))
        assertTrue(result.validationFailures.none { it is MrzDateNotInCalendar }, "failures: ${result.validationFailures}")
    }

    // --- Never two expiry warnings ---

    @Test
    fun no_result_carries_more_than_one_expiry_warning() {
        val specimens = outsideWindow + P4_F_1
        for (year in 1900..2150) {
            val at = Instant.parse("$year-06-15T00:00:00Z")
            specimens.forEach { v ->
                val result = MrzParser.parse(v.lines, at)
                val success = assertIs<ParseResult.Success>(result, "${v.id} at $year")
                val warnings = expiryWarnings(success.metadata.warnings)
                assertTrue(warnings.size <= 1, "${v.id} at $year: $warnings")
                val centuryUnknown = success.document.commonFields.dateOfExpiry.computedDate == null
                assertEquals(
                    centuryUnknown,
                    warnings.singleOrNull() is MrzExpiryDateCenturyNotInferred,
                    "${v.id} at $year: $warnings",
                )
            }
        }
    }

    // TD3 line 2: expiry at 21..26, its check digit at 27, the composite check digit at 43 over
    // 0..9, 13..19 and 21..42.
    private fun withTd3Expiry(
        line2: String,
        expiry: String,
    ): String {
        val withDate = line2.substring(0, 21) + expiry + computeCheckDigit(expiry) + line2.substring(28, 43)
        val compositeInput = withDate.substring(0, 10) + withDate.substring(13, 20) + withDate.substring(21, 43)
        return withDate + computeCheckDigit(compositeInput)
    }
}
