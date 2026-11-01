package io.lightine.tessera.mrz.camera

import com.google.android.gms.tasks.TaskCompletionSource
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Host test (JVM, no device) for [awaitWhileReadingInput], the ML Kit Task wait behind full-frame reading in
 * [MlKitMrzTextRecognizer] (TES-220).
 *
 * ML Kit reads the frame's image until its Task completes, and cancelling our wait does not cancel the Task.
 * A [TaskCompletionSource] stands in for ML Kit: the test cancels the scan while the Task is still running and
 * checks that [frameStream] releases the frame only after the Task completes, and exactly once.
 */
class MlKitTaskAwaitTest {
    private class Frame {
        val releases = AtomicInteger(0)

        fun release() {
            releases.incrementAndGet()
        }
    }

    @Test
    fun a_cancelled_scan_releases_the_frame_only_after_the_task_completes() =
        runBlocking {
            val frame = Frame()
            val ocr = TaskCompletionSource<RecognizedText>()
            val recognizing = CompletableDeferred<Unit>()
            val stream =
                frameStream<Frame>(Dispatchers.Default, releaseFrame = Frame::release) { frames ->
                    frames.send(frame)
                    awaitCancellation()
                }
            val analyzer =
                MrzFrameAnalyzer<Frame>(
                    recognizer = {
                        recognizing.complete(Unit)
                        ocr.task.awaitWhileReadingInput()
                    },
                    telemetry = NoOpTelemetrySink,
                )
            val scan = launch(Dispatchers.Default) { analyzer.scan(stream).collect() }
            withTimeout(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) { recognizing.await() }

            scan.cancel()
            // ML Kit is still reading: the frame must stay open however long the scan has been cancelled.
            delay(SETTLE_MILLIS)
            assertEquals(0, frame.releases.get(), "the frame is not released while the task is running")

            ocr.setResult(RecognizedText(emptyList()))
            withTimeout(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) { scan.join() }
            assertEquals(1, frame.releases.get(), "the frame is released exactly once after the task completes")
        }

    @Test
    fun a_cancelled_wait_gives_up_after_the_bound_when_the_task_never_completes() =
        runBlocking {
            val frame = Frame()
            val neverCompletes = TaskCompletionSource<RecognizedText>()
            val recognizing = CompletableDeferred<Unit>()
            val stream =
                frameStream<Frame>(Dispatchers.Default, releaseFrame = Frame::release) { frames ->
                    frames.send(frame)
                    awaitCancellation()
                }
            val analyzer =
                MrzFrameAnalyzer<Frame>(
                    recognizer = {
                        recognizing.complete(Unit)
                        neverCompletes.task.awaitWhileReadingInput(maxWaitAfterCancelMillis = SHORT_BOUND_MILLIS)
                    },
                    telemetry = NoOpTelemetrySink,
                )
            val scan = launch(Dispatchers.Default) { analyzer.scan(stream).collect() }
            withTimeout(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) { recognizing.await() }

            withTimeout(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) { scan.cancelAndJoin() }
            assertEquals(1, frame.releases.get(), "a stuck task cannot hold the frame forever")
            assertTrue(!neverCompletes.task.isComplete)
        }

    private companion object {
        const val WAIT_SECONDS = 5L
        const val SETTLE_MILLIS = 300L
        const val SHORT_BOUND_MILLIS = 100L
    }
}
