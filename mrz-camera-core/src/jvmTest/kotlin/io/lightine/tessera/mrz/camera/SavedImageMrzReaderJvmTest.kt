package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.generation.GenerationResult
import io.lightine.tessera.mrz.generation.MrzGenerator
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.vocabulary.ReadMethod
import io.lightine.tessera.types.vocabulary.Sex
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * JVM-only proof of the desktop flow: a Java app reads an MRZ from a saved image by supplying its
 * own [MrzTextRecognizer]. The recognized text is synthetic, produced by the SDK's own
 * [MrzGenerator], never real document data. The frame type is [Path] - the JVM type a desktop app
 * uses for an image file - which is why this test lives in jvmTest rather than commonTest.
 *
 * [RecognizedLine] carries text and confidence, no coordinates: the fake recognizer returns one
 * line per physical row of text in reading order, and the analyzer groups MRZ lines from that order.
 */
class SavedImageMrzReaderJvmTest {
    private val referenceTime = Instant.parse("2026-05-04T12:00:00Z")

    // Synthetic TD3 lines from the SDK's generator (the project's fictional specimen, not real data).
    private val generatedLines: List<String> =
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
            ),
        ).mrz

    // A fake recognizer standing in for a desktop app's own OCR engine: it ignores the file at the
    // path and returns the generated lines. A real app would call its engine here and map its output
    // to RecognizedLine.
    private fun recognizerReturning(lines: List<String>): MrzTextRecognizer<Path> =
        MrzTextRecognizer {
            RecognizedText(lines.map { RecognizedLine(it, confidence = 1f) })
        }

    @Test
    fun a_jvm_app_reads_a_document_with_its_own_recognizer() =
        runTest {
            val reader =
                SavedImageMrzReader(
                    acknowledgement = SavedImageReadingAcknowledgement(),
                    recognizer = recognizerReturning(generatedLines),
                    telemetry = NoOpTelemetrySink,
                    referenceTimeProvider = { referenceTime },
                )

            val result = reader.read(Path.of("passport-page.png"))

            // The reader returns the document parsed from the generated lines, stamped with the
            // saved-image reading method, and preserves the raw recognized text verbatim.
            val decoded = assertIs<MrzScanResult.Decoded>(result.scan)
            assertIs<ParseResult.Success>(decoded.parse)
            assertEquals(ReadMethod.PRE_CAPTURED_IMAGE, decoded.parse.metadata.readMethod)
            assertEquals(generatedLines, decoded.recognizedText.lines.map { it.text })
        }
}
