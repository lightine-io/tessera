package io.lightine.tessera.mrz.validation

import io.lightine.tessera.mrz.Specimens
import io.lightine.tessera.mrz.checkdigit.computeCheckDigit
import io.lightine.tessera.mrz.formats.MrvAFormatSpec
import io.lightine.tessera.mrz.formats.MrvBFormatSpec
import io.lightine.tessera.mrz.formats.MrzFormatSpec
import io.lightine.tessera.mrz.formats.Td1FormatSpec
import io.lightine.tessera.mrz.formats.Td2FormatSpec
import io.lightine.tessera.mrz.formats.Td3FormatSpec
import io.lightine.tessera.mrz.formats.extractFrom
import io.lightine.tessera.mrz.generation.GenerationResult
import io.lightine.tessera.mrz.generation.MrzGenerator
import io.lightine.tessera.mrz.model.TD3
import io.lightine.tessera.mrz.overwrite
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.mrz.parsing.ResultMetadata
import io.lightine.tessera.mrz.withCheckDigitsRecomputed
import io.lightine.tessera.mrz.withCompositeRecomputed
import io.lightine.tessera.types.errors.MrzCheckDigitMismatch
import io.lightine.tessera.types.errors.MrzDateNotInCalendar
import io.lightine.tessera.types.errors.MrzFormatNotDetected
import io.lightine.tessera.types.errors.MrzInvalidLength
import io.lightine.tessera.types.errors.MrzInvalidSexValue
import io.lightine.tessera.types.errors.MrzPersonalNumberCheckDigitFiller
import io.lightine.tessera.types.errors.MrzSexCharacterX
import io.lightine.tessera.types.vocabulary.CheckOutcome
import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.ReadMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The `checks` list of [ValidationResult]: one [MrzCheckRecord] per check in [MrzCheck] order,
 * reporting which checks ran and passed, which failed, which do not apply, and which did not run.
 *
 * Fixtures come from the project's specimens, regenerated with the SDK's own [MrzGenerator], and
 * variants are built from those with the test helpers in `Specimens.kt` (overwrite, recompute),
 * never with a hand-typed check digit.
 */
class MrzCheckRecordsTest {
    private val ref2026 = Instant.parse("2026-05-04T12:00:00Z")

    private val specimens =
        listOf(
            MrzFormat.TD1 to Specimens.TD1,
            MrzFormat.TD2 to Specimens.TD2,
            MrzFormat.TD3 to Specimens.TD3,
            MrzFormat.MRV_A to Specimens.MRV_A,
            MrzFormat.MRV_B to Specimens.MRV_B,
        )

    private fun specOf(format: MrzFormat): MrzFormatSpec =
        when (format) {
            MrzFormat.TD1 -> Td1FormatSpec
            MrzFormat.TD2 -> Td2FormatSpec
            MrzFormat.TD3 -> Td3FormatSpec
            MrzFormat.MRV_A -> MrvAFormatSpec
            MrzFormat.MRV_B -> MrvBFormatSpec
        }

    private fun documentOf(lines: List<String>) =
        when (val parsed = MrzParser.parse(lines, ref2026)) {
            is ParseResult.Success -> parsed.document
            is ParseResult.PartialSuccess -> parsed.document
            is ParseResult.Failure -> error("fixture did not parse: ${parsed.error}")
        }

    /** The specimen parsed, then encoded again by the SDK's generator: a clean generated document. */
    private fun generatedLines(specimen: List<String>): List<String> {
        val generated = MrzGenerator.generate(documentOf(specimen))
        return assertIs<GenerationResult.Success>(generated).mrz
    }

    private fun outcomeOf(
        result: ValidationResult,
        check: MrzCheck,
    ): CheckOutcome = result.checks.single { it.check == check }.outcome

    /** The checks a format does not have, reported as NotApplicable. */
    private fun notApplicable(format: MrzFormat): Set<MrzCheck> =
        when (format) {
            MrzFormat.TD1, MrzFormat.TD2 -> setOf(MrzCheck.OPTIONAL_DATA_CHECK_DIGIT)
            MrzFormat.MRV_A, MrzFormat.MRV_B -> setOf(MrzCheck.OPTIONAL_DATA_CHECK_DIGIT, MrzCheck.COMPOSITE_CHECK_DIGIT)
            MrzFormat.TD3 -> emptySet()
        }

    private fun expectedOutcome(
        format: MrzFormat,
        check: MrzCheck,
    ): CheckOutcome = if (check in notApplicable(format)) CheckOutcome.NotApplicable() else CheckOutcome.Passed

    // --- A clean generated document reports its full set of checks, all Passed (or NotApplicable) ---

    @Test
    fun clean_generated_document_reports_every_check_passed_in_stable_order() {
        for ((format, specimen) in specimens) {
            val lines = generatedLines(specimen)
            val result = MrzValidator.validate(lines, ref2026)
            assertEquals(MrzCheck.entries.toList(), result.checks.map { it.check }, "check order for $format")
            for (record in result.checks) {
                assertEquals(expectedOutcome(format, record.check), record.outcome, "$format ${record.check}")
                assertEquals(record.check.field, record.field, "$format ${record.check} field")
            }
            assertTrue(result.validationFailures.isEmpty(), "$format failures: ${result.validationFailures}")
            assertNull(result.structuralFailure)
        }
    }

    @Test
    fun string_lines_and_document_paths_report_the_same_checks() {
        for ((format, specimen) in specimens) {
            val lines = generatedLines(specimen)
            val viaLines = MrzValidator.validate(lines, ref2026)
            val viaString = MrzValidator.validate(lines.joinToString("\n"), ref2026)
            val viaDocument = MrzValidator.validate(documentOf(lines), ref2026)
            assertEquals(viaDocument.checks, viaLines.checks, "lines vs document for $format")
            assertEquals(viaDocument.checks, viaString.checks, "string vs document for $format")
            // The parser's result metadata carries the same records.
            val parsed = MrzParser.parse(lines, ref2026)
            val metadata =
                when (parsed) {
                    is ParseResult.Success -> parsed.metadata
                    is ParseResult.PartialSuccess -> parsed.metadata
                    is ParseResult.Failure -> error("fixture did not parse: ${parsed.error}")
                }
            assertEquals(viaDocument.checks, metadata.checks, "parse metadata vs validate for $format")
        }
    }

    // --- One wrong check digit: that check Failed, the rest Passed ---

    @Test
    fun one_wrong_document_number_check_digit_reports_that_check_failed_and_the_rest_passed() {
        for ((format, specimen) in specimens) {
            val spec = specOf(format)
            val lines = generatedLines(specimen)
            val correct = computeCheckDigit(spec.documentNumber.extractFrom(lines))
            val wrong = if (correct == '9') '8' else '9'
            // The composite covers the document number check digit, so recompute it to keep the
            // composite check passing: exactly one check is wrong.
            val tampered = lines.overwrite(spec.documentNumberCheckDigit, wrong.toString()).withCompositeRecomputed(spec)
            val result = MrzValidator.validate(tampered, ref2026)
            assertEquals(MrzCheck.entries.toList(), result.checks.map { it.check }, "check order for $format")
            for (record in result.checks) {
                val expected =
                    if (record.check == MrzCheck.DOCUMENT_NUMBER_CHECK_DIGIT) {
                        CheckOutcome.Failed()
                    } else {
                        expectedOutcome(format, record.check)
                    }
                assertEquals(expected, record.outcome, "$format ${record.check}")
            }
            // The failure channel is unchanged: exactly one mismatch, for the document number.
            val mismatches = result.validationFailures.filterIsInstance<MrzCheckDigitMismatch>()
            assertEquals(listOf(MrzField.DOCUMENT_NUMBER), mismatches.map { it.field }, "$format failures")
        }
    }

    @Test
    fun one_wrong_composite_check_digit_reports_only_the_composite_failed() {
        val spec = Td3FormatSpec
        val lines = generatedLines(Specimens.TD3)
        val correct = computeCheckDigit(spec.compositeInputFields.joinToString("") { it.extractFrom(lines) })
        val wrong = if (correct == '9') '8' else '9'
        val tampered = lines.overwrite(spec.compositeCheckDigit, wrong.toString())
        val result = MrzValidator.validate(tampered, ref2026)
        for (record in result.checks) {
            val expected = if (record.check == MrzCheck.COMPOSITE_CHECK_DIGIT) CheckOutcome.Failed() else CheckOutcome.Passed
            assertEquals(expected, record.outcome, "TD3 ${record.check}")
        }
        assertEquals(listOf(MrzField.COMPOSITE), result.validationFailures.filterIsInstance<MrzCheckDigitMismatch>().map { it.field })
    }

    // --- Visa formats: the composite (and optional-data) check is NotApplicable ---

    @Test
    fun visa_formats_report_composite_and_optional_data_checks_not_applicable() {
        for ((format, specimen) in listOf(MrzFormat.MRV_A to Specimens.MRV_A, MrzFormat.MRV_B to Specimens.MRV_B)) {
            val result = MrzValidator.validate(generatedLines(specimen), ref2026)
            assertEquals(CheckOutcome.NotApplicable(), outcomeOf(result, MrzCheck.COMPOSITE_CHECK_DIGIT), format.name)
            assertEquals(CheckOutcome.NotApplicable(), outcomeOf(result, MrzCheck.OPTIONAL_DATA_CHECK_DIGIT), format.name)
            assertEquals(6, result.checks.count { it.outcome == CheckOutcome.Passed }, format.name)
        }
    }

    // --- A structural failure: every check NotRun ---

    @Test
    fun undetected_format_reports_every_check_not_run() {
        val result = MrzValidator.validate("TOO\nSHORT", ref2026)
        assertIs<MrzFormatNotDetected>(result.structuralFailure)
        assertEquals(MrzCheck.entries.size, result.checks.size)
        for (record in result.checks) {
            val notRun = assertIs<CheckOutcome.NotRun>(record.outcome, "${record.check}")
            assertEquals(MrzNotRunReason.FORMAT_NOT_DETECTED, notRun.reason, "reason for ${record.check}")
        }
    }

    @Test
    fun wrong_length_with_explicit_format_reports_every_check_not_run() {
        val result = MrzValidator.validate(listOf("TOO", "SHORT"), MrzFormat.TD3, ref2026)
        assertIs<MrzInvalidLength>(result.structuralFailure)
        assertEquals(MrzCheck.entries.size, result.checks.size)
        assertTrue(result.checks.all { it.outcome is CheckOutcome.NotRun && it.outcome.reason == MrzNotRunReason.INVALID_LENGTH })
    }

    @Test
    fun document_with_unreadable_raw_lines_reports_every_check_not_run() {
        val document = assertIs<TD3>(documentOf(Specimens.TD3))
        val wrongLineCount = MrzValidator.validate(document.copy(rawLines = listOf(document.rawLines[0])), ref2026)
        assertIs<MrzInvalidLength>(wrongLineCount.structuralFailure)
        assertEquals(MrzCheck.entries.size, wrongLineCount.checks.size)
        assertTrue(wrongLineCount.checks.all { it.outcome is CheckOutcome.NotRun && it.outcome.reason == MrzNotRunReason.INVALID_LENGTH })

        val badCharacter = document.copy(rawLines = listOf(document.rawLines[0].replace('A', 'a'), document.rawLines[1]))
        val result = MrzValidator.validate(badCharacter, ref2026)
        assertIs<io.lightine.tessera.types.errors.MrzCharacterSetViolation>(result.structuralFailure)
        assertTrue(result.checks.all { it.outcome is CheckOutcome.NotRun && it.outcome.reason == MrzNotRunReason.CHARACTER_SET_VIOLATION })
    }

    // --- The TD3 personal number check digit: filler where the standard allows it ---

    @Test
    fun all_filler_personal_number_with_filler_check_digit_is_not_applicable() {
        val lines = generatedLines(Specimens.TD3)
        val tampered =
            lines
                .overwrite(Td3FormatSpec.personalNumber, "<".repeat(14))
                .overwrite(Td3FormatSpec.personalNumberCheckDigit, "<")
                .withCompositeRecomputed(Td3FormatSpec)
        val result = MrzValidator.validate(tampered, ref2026)
        assertEquals(CheckOutcome.NotApplicable(), outcomeOf(result, MrzCheck.OPTIONAL_DATA_CHECK_DIGIT))
        for (record in result.checks) {
            if (record.check == MrzCheck.OPTIONAL_DATA_CHECK_DIGIT) continue
            assertEquals(CheckOutcome.Passed, record.outcome, "TD3 ${record.check}")
        }
        assertTrue(result.validationFailures.isEmpty(), "failures: ${result.validationFailures}")
        assertTrue(result.warnings.none { it is MrzPersonalNumberCheckDigitFiller })
    }

    @Test
    fun filler_check_digit_over_populated_personal_number_is_failed_and_warned() {
        val lines = generatedLines(Specimens.TD3)
        val tampered =
            lines
                .overwrite(Td3FormatSpec.personalNumberCheckDigit, "<")
                .withCompositeRecomputed(Td3FormatSpec)
        val result = MrzValidator.validate(tampered, ref2026)
        // The printed filler differs from the computed digit, so the check outcome is Failed;
        // the deviation itself stays a warning, not a validation failure.
        assertEquals(CheckOutcome.Failed(), outcomeOf(result, MrzCheck.OPTIONAL_DATA_CHECK_DIGIT))
        assertTrue(result.validationFailures.isEmpty(), "failures: ${result.validationFailures}")
        assertTrue(result.warnings.any { it is MrzPersonalNumberCheckDigitFiller })
    }

    // --- The calendar-date and sex checks are records too ---

    @Test
    fun birth_date_not_in_calendar_reports_the_calendar_check_failed_and_the_digit_check_passed() {
        val lines = generatedLines(Specimens.TD3)
        val tampered = lines.overwrite(Td3FormatSpec.dateOfBirth, "991399").withCheckDigitsRecomputed(Td3FormatSpec)
        val result = MrzValidator.validate(tampered, ref2026)
        assertEquals(CheckOutcome.Failed(), outcomeOf(result, MrzCheck.DATE_OF_BIRTH_CALENDAR_DATE))
        assertEquals(CheckOutcome.Passed, outcomeOf(result, MrzCheck.DATE_OF_BIRTH_CHECK_DIGIT))
        assertTrue(result.validationFailures.any { it is MrzDateNotInCalendar })
    }

    @Test
    fun unaccepted_sex_character_reports_the_sex_check_failed() {
        val lines = generatedLines(Specimens.TD3)
        // The sex character sits outside the composite input, so the composite still passes.
        val tampered = lines.overwrite(Td3FormatSpec.sex, "Z")
        val result = MrzValidator.validate(tampered, ref2026)
        assertEquals(CheckOutcome.Failed(), outcomeOf(result, MrzCheck.SEX_CHARACTER))
        assertEquals(CheckOutcome.Passed, outcomeOf(result, MrzCheck.COMPOSITE_CHECK_DIGIT))
        assertTrue(result.validationFailures.any { it is MrzInvalidSexValue })
    }

    @Test
    fun sex_character_x_passes_the_sex_check_and_warns() {
        val lines = generatedLines(Specimens.TD3)
        val tampered = lines.overwrite(Td3FormatSpec.sex, "X")
        val result = MrzValidator.validate(tampered, ref2026)
        assertEquals(CheckOutcome.Passed, outcomeOf(result, MrzCheck.SEX_CHARACTER))
        assertTrue(result.warnings.any { it is MrzSexCharacterX })
    }

    // --- The old constructors still work and carry no checks ---

    @Test
    fun validation_result_constructors_without_checks_stay_empty() {
        val twoArg = ValidationResult(emptyList(), emptyList())
        assertTrue(twoArg.checks.isEmpty())
        assertNull(twoArg.structuralFailure)
        val threeArg = ValidationResult(emptyList(), emptyList(), null)
        assertTrue(threeArg.checks.isEmpty())
        val metadata = ResultMetadata(ReadMethod.BACKEND_STRING_INPUT, emptyList(), emptyList(), null)
        assertTrue(metadata.checks.isEmpty())
    }
}
