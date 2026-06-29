package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.vocabulary.ReadMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
        tolerant: Boolean = false,
    ): SavedImageMrzReader<FakeImage> =
        SavedImageMrzReader(
            acknowledgement = SavedImageReadingAcknowledgement(),
            recognizer = recognizer,
            mode = mode,
            tolerant = tolerant,
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

    @Test
    fun non_tolerant_reads_surface_no_candidates() =
        runTest {
            val result = reader(recognizerReturning(td3Line1, td3Line2), tolerant = false).read(FakeImage())

            assertIs<MrzScanResult.Decoded>(result.scan)
            assertTrue(result.candidates.isEmpty(), "candidates must be empty unless tolerant reading is enabled")
        }

    @Test
    fun tolerant_reading_surfaces_distinct_candidates_including_the_as_recognized_one() =
        runTest {
            val result = reader(recognizerReturning(td3Line1, td3Line2), tolerant = true).read(FakeImage())

            // The strict read is unchanged and still the primary result.
            assertIs<MrzScanResult.Decoded>(result.scan)

            // Candidates surface alongside it. The as-recognized candidate (no disambiguations) is present and
            // parses as the valid specimen; every candidate is stamped with saved-image provenance, and
            // candidate MRZ lines are distinct (no duplicates).
            assertTrue(result.candidates.isNotEmpty(), "tolerant reading should surface candidates for an MRZ with ambiguous glyphs")
            val asRecognized = result.candidates.singleOrNull { it.disambiguations.isEmpty() }
            assertIs<ParseResult.Success>(
                asRecognized?.parse,
                "the as-recognized reading should be surfaced and parse as the valid specimen",
            )
            assertTrue(result.candidates.any { it.disambiguations.isNotEmpty() }, "at least one disambiguated alternative should surface")
            result.candidates.forEach { candidate ->
                assertEquals(ReadMethod.PRE_CAPTURED_IMAGE, candidate.parse.metadata.readMethod)
                // Every recorded disambiguation must actually differ (recognized != resolved) at a real position.
                candidate.disambiguations.forEach { d ->
                    assertTrue(d.recognized != d.resolved)
                    assertEquals(d.resolved, candidate.mrzLines[d.lineIndex][d.columnIndex])
                }
            }
            assertEquals(
                result.candidates
                    .map { it.mrzLines }
                    .distinct()
                    .size,
                result.candidates.size,
                "candidates must be distinct",
            )
        }

    @Test
    fun tolerant_reading_of_an_image_without_an_mrz_surfaces_no_candidates() =
        runTest {
            val result = reader(recognizerReturning("UTOPIA", "JUST SOME PRINTED TEXT"), tolerant = true).read(FakeImage())

            assertIs<MrzScanResult.NoMrzFound>(result.scan)
            assertTrue(result.candidates.isEmpty(), "no shape-matched run means no candidates")
        }

    // Inserts a stray space after the 5th character — benign OCR whitespace noise that STRICT rejects and
    // LENIENT forgives (mirrors the analyse-frame core's own test).
    private fun withSpace(line: String): String = line.substring(0, 5) + " " + line.substring(5)
}
