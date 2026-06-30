package io.lightine.tessera.mrz.parsing

import io.lightine.tessera.types.errors.MrzFormatNotDetected
import io.lightine.tessera.types.errors.MrzInvalidLength
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.ReadMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class ManualMrzReaderTest {
    private val ref2026 = Instant.parse("2026-05-04T12:00:00Z")

    // The Anna Eriksson / UTO synthetic specimens shared across the parser fixtures
    // (check digits pre-computed and locked).
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

    private fun joined(lines: List<String>) = lines.joinToString("\n")

    // --- Provenance is stamped MANUAL_ENTRY (auto-detect, both String and List forms) ---

    @Test
    fun read_string_autodetect_stamps_manual_entry() {
        val success = assertIs<ParseResult.Success>(ManualMrzReader.read(joined(td3Lines), ref2026))
        assertEquals(ReadMethod.MANUAL_ENTRY, success.metadata.readMethod)
    }

    @Test
    fun read_lines_autodetect_stamps_manual_entry() {
        val success = assertIs<ParseResult.Success>(ManualMrzReader.read(td1Lines, ref2026))
        assertEquals(ReadMethod.MANUAL_ENTRY, success.metadata.readMethod)
    }

    // --- The verdict is the parser's; only the read method changes ---

    @Test
    fun read_preserves_parser_verdict_changing_only_read_method() {
        val parsed = assertIs<ParseResult.Success>(MrzParser.parse(td3Lines, ref2026))
        val read = assertIs<ParseResult.Success>(ManualMrzReader.read(td3Lines, ref2026))
        assertEquals(parsed.document, read.document)
        assertEquals(parsed.metadata.warnings, read.metadata.warnings)
        assertEquals(parsed.metadata.validationFailures, read.metadata.validationFailures)
        assertEquals(ReadMethod.BACKEND_STRING_INPUT, parsed.metadata.readMethod)
        assertEquals(ReadMethod.MANUAL_ENTRY, read.metadata.readMethod)
    }

    // --- Each explicit-format entry point (String + List) stamps MANUAL_ENTRY on the right type ---

    @Test
    fun readTD3_string_and_lines_stamp_manual_entry() {
        for (result in listOf(ManualMrzReader.readTD3(joined(td3Lines), ref2026), ManualMrzReader.readTD3(td3Lines, ref2026))) {
            val success = assertIs<ParseResult.Success>(result)
            assertEquals(MrzFormat.TD3, success.document.format)
            assertEquals(ReadMethod.MANUAL_ENTRY, success.metadata.readMethod)
        }
    }

    @Test
    fun readTD2_string_and_lines_stamp_manual_entry() {
        for (result in listOf(ManualMrzReader.readTD2(joined(td2Lines), ref2026), ManualMrzReader.readTD2(td2Lines, ref2026))) {
            val success = assertIs<ParseResult.Success>(result)
            assertEquals(MrzFormat.TD2, success.document.format)
            assertEquals(ReadMethod.MANUAL_ENTRY, success.metadata.readMethod)
        }
    }

    @Test
    fun readTD1_string_and_lines_stamp_manual_entry() {
        for (result in listOf(ManualMrzReader.readTD1(joined(td1Lines), ref2026), ManualMrzReader.readTD1(td1Lines, ref2026))) {
            val success = assertIs<ParseResult.Success>(result)
            assertEquals(MrzFormat.TD1, success.document.format)
            assertEquals(ReadMethod.MANUAL_ENTRY, success.metadata.readMethod)
        }
    }

    @Test
    fun readMRVA_string_and_lines_stamp_manual_entry() {
        for (result in listOf(ManualMrzReader.readMRVA(joined(mrvALines), ref2026), ManualMrzReader.readMRVA(mrvALines, ref2026))) {
            val success = assertIs<ParseResult.Success>(result)
            assertEquals(MrzFormat.MRV_A, success.document.format)
            assertEquals(ReadMethod.MANUAL_ENTRY, success.metadata.readMethod)
        }
    }

    @Test
    fun readMRVB_string_and_lines_stamp_manual_entry() {
        for (result in listOf(ManualMrzReader.readMRVB(joined(mrvBLines), ref2026), ManualMrzReader.readMRVB(mrvBLines, ref2026))) {
            val success = assertIs<ParseResult.Success>(result)
            assertEquals(MrzFormat.MRV_B, success.document.format)
            assertEquals(ReadMethod.MANUAL_ENTRY, success.metadata.readMethod)
        }
    }

    // --- Failures carry the provenance too (the read method is reported even when reading fails) ---

    @Test
    fun read_undetected_shape_is_failure_stamped_manual_entry() {
        val failure = assertIs<ParseResult.Failure>(ManualMrzReader.read(listOf("TOO", "SHORT"), ref2026))
        assertIs<MrzFormatNotDetected>(failure.error)
        assertEquals(ReadMethod.MANUAL_ENTRY, failure.metadata.readMethod)
    }

    @Test
    fun readTD3_wrong_length_reports_invalid_length_stamped_manual_entry() {
        val failure = assertIs<ParseResult.Failure>(ManualMrzReader.readTD3(listOf("TOO", "SHORT"), ref2026))
        assertIs<MrzInvalidLength>(failure.error)
        assertEquals(ReadMethod.MANUAL_ENTRY, failure.metadata.readMethod)
    }

    // A correctly-typed MRZ except one wrong check digit parses structurally but fails validation →
    // PartialSuccess. The provenance is still MANUAL_ENTRY (the read method is reported even when the
    // document has bad fields), exercising the PartialSuccess branch of the read-method stamp.
    @Test
    fun read_partial_success_is_stamped_manual_entry() {
        val tampered = listOf(td3Lines[0], td3Lines[1].dropLast(1) + "0")
        val partial = assertIs<ParseResult.PartialSuccess>(ManualMrzReader.read(tampered, ref2026))
        assertEquals(ReadMethod.MANUAL_ENTRY, partial.metadata.readMethod)
        assertTrue(partial.metadata.validationFailures.isNotEmpty(), "expected a check-digit validation failure")
    }
}
