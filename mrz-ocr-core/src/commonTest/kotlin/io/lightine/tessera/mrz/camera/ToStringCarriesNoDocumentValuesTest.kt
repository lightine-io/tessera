package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Every public type of this module that holds document values prints shape, never the values.
 *
 * Each instance is built from the project's specimen data (the SDK's own synthetic person, never a
 * real document), and each string it prints must contain none of the specimen's values: a name
 * part, the document number, a date, the personal number, or a raw line.
 */
class ToStringCarriesNoDocumentValuesTest {
    private val referenceTime = Instant.parse("2026-05-04T12:00:00Z")

    // The specimen, the same TD3 lines the module's other tests use.
    private val specimenLine1 = "P<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<"
    private val specimenLine2 = "T220001293UTO9003152F3403141S98765432<<<<<20"
    private val specimenLines = listOf(specimenLine1, specimenLine2)

    private val forbidden: List<String> =
        listOf(
            "SPECIMEN",
            "ALEX",
            "MORGAN",
            "T22000129",
            "S98765432",
            "900315",
            "340314",
            "UTO",
            specimenLine1,
            specimenLine2,
        )

    @Test
    fun scan_results_candidates_and_saved_images_print_no_document_values() {
        val recognizedText =
            RecognizedText(
                listOf(
                    RecognizedLine(specimenLine1, null),
                    RecognizedLine(specimenLine2, 0.9f),
                ),
            )
        val parse = MrzParser.parseTD3(specimenLines, referenceTime = referenceTime)
        assertIs<ParseResult.Success>(parse)
        val quality = ScanQuality(mrzRegionFound = true, ocrConfidence = 0.9f, recognizedLineCount = 2)
        val decoded = MrzScanResult.Decoded(parse, recognizedText, quality)
        val noMrz = MrzScanResult.NoMrzFound(recognizedText, quality)
        val captureError = MrzScanResult.CaptureError(CameraError.OcrFailed("the engine failed"), quality)
        val candidate =
            MrzCandidate(
                mrzLines = specimenLines,
                parse = parse,
                disambiguations = listOf(CharacterDisambiguation(lineIndex = 0, columnIndex = 8, recognized = '9', resolved = '8')),
                fillerPadding = MrzFillerPadding(lineIndex = 0, recognizedLength = 40, paddedLength = 44),
            )
        val confirmed = ConsensusVerdict.Confirmed(decoded)
        val savedImage = SavedImageScanResult(decoded, listOf(candidate), captureMetadata = null)

        val rendered =
            listOf(
                recognizedText.toString(),
                recognizedText.lines[0].toString(),
                recognizedText.lines[1].toString(),
                decoded.toString(),
                noMrz.toString(),
                captureError.toString(),
                candidate.toString(),
                confirmed.toString(),
                savedImage.toString(),
            )
        rendered.forEach { string -> assertCarriesNoneOfTheSpecimenValues(string) }
    }

    @Test
    fun to_string_shows_only_shape() {
        val recognizedText = RecognizedText(listOf(RecognizedLine(specimenLine1, null)))
        assertEquals("RecognizedText(lines=1)", recognizedText.toString())
        assertEquals("RecognizedLine(length=44, confidence=null)", recognizedText.lines[0].toString())
        val noMrz =
            MrzScanResult.NoMrzFound(
                recognizedText,
                ScanQuality(mrzRegionFound = true, ocrConfidence = null, recognizedLineCount = 1),
            )
        assertEquals(
            "NoMrzFound(lines=1, quality=ScanQuality(mrzRegionFound=true, ocrConfidence=null, recognizedLineCount=1))",
            noMrz.toString(),
        )
        assertEquals(
            "Confirmed",
            ConsensusVerdict
                .Confirmed(
                    MrzScanResult.Decoded(
                        MrzParser.parseTD3(specimenLines, referenceTime = referenceTime),
                        recognizedText,
                        ScanQuality(mrzRegionFound = true, ocrConfidence = null, recognizedLineCount = 2),
                    ),
                ).toString(),
        )
    }

    private fun assertCarriesNoneOfTheSpecimenValues(rendered: String) {
        forbidden.forEach { value ->
            assertFalse(value in rendered, "toString carries the specimen value '$value': $rendered")
        }
    }
}
