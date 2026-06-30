package io.lightine.tessera.mrz.validation

import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.errors.MrzFormatNotDetected
import io.lightine.tessera.types.errors.MrzInvalidLength
import io.lightine.tessera.types.vocabulary.MrzFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * TES-6 standalone string / explicit-format validator overloads, and the
 * [`ValidationResult.structuralFailure`][ValidationResult.structuralFailure] channel that carries a
 * hard parse failure separately from the soft per-field failures.
 */
class MrzValidatorStringInputTest {
    private val ref2026 = Instant.parse("2026-05-04T12:00:00Z")

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
    private val td3Lines =
        listOf(
            "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<",
            "L898902C<3UTO6908061F9406236ZE184226B<<<<<14",
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

    private fun documentOf(lines: List<String>) =
        when (val parsed = MrzParser.parse(lines, ref2026)) {
            is ParseResult.Success -> parsed.document
            is ParseResult.PartialSuccess -> parsed.document
            is ParseResult.Failure -> error("fixture did not parse: ${parsed.error}")
        }

    // --- String / lines validation matches validate(document) exactly (single source of truth) ---

    @Test
    fun validate_string_and_lines_match_validate_document() {
        val viaDocument = MrzValidator.validate(documentOf(td3Lines), ref2026)
        val viaString = MrzValidator.validate(td3Lines.joinToString("\n"), ref2026)
        val viaLines = MrzValidator.validate(td3Lines, ref2026)
        assertEquals(viaDocument.validationFailures, viaString.validationFailures)
        assertEquals(viaDocument.warnings, viaString.warnings)
        assertEquals(viaDocument.validationFailures, viaLines.validationFailures)
        assertEquals(viaDocument.warnings, viaLines.warnings)
        assertNull(viaString.structuralFailure)
        assertNull(viaLines.structuralFailure)
    }

    @Test
    fun validate_explicit_format_matches_validate_document() {
        val viaDocument = MrzValidator.validate(documentOf(td3Lines), ref2026)
        val viaString = MrzValidator.validate(td3Lines.joinToString("\n"), MrzFormat.TD3, ref2026)
        val viaLines = MrzValidator.validate(td3Lines, MrzFormat.TD3, ref2026)
        assertEquals(viaDocument.validationFailures, viaString.validationFailures)
        assertEquals(viaDocument.validationFailures, viaLines.validationFailures)
        assertNull(viaString.structuralFailure)
        assertNull(viaLines.structuralFailure)
    }

    // --- validate(document) never carries a structural failure (the document already cleared it) ---

    @Test
    fun validate_document_has_null_structural_failure() {
        assertNull(MrzValidator.validate(documentOf(td3Lines), ref2026).structuralFailure)
    }

    // --- Structurally-invalid input surfaces on structuralFailure, not validationFailures ---

    @Test
    fun validate_undetected_shape_sets_structural_failure() {
        val result = MrzValidator.validate("TOO\nSHORT", ref2026)
        assertIs<MrzFormatNotDetected>(result.structuralFailure)
        assertTrue(result.validationFailures.isEmpty())
    }

    @Test
    fun validate_explicit_format_wrong_length_sets_invalid_length() {
        val result = MrzValidator.validate(listOf("TOO", "SHORT"), MrzFormat.TD3, ref2026)
        assertIs<MrzInvalidLength>(result.structuralFailure)
        assertTrue(result.validationFailures.isEmpty())
    }

    // --- A soft per-field failure flows to validationFailures; structuralFailure stays null ---

    @Test
    fun validate_string_with_check_digit_failure_surfaces_on_validation_failures() {
        // Corrupt the TD3 composite check digit (final character of line 2) — a soft per-field
        // failure on a structurally-valid input, not a structural one.
        val tampered = listOf(td3Lines[0], td3Lines[1].dropLast(1) + "0")
        val result = MrzValidator.validate(tampered, ref2026)
        assertTrue(result.validationFailures.isNotEmpty(), "expected a check-digit validation failure")
        assertNull(result.structuralFailure)
    }

    // The explicit-format overloads dispatch each MrzFormat to the matching parser (the parseAs
    // dispatch) — a structurally-valid specimen for the right format clears structural validation
    // and matches the document path. Covers all five dispatch branches, String and List forms.
    @Test
    fun validate_explicit_format_dispatches_every_format() {
        val specimens =
            listOf(
                MrzFormat.TD1 to td1Lines,
                MrzFormat.TD2 to td2Lines,
                MrzFormat.TD3 to td3Lines,
                MrzFormat.MRV_A to mrvALines,
                MrzFormat.MRV_B to mrvBLines,
            )
        for ((format, lines) in specimens) {
            val viaLines = MrzValidator.validate(lines, format, ref2026)
            val viaString = MrzValidator.validate(lines.joinToString("\n"), format, ref2026)
            assertNull(viaLines.structuralFailure, "structuralFailure should be null for valid $format")
            assertNull(viaString.structuralFailure, "structuralFailure should be null for valid $format")
            assertEquals(
                MrzValidator.validate(documentOf(lines), ref2026).validationFailures,
                viaLines.validationFailures,
                "validation verdict should match the document path for $format",
            )
        }
    }
}
