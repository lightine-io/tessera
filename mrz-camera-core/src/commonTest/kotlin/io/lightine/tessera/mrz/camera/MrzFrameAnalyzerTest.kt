package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.generation.GenerationResult
import io.lightine.tessera.mrz.generation.MrzGenerator
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.telemetry.TelemetrySinkRegistry
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.ReadMethod
import io.lightine.tessera.types.vocabulary.Sex
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Host tests for [MrzFrameAnalyzer] — the analyse-frame core — driven by a mock [MrzTextRecognizer]
 * (no device, no real OCR). Synthetic MRZ data only: the TD3/TD2 lines are the ICAO Doc 9303 Utopia
 * specimen, and the TD1 lines are produced by the SDK's own generator.
 */
class MrzFrameAnalyzerTest {
    private val referenceTime = Instant.parse("2026-05-04T12:00:00Z")

    // ICAO Doc 9303 Utopia specimen (synthetic).
    private val td3Line1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    private val td3Line2 = "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"
    private val td2Line1 = "I<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<"
    private val td2Line2 = "D231458907UTO6908061F3008063<<<<<<<4"

    // --- An opaque stand-in for a platform frame: the core never inspects it, only forwards it. ---
    private class FakeFrame

    private fun recognizerReturning(text: RecognizedText): MrzTextRecognizer<FakeFrame> = MrzTextRecognizer { text }

    private fun textOf(
        vararg lines: String,
        confidence: Float? = null,
    ): RecognizedText = RecognizedText(lines.map { RecognizedLine(it, confidence) })

    private fun analyzer(
        recognizer: MrzTextRecognizer<FakeFrame>,
        mode: ParsingMode = ParsingMode.STRICT,
        telemetry: TelemetrySink = io.lightine.tessera.telemetry.NoOpTelemetrySink,
    ): MrzFrameAnalyzer<FakeFrame> =
        MrzFrameAnalyzer(
            recognizer = recognizer,
            mode = mode,
            telemetry = telemetry,
            referenceTimeProvider = { referenceTime },
        )

    private class RecordingSink : TelemetrySink {
        val events: MutableList<TelemetryEvent> = mutableListOf()

        override fun record(event: TelemetryEvent) {
            events.add(event)
        }
    }

    @Test
    fun decodes_a_well_formed_td3_frame() =
        runTest {
            val result = analyzer(recognizerReturning(textOf(td3Line1, td3Line2))).analyse(FakeFrame())

            val decoded = assertIs<MrzScanResult.Decoded>(result)
            assertIs<ParseResult.Success>(decoded.parse)
            assertTrue(decoded.quality.mrzRegionFound)
            assertEquals(2, decoded.quality.recognizedLineCount)
        }

    @Test
    fun ignores_surrounding_visual_zone_text() =
        runTest {
            // Real OCR returns the printed name/country lines above the MRZ; only the two 44-char
            // lines form the candidate.
            val recognizer = recognizerReturning(textOf("UTOPIA", "ERIKSSON, ANNA MARIA", td3Line1, td3Line2))

            val decoded = assertIs<MrzScanResult.Decoded>(analyzer(recognizer).analyse(FakeFrame()))
            assertIs<ParseResult.Success>(decoded.parse)
            assertEquals(4, decoded.quality.recognizedLineCount)
        }

    @Test
    fun decodes_a_second_format_to_prove_length_generic_extraction() =
        runTest {
            val decoded = assertIs<MrzScanResult.Decoded>(analyzer(recognizerReturning(textOf(td2Line1, td2Line2))).analyse(FakeFrame()))
            val parsed = assertIs<ParseResult.Success>(decoded.parse)
            assertEquals(MrzFormat.TD2, parsed.document.format)
        }

    @Test
    fun decodes_a_three_line_td1_frame() =
        runTest {
            val td1 = generatedTd1Lines()
            assertEquals(3, td1.size)

            val decoded =
                assertIs<MrzScanResult.Decoded>(
                    analyzer(recognizerReturning(RecognizedText(td1.map { RecognizedLine(it, null) }))).analyse(FakeFrame()),
                )
            val parsed = assertIs<ParseResult.Success>(decoded.parse)
            assertEquals(MrzFormat.TD1, parsed.document.format)
        }

    @Test
    fun a_frame_without_an_mrz_yields_no_mrz_found() =
        runTest {
            val result = analyzer(recognizerReturning(textOf("UTOPIA", "JUST SOME PRINTED TEXT"))).analyse(FakeFrame())

            val noMrz = assertIs<MrzScanResult.NoMrzFound>(result)
            assertFalse(noMrz.quality.mrzRegionFound)
            assertEquals(2, noMrz.quality.recognizedLineCount)
        }

    @Test
    fun an_ocr_failure_yields_a_capture_error() =
        runTest {
            val recognizer = MrzTextRecognizer<FakeFrame> { throw IllegalStateException("engine exploded") }

            val result = analyzer(recognizer).analyse(FakeFrame())

            val captureError = assertIs<MrzScanResult.CaptureError>(result)
            val ocrFailed = assertIs<CameraError.OcrFailed>(captureError.error)
            assertEquals("camera.ocr_failed", ocrFailed.code)
            assertEquals("engine exploded", ocrFailed.message)
            assertFalse(captureError.quality.mrzRegionFound)
            assertEquals(0, captureError.quality.recognizedLineCount)
        }

    @Test
    fun strict_mode_rejects_an_mrz_with_internal_spaces() =
        runTest {
            val result = analyzer(recognizerReturning(textOf(withSpace(td3Line1), withSpace(td3Line2)))).analyse(FakeFrame())

            assertIs<MrzScanResult.NoMrzFound>(result)
        }

    @Test
    fun lenient_mode_accepts_an_mrz_with_internal_spaces() =
        runTest {
            val recognizer = recognizerReturning(textOf(withSpace(td3Line1), withSpace(td3Line2)))

            val decoded = assertIs<MrzScanResult.Decoded>(analyzer(recognizer, mode = ParsingMode.LENIENT).analyse(FakeFrame()))
            assertIs<ParseResult.Success>(decoded.parse)
        }

    @Test
    fun lowercase_ocr_is_case_folded_before_parsing() =
        runTest {
            val recognizer = recognizerReturning(textOf(td3Line1.lowercase(), td3Line2.lowercase()))

            val decoded = assertIs<MrzScanResult.Decoded>(analyzer(recognizer).analyse(FakeFrame()))
            assertIs<ParseResult.Success>(decoded.parse)
        }

    @Test
    fun surfaces_the_parser_verdict_unchanged_without_gating() =
        runTest {
            // Corrupt the composite check digit (last char of TD3 line 2): mrz-core parses the document
            // but flags the failed composite check — a PartialSuccess. The analyzer must surface that
            // verdict verbatim, neither hiding the data nor "fixing" it (reader, not oracle).
            val corruptedLine2 = td3Line2.dropLast(1) + "5"
            val candidate = listOf(td3Line1, corruptedLine2)

            val decoded =
                assertIs<MrzScanResult.Decoded>(analyzer(recognizerReturning(textOf(td3Line1, corruptedLine2))).analyse(FakeFrame()))

            val partial = assertIs<ParseResult.PartialSuccess>(decoded.parse)
            val expected = assertIs<ParseResult.PartialSuccess>(MrzParser.parse(candidate, referenceTime))
            // The verdict is surfaced verbatim — only the read-method provenance differs (live camera).
            assertEquals(expected.document, partial.document)
            assertEquals(expected.metadata.validationFailures, partial.metadata.validationFailures)
            assertEquals(expected.metadata.warnings, partial.metadata.warnings)
            assertEquals(ReadMethod.LIVE_CAMERA, partial.metadata.readMethod)
        }

    @Test
    fun decoded_results_report_live_camera_provenance() =
        runTest {
            // The parser alone sees a string and reports BACKEND_STRING_INPUT; the camera core knows the
            // frame came from a live camera and stamps that provenance honestly (Principle 5).
            val decoded = assertIs<MrzScanResult.Decoded>(analyzer(recognizerReturning(textOf(td3Line1, td3Line2))).analyse(FakeFrame()))
            assertEquals(ReadMethod.LIVE_CAMERA, decoded.parse.metadata.readMethod)
        }

    @Test
    fun exposes_the_raw_recognized_text_on_the_result() =
        runTest {
            val recognized = textOf("UTOPIA", td3Line1, td3Line2)

            val decoded = assertIs<MrzScanResult.Decoded>(analyzer(recognizerReturning(recognized)).analyse(FakeFrame()))
            assertEquals(recognized, decoded.recognizedText)
        }

    @Test
    fun aggregates_per_line_confidence_when_reported() =
        runTest {
            // 0.5 and 1.0 are exactly representable, and so is their mean (0.75) — no float-rounding slack.
            val recognized =
                RecognizedText(
                    listOf(RecognizedLine(td3Line1, 0.5f), RecognizedLine(td3Line2, 1.0f)),
                )

            val decoded = assertIs<MrzScanResult.Decoded>(analyzer(recognizerReturning(recognized)).analyse(FakeFrame()))
            val confidence = assertNotNull(decoded.quality.ocrConfidence)
            assertEquals(0.75f, confidence, absoluteTolerance = 1e-6f)
        }

    @Test
    fun confidence_is_null_when_the_engine_reports_none() =
        runTest {
            val decoded = assertIs<MrzScanResult.Decoded>(analyzer(recognizerReturning(textOf(td3Line1, td3Line2))).analyse(FakeFrame()))
            assertNull(decoded.quality.ocrConfidence)
        }

    @Test
    fun forwards_the_exact_frame_to_the_recognizer() =
        runTest {
            val frame = FakeFrame()
            var received: FakeFrame? = null
            val analyzer =
                analyzer(
                    MrzTextRecognizer {
                        received = it
                        textOf()
                    },
                )

            analyzer.analyse(frame)

            assertSame(frame, received)
        }

    @Test
    fun emits_one_frame_event_per_analyse_carrying_no_document_data() =
        runTest {
            val sink = RecordingSink()

            analyzer(recognizerReturning(textOf("UTOPIA", td3Line1, td3Line2)), telemetry = sink).analyse(FakeFrame())

            assertEquals(1, sink.events.size)
            val event = assertIs<CameraFrameEvent>(sink.events.single())
            assertEquals("mrz.camera.frame", event.name)
            assertEquals(CameraFrameOutcome.DECODED, event.outcome)
            assertEquals(MrzFormat.TD3, event.detectedFormat)
            assertTrue(event.mrzRegionFound)
            assertEquals(3, event.recognizedLineCount)
            // No document data must leak into telemetry (PII guard).
            val rendered = event.toString()
            assertFalse(rendered.contains("ERIKSSON"), "telemetry leaked the name field")
            assertFalse(rendered.contains("L898902"), "telemetry leaked the document number")
        }

    @Test
    fun defaults_telemetry_to_the_registered_sink_when_none_is_passed() =
        runTest {
            // The documented contract: register a sink at startup and SDK emitters route to it
            // (telemetry.md / ADR-015). Constructing the analyzer WITHOUT a telemetry argument must
            // therefore default to TelemetrySinkRegistry.current, not a private no-op.
            val registered = RecordingSink()
            TelemetrySinkRegistry.register(registered)
            try {
                MrzFrameAnalyzer(
                    recognizer = recognizerReturning(textOf("UTOPIA", td3Line1, td3Line2)),
                    referenceTimeProvider = { referenceTime },
                ).analyse(FakeFrame())

                assertEquals(1, registered.events.size, "the registered sink should receive the frame event by default")
                assertIs<CameraFrameEvent>(registered.events.single())
            } finally {
                TelemetrySinkRegistry.reset()
            }
        }

    @Test
    fun telemetry_outcome_reflects_no_mrz_and_ocr_failure() =
        runTest {
            val noMrzSink = RecordingSink()
            analyzer(recognizerReturning(textOf("PRINTED TEXT ONLY")), telemetry = noMrzSink).analyse(FakeFrame())
            val noMrzEvent = assertIs<CameraFrameEvent>(noMrzSink.events.single())
            assertEquals(CameraFrameOutcome.NO_MRZ_FOUND, noMrzEvent.outcome)
            assertNull(noMrzEvent.detectedFormat)

            val failureSink = RecordingSink()
            analyzer(MrzTextRecognizer { throw RuntimeException("boom") }, telemetry = failureSink).analyse(FakeFrame())
            val failureEvent = assertIs<CameraFrameEvent>(failureSink.events.single())
            assertEquals(CameraFrameOutcome.OCR_FAILED, failureEvent.outcome)
        }

    @Test
    fun an_empty_ocr_result_yields_no_mrz_found() =
        runTest {
            val result = analyzer(recognizerReturning(RecognizedText(emptyList()))).analyse(FakeFrame())

            val noMrz = assertIs<MrzScanResult.NoMrzFound>(result)
            assertFalse(noMrz.quality.mrzRegionFound)
            assertEquals(0, noMrz.quality.recognizedLineCount)
            assertNull(noMrz.quality.ocrConfidence)
        }

    @Test
    fun many_uniform_non_mrz_lines_yield_no_mrz_found() =
        runTest {
            // A long run of equal-length lines that is not a valid MRZ length exercises the run-walk
            // fully without matching any shape.
            val recognized = RecognizedText(List(12) { RecognizedLine("ABCDE", null) })

            assertIs<MrzScanResult.NoMrzFound>(analyzer(recognizerReturning(recognized)).analyse(FakeFrame()))
        }

    @Test
    fun a_single_mrz_length_line_is_not_a_candidate() =
        runTest {
            // One 44-char line is not a TD3/MRV-A candidate (those need two), so the run (size 1) matches
            // no shape — proves line count matters, not just line length.
            assertIs<MrzScanResult.NoMrzFound>(analyzer(recognizerReturning(textOf(td3Line1))).analyse(FakeFrame()))
        }

    // Inserts a stray space after the 5th character — benign OCR whitespace noise that STRICT rejects
    // (wrong length) and LENIENT forgives.
    private fun withSpace(line: String): String = line.substring(0, 5) + " " + line.substring(5)

    private fun generatedTd1Lines(): List<String> {
        val result =
            MrzGenerator.generateTD1(
                documentType = "I",
                issuingState = "UTO",
                documentNumber = "D23145890",
                primaryIdentifier = "ERIKSSON",
                secondaryIdentifier = "ANNA MARIA",
                nationality = "UTO",
                dateOfBirth = LocalDate(1969, 8, 6),
                sex = Sex.FEMALE,
                dateOfExpiry = LocalDate(2030, 8, 6),
                optionalData1 = "",
                optionalData2 = "",
            )
        return assertIs<GenerationResult.Success>(result).mrz
    }
}
