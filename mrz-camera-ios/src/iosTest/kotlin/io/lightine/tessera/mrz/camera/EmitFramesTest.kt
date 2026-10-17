package io.lightine.tessera.mrz.camera

import io.lightine.tessera.telemetry.NoOpTelemetrySink
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [emitFrames], the frame hand-off behind [AVCaptureMrzScanner]'s camera stream (TES-219): the stream always
 * ends when the capture session closes the frame channel, and every frame is released exactly once.
 */
class EmitFramesTest {
    private class Frame {
        val releases = AtomicInt(0)

        fun release() {
            releases.incrementAndGet()
        }
    }

    @Test
    fun aSessionErrorEndsTheScanWithACaptureErrorAndReleasesEveryFrame() =
        runBlocking {
            val sent = List(2) { Frame() }
            val results = scanUntilClosed(sent, IllegalStateException("the capture session reported a runtime error"))

            assertNotNull(results, "the scan ends instead of waiting forever")
            assertEquals(3, results.size, "one result per frame, then the capture error")
            val error = assertIs<MrzScanResult.CaptureError>(results.last()).error
            assertEquals(CameraError.CameraUnavailable("the capture session reported a runtime error"), error)
            assertTrue(sent.all { it.releases.value == 1 }, "every frame is released exactly once")
        }

    @Test
    fun aSessionEndedByAForeignCancellationEndsTheScanWithACaptureError() =
        runBlocking {
            // A CancellationException that is not the collector's own.
            val sent = List(1) { Frame() }
            val results = scanUntilClosed(sent, CancellationException("the capture session was cancelled"))

            assertNotNull(results, "the scan ends instead of waiting forever")
            val error = assertIs<MrzScanResult.CaptureError>(results.last()).error
            assertEquals(CameraError.CameraUnavailable("the capture session was cancelled"), error)
            assertTrue(sent.all { it.releases.value == 1 }, "every frame is released exactly once")
        }

    @Test
    fun aFrameStillBufferedWhenTheStreamStopsIsReleased() =
        runBlocking {
            val frames = Channel<Frame>(capacity = 1, onUndeliveredElement = Frame::release)
            val first = Frame()
            val buffered = Frame()
            frames.send(first)
            // The collector stops at the first frame, while the second is still in the channel.
            assertFailsWith<StopCollecting> {
                flow { emitFrames(frames, Frame::release) }.collect {
                    frames.send(buffered)
                    throw StopCollecting()
                }
            }

            assertEquals(1, first.releases.value, "the emitted frame is released once")
            assertEquals(1, buffered.releases.value, "the buffered frame is released once")
        }

    private class StopCollecting : Exception()

    // Sends [sent] into a frame channel, then closes it with [cause], as the capture session's runtime-error
    // observer does, and scans the stream to its end (null if it never ends).
    private suspend fun scanUntilClosed(
        sent: List<Frame>,
        cause: Throwable,
    ): List<MrzScanResult>? {
        val frames = Channel<Frame>(capacity = sent.size, onUndeliveredElement = Frame::release)
        sent.forEach { frames.send(it) }
        frames.close(cause)
        val analyzer =
            MrzFrameAnalyzer<Frame>(recognizer = { RecognizedText(emptyList()) }, telemetry = NoOpTelemetrySink)
        return withTimeoutOrNull(WAIT_MILLIS) {
            analyzer
                .scan(
                    flow { emitFrames(frames, Frame::release) },
                    captureErrorFor = { CameraError.CameraUnavailable(it.message ?: it.toString()) },
                ).toList()
        }
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
