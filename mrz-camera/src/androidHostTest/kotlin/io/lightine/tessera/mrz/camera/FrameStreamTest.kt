package io.lightine.tessera.mrz.camera

import io.lightine.tessera.telemetry.NoOpTelemetrySink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Host test (JVM, no device) for [frameStream], the frame hand-off behind [CameraXMrzScanner] (TES-205).
 *
 * Frames cross from the camera's analysis thread to the recognition thread here, so the test locks the
 * guarantee CameraX needs: every frame is released exactly once, also when the scan is cancelled while
 * frames are in flight. A plain thread stands in for the CameraX analysis executor and uses the same
 * `trySendBlocking` call.
 */
class FrameStreamTest {
    private class Frame {
        val releases = AtomicInteger(0)

        fun release() {
            releases.incrementAndGet()
        }
    }

    @Test
    fun every_frame_is_released_exactly_once_when_the_scan_is_cancelled() =
        repeat(ITERATIONS) { iteration ->
            runBlocking {
                val produced = CopyOnWriteArrayList<Frame>()
                val producer = AtomicReference<Thread?>(null)
                val stream =
                    frameStream<Frame>(Dispatchers.Default, releaseFrame = Frame::release) { frames ->
                        producer.set(
                            thread(name = "fake-analysis-executor") {
                                while (true) {
                                    val frame = Frame()
                                    produced += frame
                                    if (frames.trySendBlocking(frame).isFailure) break
                                }
                            },
                        )
                        awaitCancellation()
                    }
                val analyzer =
                    MrzFrameAnalyzer<Frame>(
                        recognizer = {
                            yield()
                            RecognizedText(emptyList())
                        },
                        telemetry = NoOpTelemetrySink,
                    )
                val scan =
                    launch(Dispatchers.Default) {
                        analyzer.scan(stream).collect()
                    }
                // Vary the cancellation point so it lands in different phases of the hand-off.
                delay((iteration % 5).toLong())
                scan.cancelAndJoin()

                // The scan can be cancelled before the session starts; then no producer ran.
                producer.get()?.let { running ->
                    running.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
                    assertTrue(!running.isAlive, "the producer stops once the scan ends")
                }
                val wrong = produced.map { it.releases.get() }.filter { it != 1 }
                assertTrue(wrong.isEmpty(), "every frame is released exactly once; got release counts $wrong")
            }
        }

    @Test
    fun the_session_runs_in_its_context_and_is_torn_down_when_the_collector_stops() =
        runBlocking {
            val sessionThread = CopyOnWriteArrayList<String>()
            val tornDown = AtomicBoolean(false)
            val frames =
                frameStream<Frame>(Dispatchers.IO, releaseFrame = Frame::release) { channel ->
                    sessionThread += Thread.currentThread().name
                    try {
                        channel.send(Frame())
                        channel.send(Frame())
                        awaitCancellation()
                    } finally {
                        tornDown.set(true)
                    }
                }
            val collected = mutableListOf<Frame>()
            val collector = launch { frames.collect { collected += it } }
            while (collected.size < 2) yield()
            collector.cancelAndJoin()

            assertEquals(2, collected.size)
            assertTrue(tornDown.get(), "the session's teardown ran before the flow completed")
            assertTrue(sessionThread.single().startsWith("DefaultDispatcher-worker"), "the session ran in its context")
        }

    @Test
    fun a_session_failure_ends_the_stream_with_its_cause() =
        runBlocking {
            val failure = IllegalStateException("bind failed")
            val frames = frameStream<Frame>(Dispatchers.Default, releaseFrame = Frame::release) { throw failure }

            val thrown = assertFailsWith<IllegalStateException> { frames.toList() }
            assertEquals("bind failed", thrown.message)
        }

    @Test
    fun a_session_ended_by_a_foreign_cancellation_ends_the_scan_with_a_capture_error() =
        runBlocking {
            // A CancellationException that is not the collector's own, like a cancelled CameraX provider future.
            val frames =
                frameStream<Frame>(Dispatchers.Default, releaseFrame = Frame::release) {
                    throw CancellationException("camera provider lookup was cancelled")
                }
            val analyzer =
                MrzFrameAnalyzer<Frame>(recognizer = { RecognizedText(emptyList()) }, telemetry = NoOpTelemetrySink)

            val results =
                withTimeoutOrNull(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) {
                    analyzer
                        .scan(frames, captureErrorFor = { CameraError.CameraUnavailable(it.message ?: it.toString()) })
                        .toList()
                }

            assertNotNull(results, "the scan ends instead of waiting forever")
            val error = assertIs<MrzScanResult.CaptureError>(results.single()).error
            assertEquals(CameraError.CameraUnavailable("camera provider lookup was cancelled"), error)
        }

    private companion object {
        const val ITERATIONS = 300
        const val WAIT_SECONDS = 5L
    }
}
