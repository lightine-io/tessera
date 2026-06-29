package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.vocabulary.ReadMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // A recognizer whose OCR step fails — the analyse-frame core turns this into a CaptureError.
    private fun recognizerThrowing(): MrzTextRecognizer<FakeImage> = MrzTextRecognizer { throw RuntimeException("OCR boom") }

    // Records whether the reader closed it: SavedImageMrzReader owns an AutoCloseable recognizer's lifetime
    // and must release it on close() (mirroring CameraXMrzScanner).
    private class ClosableRecognizer :
        MrzTextRecognizer<FakeImage>,
        AutoCloseable {
        var closed = false
            private set

        override suspend fun recognize(frame: FakeImage): RecognizedText = RecognizedText(emptyList())

        override fun close() {
            closed = true
        }
    }

    private fun reader(
        recognizer: MrzTextRecognizer<FakeImage>,
        mode: ParsingMode = ParsingMode.STRICT,
        tolerant: Boolean = false,
        metadataReader: CaptureMetadataReader<FakeImage>? = null,
        metadataPolicy: CaptureMetadataPolicy = CaptureMetadataPolicy.NONE,
    ): SavedImageMrzReader<FakeImage> =
        SavedImageMrzReader(
            acknowledgement = SavedImageReadingAcknowledgement(),
            recognizer = recognizer,
            mode = mode,
            tolerant = tolerant,
            metadataReader = metadataReader,
            metadataPolicy = metadataPolicy,
            telemetry = NoOpTelemetrySink,
            referenceTimeProvider = { referenceTime },
        )

    private val sampleMetadata =
        CaptureMetadata(
            reportedCaptureTime = "2026:05:04 12:00:00",
            deviceMake = "ACME",
            deviceModel = "Phone X",
            software = null,
            location = GeoLocation(latitude = 40.0, longitude = -3.0),
            rawTags = mapOf("DateTimeOriginal" to "2026:05:04 12:00:00"),
        )

    // Records the policy it was asked for, so tests can assert the reader forwards it (the platform impl is
    // what actually suppresses GPS under that policy — verified in the platform modules).
    private class RecordingMetadataReader(
        private val metadata: CaptureMetadata,
    ) : CaptureMetadataReader<FakeImage> {
        var calledWith: CaptureMetadataPolicy? = null

        override suspend fun read(
            image: FakeImage,
            policy: CaptureMetadataPolicy,
        ): CaptureMetadata {
            calledWith = policy
            return metadata
        }
    }

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

    @Test
    fun no_metadata_is_read_under_the_default_none_policy() =
        runTest {
            val metadataReader = RecordingMetadataReader(sampleMetadata)

            val result = reader(recognizerReturning(td3Line1, td3Line2), metadataReader = metadataReader).read(FakeImage())

            assertNull(result.captureMetadata, "the default NONE policy must read no metadata")
            assertNull(metadataReader.calledWith, "the metadata reader must not be called under NONE (read-suppression)")
        }

    @Test
    fun the_metadata_policy_is_forwarded_to_the_reader() =
        runTest {
            val metadataReader = RecordingMetadataReader(sampleMetadata)

            reader(
                recognizerReturning(td3Line1, td3Line2),
                metadataReader = metadataReader,
                metadataPolicy = CaptureMetadataPolicy.EXCLUDING_LOCATION,
            ).read(FakeImage())

            // The reader receives the policy so it can suppress GPS at read time (the platform impl honours it).
            assertEquals(CaptureMetadataPolicy.EXCLUDING_LOCATION, metadataReader.calledWith)
        }

    @Test
    fun capture_metadata_is_surfaced_when_requested_with_a_reader() =
        runTest {
            val result =
                reader(
                    recognizerReturning(td3Line1, td3Line2),
                    metadataReader = RecordingMetadataReader(sampleMetadata),
                    metadataPolicy = CaptureMetadataPolicy.INCLUDING_LOCATION,
                ).read(FakeImage())

            val metadata = assertNotNull(result.captureMetadata)
            assertEquals("ACME", metadata.deviceMake)
            assertNotNull(metadata.location, "INCLUDING_LOCATION should surface the GPS the reader returned")
        }

    @Test
    fun requesting_metadata_without_a_reader_surfaces_none() =
        runTest {
            val result =
                reader(recognizerReturning(td3Line1, td3Line2), metadataPolicy = CaptureMetadataPolicy.INCLUDING_LOCATION)
                    .read(FakeImage())

            assertNull(result.captureMetadata, "a non-NONE policy with no reader supplied still reads no metadata")
        }

    @Test
    fun an_ocr_failure_surfaces_a_capture_error() =
        runTest {
            val result = reader(recognizerThrowing()).read(FakeImage())

            val captureError = assertIs<MrzScanResult.CaptureError>(result.scan)
            assertIs<CameraError.OcrFailed>(captureError.error)
            assertTrue(result.candidates.isEmpty())
        }

    @Test
    fun a_tolerant_read_of_a_capture_error_surfaces_no_candidates() =
        runTest {
            // The tolerant path must short-circuit on a capture failure (no recognized text to disambiguate).
            val result = reader(recognizerThrowing(), tolerant = true).read(FakeImage())

            assertIs<MrzScanResult.CaptureError>(result.scan)
            assertTrue(result.candidates.isEmpty(), "a capture failure carries no text, so it yields no candidates")
        }

    @Test
    fun close_releases_a_closeable_recognizer() {
        val recognizer = ClosableRecognizer()
        reader(recognizer).close()
        assertTrue(recognizer.closed, "close() must release an AutoCloseable recognizer")
    }

    @Test
    fun close_is_safe_when_the_recognizer_is_not_closeable() {
        // recognizerReturning is a plain SAM lambda, not AutoCloseable — closing the reader is a no-op, not a throw.
        reader(recognizerReturning(td3Line1, td3Line2)).close()
    }

    // Inserts a stray space after the 5th character — benign OCR whitespace noise that STRICT rejects and
    // LENIENT forgives (mirrors the analyse-frame core's own test).
    private fun withSpace(line: String): String = line.substring(0, 5) + " " + line.substring(5)
}
