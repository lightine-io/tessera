package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.generation.GenerationResult
import io.lightine.tessera.mrz.generation.MrzGenerator
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.vocabulary.Sex
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Padding of a name line read short (TES-264): line 1 of a two-line MRZ is filled with `<` at the end, only
 * within the limits on [MrzFillerPadding], and the result reports it. Synthetic data only: every MRZ comes
 * from the SDK's own generator with the project's specimen person (UTO = Utopia).
 */
class MrzFillerPaddingTest {
    private val referenceTime = Instant.parse("2026-05-04T12:00:00Z")

    private class FakeFrame

    private suspend fun analyse(
        vararg lines: String,
        mode: ParsingMode = ParsingMode.STRICT,
    ): MrzScanResult =
        run {
            MrzFrameAnalyzer(
                recognizer = MrzTextRecognizer<FakeFrame> { RecognizedText(lines.map { RecognizedLine(it, null) }) },
                mode = mode,
                telemetry = NoOpTelemetrySink,
                referenceTimeProvider = { referenceTime },
            ).analyse(FakeFrame())
        }

    private val td3: List<String> =
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
                personalNumber = "",
            ),
        ).mrz

    private val td2: List<String> =
        assertIs<GenerationResult.Success>(
            MrzGenerator.generateTD2(
                documentType = "I",
                issuingState = "UTO",
                documentNumber = "T62000145",
                primaryIdentifier = "SPECIMEN",
                secondaryIdentifier = "ALEX MORGAN",
                nationality = "UTO",
                dateOfBirth = LocalDate(1990, 3, 15),
                sex = Sex.FEMALE,
                dateOfExpiry = LocalDate(2034, 3, 14),
                optionalData = "",
            ),
        ).mrz

    private val td1: List<String> =
        assertIs<GenerationResult.Success>(
            MrzGenerator.generateTD1(
                documentType = "I",
                issuingState = "UTO",
                documentNumber = "T42000137",
                primaryIdentifier = "SPECIMEN",
                secondaryIdentifier = "ALEX MORGAN",
                nationality = "UTO",
                dateOfBirth = LocalDate(1990, 3, 15),
                sex = Sex.FEMALE,
                dateOfExpiry = LocalDate(2034, 3, 14),
                optionalData1 = "",
                optionalData2 = "",
            ),
        ).mrz

    // The name line as a text reader that shortens the trailing filler run reads it: the run becomes [kept] `<`.
    private fun shortTail(
        line: String,
        kept: Int = 1,
    ): String = line.trimEnd('<') + "<".repeat(kept)

    private fun documentOf(parse: ParseResult) = assertIs<ParseResult.Success>(parse).document

    @Test
    fun a_td3_name_line_read_short_is_padded_and_reported() =
        runTest {
            val short = shortTail(td3[0])
            val decoded = assertIs<MrzScanResult.Decoded>(analyse(short, td3[1]))

            assertEquals(MrzFillerPadding(lineIndex = 0, recognizedLength = short.length, paddedLength = 44), decoded.fillerPadding)
            assertEquals(44 - short.length, decoded.fillerPadding?.addedCount)
            // Padding with the trailing filler gives back exactly the generated document.
            assertEquals(documentOf(MrzParser.parse(td3, referenceTime)), documentOf(decoded.parse))
            // The raw text keeps the line as it was read.
            assertEquals(short, decoded.recognizedText.lines[0].text)
        }

    @Test
    fun a_td2_name_line_read_short_is_padded_to_36() =
        runTest {
            val short = shortTail(td2[0], kept = 2)
            val decoded = assertIs<MrzScanResult.Decoded>(analyse(short, td2[1]))

            assertEquals(MrzFillerPadding(0, short.length, 36), decoded.fillerPadding)
            assertEquals(documentOf(MrzParser.parse(td2, referenceTime)), documentOf(decoded.parse))
        }

    @Test
    fun a_chevron_glyph_at_the_end_is_recovered_before_padding() =
        runTest {
            val short = td3[0].trimEnd('<') + "«"
            val decoded = assertIs<MrzScanResult.Decoded>(analyse(short, td3[1]))

            assertEquals(short.length, decoded.fillerPadding?.recognizedLength)
            assertIs<ParseResult.Success>(decoded.parse)
        }

    @Test
    fun full_width_lines_are_not_padded() =
        runTest {
            val decoded = assertIs<MrzScanResult.Decoded>(analyse(td3[0], td3[1]))
            assertNull(decoded.fillerPadding)
        }

    @Test
    fun an_exact_window_wins_over_padding() =
        runTest {
            // A short line that would qualify sits above a full-width MRZ: the exact window is used, nothing padded.
            val decoded = assertIs<MrzScanResult.Decoded>(analyse(shortTail(td2[0]), td3[0], td3[1]))
            assertNull(decoded.fillerPadding)
            assertEquals(documentOf(MrzParser.parse(td3, referenceTime)), documentOf(decoded.parse))
        }

    @Test
    fun line_2_read_short_is_never_padded() =
        runTest {
            assertIs<MrzScanResult.NoMrzFound>(analyse(td3[0], shortTail(td3[1])))
        }

    @Test
    fun a_name_line_that_stops_on_a_letter_is_never_padded() =
        runTest {
            // The end of the name may be missing; padding would shorten the name without a trace.
            val cut = td3[0].trimEnd('<').dropLast(2)
            assertIs<MrzScanResult.NoMrzFound>(analyse(cut, td3[1]))
        }

    @Test
    fun a_name_line_with_no_name_character_is_never_padded() =
        runTest {
            assertIs<MrzScanResult.NoMrzFound>(analyse(td3[0].take(5) + "<<", td3[1]))
            // Too short to hold the code, the state, a name character and a filler.
            assertIs<MrzScanResult.NoMrzFound>(analyse(td3[0].take(5) + "<", td3[1]))
        }

    @Test
    fun a_line_longer_than_its_width_is_never_trimmed() =
        runTest {
            assertIs<MrzScanResult.NoMrzFound>(analyse(td3[0] + "<", td3[1]))
        }

    @Test
    fun a_line_with_a_non_mrz_character_is_never_padded() =
        runTest {
            assertIs<MrzScanResult.NoMrzFound>(analyse(td3[0].trimEnd('<').replaceFirst("<<", "<,") + "<", td3[1]))
        }

    @Test
    fun td1_lines_are_never_padded() =
        runTest {
            assertIs<MrzScanResult.NoMrzFound>(analyse(td1[0], td1[1], shortTail(td1[2])))
            assertIs<MrzScanResult.NoMrzFound>(analyse(shortTail(td1[0]), td1[1], td1[2]))
        }

    @Test
    fun a_stray_line_is_not_padded_onto_a_name_line() =
        runTest {
            // A stray text line ending in `<` above a full-width line 1, with line 2 read short: padding the stray
            // line would pair it with line 1, whose check-digit places hold letters, so nothing is padded.
            val stray = "UTOPIA<CITY<"
            assertIs<MrzScanResult.NoMrzFound>(analyse(stray, td3[0], shortTail(td3[1])))
        }

    @Test
    fun a_line_2_with_one_agreeing_check_digit_is_enough() =
        runTest {
            // Birth date check digit misread: the document number and expiry digits still agree, so line 1 is
            // padded and the parser reports the mismatch.
            val line2 = td3[1].toCharArray().also { it[19] = if (it[19] == '1') '2' else '1' }.concatToString()
            val decoded = assertIs<MrzScanResult.Decoded>(analyse(shortTail(td3[0]), line2))
            assertEquals(44, decoded.fillerPadding?.paddedLength)
            assertIs<ParseResult.PartialSuccess>(decoded.parse)
        }

    @Test
    fun a_line_2_with_no_agreeing_check_digit_is_not_used() =
        runTest {
            val line2 =
                td3[1]
                    .toCharArray()
                    .also { chars ->
                        for (i in listOf(9, 19, 27)) {
                            chars[i] =
                                if (chars[i] ==
                                    '1'
                                ) {
                                    '2'
                                } else {
                                    '1'
                                }
                        }
                    }.concatToString()
            assertIs<MrzScanResult.NoMrzFound>(analyse(shortTail(td3[0]), line2))
        }

    @Test
    fun a_collapsed_double_filler_inside_the_name_cannot_be_told_apart() =
        runTest {
            // The documented limit: `<<` between the surname and given names read as `<` is padded like any short
            // line, and the parser then splits the name differently. The padding is still reported.
            val collapsed = shortTail(td3[0]).replaceFirst("<<", "<")
            val decoded = assertIs<MrzScanResult.Decoded>(analyse(collapsed, td3[1]))

            assertEquals(collapsed.length, decoded.fillerPadding?.recognizedLength)
            val expected = documentOf(MrzParser.parse(td3, referenceTime)).commonFields
            val actual = documentOf(decoded.parse).commonFields
            assertNotEquals(expected.primaryIdentifier, actual.primaryIdentifier)
        }

    @Test
    fun lenient_mode_pads_after_removing_spaces() =
        runTest {
            val short = shortTail(td3[0])
            val spaced = short.substring(0, 5) + " " + short.substring(5)
            val decoded = assertIs<MrzScanResult.Decoded>(analyse(spaced, td3[1], mode = ParsingMode.LENIENT))
            assertEquals(short.length, decoded.fillerPadding?.recognizedLength)
        }

    @Test
    fun tolerant_candidates_carry_the_same_padding_as_the_scan() =
        runTest {
            // An ambiguous glyph in a checked field (`0` read as `O` in the document number) makes the tolerant
            // reader list candidates; the name line is short, so every candidate and the scan carry the padding.
            val short = shortTail(td3[0])
            val line2 = td3[1].replaceFirst('0', 'O')
            val reader =
                SavedImageMrzReader(
                    acknowledgement = SavedImageReadingAcknowledgement(),
                    recognizer =
                        MrzTextRecognizer<FakeFrame> {
                            RecognizedText(
                                listOf(RecognizedLine(short, null), RecognizedLine(line2, null)),
                            )
                        },
                    tolerant = true,
                    telemetry = NoOpTelemetrySink,
                    referenceTimeProvider = { referenceTime },
                )
            val result = reader.read(FakeFrame())
            val expected = MrzFillerPadding(0, short.length, 44)

            assertEquals(expected, assertIs<MrzScanResult.Decoded>(result.scan).fillerPadding)
            assertTrue(result.candidates.isNotEmpty())
            assertTrue(result.candidates.all { it.fillerPadding == expected && it.mrzLines[0].length == 44 })
        }

    @Test
    fun the_constructors_without_padding_report_none() =
        runTest {
            val parse = MrzParser.parse(td3, referenceTime)
            val quality = ScanQuality(mrzRegionFound = true, ocrConfidence = null, recognizedLineCount = 2)
            assertNull(MrzScanResult.Decoded(parse, RecognizedText(emptyList()), quality).fillerPadding)
            assertNull(MrzCandidate(td3, parse, emptyList()).fillerPadding)
        }
}
