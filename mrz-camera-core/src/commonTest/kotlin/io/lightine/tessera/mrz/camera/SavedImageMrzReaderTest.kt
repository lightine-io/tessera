package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.vocabulary.ReadMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Host tests for [SavedImageMrzReader] — the saved-image convenience — driven by a mock
 * [MrzTextRecognizer] (no device, no real OCR). Synthetic MRZ only: the TD3 lines are the ICAO Doc 9303
 * Utopia specimen. The reader wraps the analyse-frame core, so these assert the saved-image-specific
 * contract: PRE_CAPTURED_IMAGE provenance, the wrapped result, and that mode is forwarded.
 */
class SavedImageMrzReaderTest {
    private val referenceTime = Instant.parse("2026-05-04T12:00:00Z")

    private val td3Line1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    private val td3Line2 = "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"

    // An opaque stand-in for a platform saved-image reference (e.g. a Uri / NSURL); the core only forwards it.
    private class FakeImage

    private fun recognizerReturning(vararg lines: String): MrzTextRecognizer<FakeImage> =
        MrzTextRecognizer { RecognizedText(lines.map { RecognizedLine(it, null) }) }

    private fun reader(
        recognizer: MrzTextRecognizer<FakeImage>,
        mode: ParsingMode = ParsingMode.STRICT,
    ): SavedImageMrzReader<FakeImage> =
        SavedImageMrzReader(
            acknowledgement = SavedImageReadingAcknowledgement(),
            recognizer = recognizer,
            mode = mode,
            telemetry = NoOpTelemetrySink,
            referenceTimeProvider = { referenceTime },
        )

    @Test
    fun reads_a_saved_image_and_stamps_pre_captured_image_provenance() =
        runTest {
            val result = reader(recognizerReturning(td3Line1, td3Line2)).read(FakeImage())

            val decoded = assertIs<MrzScanResult.Decoded>(result.scan)
            assertIs<ParseResult.Success>(decoded.parse)
            // The whole point of the reader: a saved image is stamped PRE_CAPTURED_IMAGE, never LIVE_CAMERA.
            assertEquals(ReadMethod.PRE_CAPTURED_IMAGE, decoded.parse.metadata.readMethod)
        }

    @Test
    fun an_image_without_an_mrz_yields_no_mrz_found() =
        runTest {
            val result = reader(recognizerReturning("UTOPIA", "JUST SOME PRINTED TEXT")).read(FakeImage())

            assertIs<MrzScanResult.NoMrzFound>(result.scan)
        }

    @Test
    fun parsing_mode_is_forwarded_strict_rejects_a_spaced_mrz_lenient_accepts_it() =
        runTest {
            val spaced1 = withSpace(td3Line1)
            val spaced2 = withSpace(td3Line2)

            // STRICT (default): the stray space makes the line the wrong length, so no candidate is found.
            assertIs<MrzScanResult.NoMrzFound>(reader(recognizerReturning(spaced1, spaced2)).read(FakeImage()).scan)

            // LENIENT: benign whitespace is forgiven, so the same image decodes.
            val lenient = reader(recognizerReturning(spaced1, spaced2), mode = ParsingMode.LENIENT).read(FakeImage())
            val decoded = assertIs<MrzScanResult.Decoded>(lenient.scan)
            assertIs<ParseResult.Success>(decoded.parse)
            assertEquals(ReadMethod.PRE_CAPTURED_IMAGE, decoded.parse.metadata.readMethod)
        }

    // Inserts a stray space after the 5th character — benign OCR whitespace noise that STRICT rejects and
    // LENIENT forgives (mirrors the analyse-frame core's own test).
    private fun withSpace(line: String): String = line.substring(0, 5) + " " + line.substring(5)
}
