package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.parsing.ParseResult
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Host tests for the streaming layer of camera reading — the [MrzFrameAnalyzer.scan] engine that the
 * owns-the-camera-session [MrzCameraScanner]s build on — and for the capture-availability members of the
 * [CameraError] family. Driven by a mock [MrzTextRecognizer] over fake frames (no device, no real OCR),
 * exactly as the analyse-frame core's tests are. Synthetic MRZ only (the project's specimen).
 */
class MrzScanTest {
    private val referenceTime = Instant.parse("2026-05-04T12:00:00Z")

    private val td3Line1 = "P<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<"
    private val td3Line2 = "T220001293UTO9003152F3403141S98765432<<<<<20"

    // An opaque, identifiable stand-in for a platform frame; the engine forwards it and releases it but
    // never inspects it.
    private data class FakeFrame(
        val id: Int,
    )

    private fun analyzer(recognizer: MrzTextRecognizer<FakeFrame>): MrzFrameAnalyzer<FakeFrame> =
        MrzFrameAnalyzer(recognizer = recognizer, referenceTimeProvider = { referenceTime })

    // A recognizer that always reads a well-formed TD3 MRZ, regardless of which frame it is handed.
    private val td3Recognizer =
        MrzTextRecognizer<FakeFrame> {
            RecognizedText(listOf(RecognizedLine(td3Line1, null), RecognizedLine(td3Line2, null)))
        }

    @Test
    fun scan_emits_one_result_per_frame_in_order() =
        runTest {
            val frames = flowOf(FakeFrame(1), FakeFrame(2), FakeFrame(3))

            val results = analyzer(td3Recognizer).scan(frames).toList()

            assertEquals(3, results.size)
            assertTrue(results.all { it is MrzScanResult.Decoded })
        }

    @Test
    fun scan_releases_each_frame_after_it_is_analysed() =
        runTest {
            // A single ordered log proves each frame is analysed and then released before the next frame.
            val log = mutableListOf<String>()
            val recognizer =
                MrzTextRecognizer<FakeFrame> { frame ->
                    log.add("analysed:${frame.id}")
                    RecognizedText(listOf(RecognizedLine(td3Line1, null), RecognizedLine(td3Line2, null)))
                }

            analyzer(recognizer).scan(flowOf(FakeFrame(1), FakeFrame(2)), releaseFrame = { log.add("released:${it.id}") }).toList()

            assertEquals(listOf("analysed:1", "released:1", "analysed:2", "released:2"), log)
        }

    @Test
    fun scan_releases_the_frame_even_when_no_mrz_is_found() =
        runTest {
            val released = mutableListOf<FakeFrame>()
            val noMrz = MrzTextRecognizer<FakeFrame> { RecognizedText(listOf(RecognizedLine("JUST PRINTED TEXT", null))) }

            val results = analyzer(noMrz).scan(flowOf(FakeFrame(7)), releaseFrame = released::add).toList()

            assertIs<MrzScanResult.NoMrzFound>(results.single())
            assertEquals(listOf(FakeFrame(7)), released)
        }

    @Test
    fun scan_surfaces_a_capture_failure_as_a_sealed_result_without_throwing() =
        runTest {
            // The frame source itself fails (the camera could not start). scan must surface this as a
            // terminal CaptureError on the same stream — not throw (ADR-020).
            val failingSource = flow<FakeFrame> { throw IllegalStateException("camera could not be opened") }

            val results =
                analyzer(td3Recognizer)
                    .scan(failingSource, captureErrorFor = { CameraError.CameraUnavailable(it.message ?: "?") })
                    .toList()

            val captureError = assertIs<MrzScanResult.CaptureError>(results.single())
            val unavailable = assertIs<CameraError.CameraUnavailable>(captureError.error)
            assertEquals("camera could not be opened", unavailable.message)
            assertEquals("camera.unavailable", unavailable.code)
            // A capture failure carries the no-frame quality, never gating (mrzRegionFound = false).
            assertEquals(false, captureError.quality.mrzRegionFound)
        }

    @Test
    fun scan_rethrows_a_source_failure_the_mapper_does_not_claim() =
        runTest {
            // Default mapper returns null → "not a capture error" → the cause propagates unchanged, so a
            // bare scan(frames) keeps ordinary Flow exception semantics.
            val failingSource = flow<FakeFrame> { throw IllegalStateException("unexpected") }

            assertFailsWith<IllegalStateException> {
                analyzer(td3Recognizer).scan(failingSource).toList()
            }
        }

    @Test
    fun scan_surfaces_each_capture_availability_member() =
        runTest {
            // Drives the production of each new CameraError member through the scan surfacing path, and
            // asserts its stable code (the "test that produces every new error type" rule).
            val members =
                listOf(
                    CameraError.CameraUnavailable("no camera") to "camera.unavailable",
                    CameraError.PermissionDenied("not granted") to "camera.permission_denied",
                    CameraError.CameraInUse("held elsewhere") to "camera.in_use",
                )

            for ((member, expectedCode) in members) {
                val source = flow<FakeFrame> { throw RuntimeException("boom") }
                val result = analyzer(td3Recognizer).scan(source, captureErrorFor = { member }).toList().single()

                val captureError = assertIs<MrzScanResult.CaptureError>(result)
                assertEquals(member, captureError.error)
                assertEquals(expectedCode, captureError.error.code)
            }
        }

    @Test
    fun capture_availability_error_codes_are_stable() =
        runTest {
            assertEquals("camera.unavailable", CameraError.CameraUnavailable("x").code)
            assertEquals("camera.permission_denied", CameraError.PermissionDenied("x").code)
            assertEquals("camera.in_use", CameraError.CameraInUse("x").code)
        }

    @Test
    fun scan_propagates_cancellation_rather_than_surfacing_it_as_a_capture_error() =
        runTest {
            val collected = mutableListOf<MrzScanResult>()
            // One real frame, then suspend forever so the timeout cancels the collection mid-stream.
            val source =
                flow {
                    emit(FakeFrame(1))
                    awaitCancellation()
                }
            // A mapper that would claim ANY throwable as a capture error: if the timeout's cancellation
            // were (wrongly) routed through scan's catch block, it would surface here as a CaptureError.
            // runTest's virtual clock fast-forwards the timeout, so there is no real wait.
            val finished =
                withTimeoutOrNull(1_000) {
                    analyzer(td3Recognizer)
                        .scan(source, captureErrorFor = { CameraError.CameraUnavailable("cancellation must not reach here") })
                        .collect(collected::add)
                }

            assertNull(finished, "collection should have been cancelled by the timeout, not completed")
            // The one real frame decoded; cancellation tore the collection down without becoming a result.
            assertIs<MrzScanResult.Decoded>(collected.single())
        }

    @Test
    fun scan_drives_an_arbitrary_non_camera_frame_source() =
        runTest {
            // The owns-session contract is frame-source-agnostic: the same engine an iOS sample-buffer
            // stream or a USB reader would feed. A plain non-camera Flow proves the shape holds without
            // any platform camera, which is the host-side evidence for the iOS contract validation.
            val results = analyzer(td3Recognizer).scan(flowOf(FakeFrame(1), FakeFrame(2))).toList()

            assertEquals(2, results.size)
            results.forEach { assertIs<ParseResult.Success>(assertIs<MrzScanResult.Decoded>(it).parse) }
        }
}
