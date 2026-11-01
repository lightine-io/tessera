package io.lightine.tessera.mrz.camera

import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageProxy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.telemetry.TelemetrySink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CameraXMrzScanner] delivers [CameraXMrzScanner.results] on the main thread and runs recognition off it
 * (TES-205), the Android counterpart of the iOS `AVCaptureMrzScannerThreadingTest` (TES-200).
 *
 * A JVM host has no camera, so synthetic frames go in through the internal `startWith` seam, the same
 * pipeline `start()` uses with live frames. Robolectric supplies a real main Looper (the test runs on its
 * thread); the test idles that Looper while it waits, so `Dispatchers.Main` work runs. The collector uses
 * [Dispatchers.Unconfined], so it runs on whichever thread emits a result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraXMrzScannerThreadingTest {
    @Test
    fun recognition_runs_off_the_main_thread() {
        val recognizerCalls = AtomicInteger(0)
        val recognizerOnMain = AtomicInteger(0)
        val recognizer =
            MrzTextRecognizer<ImageProxy> {
                recognizerCalls.incrementAndGet()
                if (isMainThread()) recognizerOnMain.incrementAndGet()
                RecognizedText(emptyList())
            }

        val delivered = scan(recognizer, frameCount = FRAME_COUNT)

        assertEquals(FRAME_COUNT, delivered.size, "every frame gives one result")
        assertEquals(FRAME_COUNT, recognizerCalls.get())
        assertEquals(0, recognizerOnMain.get(), "recognition never runs on the main thread")
    }

    @Test
    fun results_arrive_on_the_main_thread_in_frame_order() {
        // A distinct line per call, so the order of the results shows the order of the frames.
        val calls = AtomicInteger(0)
        val recognizer =
            MrzTextRecognizer<ImageProxy> {
                RecognizedText(listOf(RecognizedLine("FRAME${calls.incrementAndGet()}", confidence = null)))
            }

        val delivered = scan(recognizer, frameCount = FRAME_COUNT)

        assertEquals(FRAME_COUNT, delivered.size, "every frame gives one result")
        assertTrue(delivered.all { it.onMain }, "every result arrives on the main thread")
        val texts =
            delivered.map {
                (it.result as MrzScanResult.NoMrzFound)
                    .recognizedText.lines
                    .single()
                    .text
            }
        assertEquals((1..FRAME_COUNT).map { "FRAME$it" }, texts, "results keep the frame order")
    }

    @Test
    fun a_slow_recognizer_does_not_block_the_main_thread() {
        // The recognizer posts a probe to the main thread and waits for it. If recognition ran on the main
        // thread, the probe could not run until the recognizer returned, so the wait would time out.
        val probeRanDuringRecognition = AtomicInteger(0)
        val recognizer =
            MrzTextRecognizer<ImageProxy> {
                val probe = CountDownLatch(1)
                Handler(Looper.getMainLooper()).post { probe.countDown() }
                if (probe.await(PROBE_WAIT_SECONDS, TimeUnit.SECONDS)) probeRanDuringRecognition.incrementAndGet()
                RecognizedText(emptyList())
            }

        val delivered = scan(recognizer, frameCount = 1)

        assertEquals(1, delivered.size)
        assertEquals(1, probeRanDuringRecognition.get(), "the main thread kept running while the recognizer worked")
    }

    @Test
    fun a_telemetry_sink_that_throws_does_not_end_the_scan() {
        val telemetryCalls = AtomicInteger(0)
        val throwing =
            object : TelemetrySink {
                override fun record(event: TelemetryEvent) {
                    telemetryCalls.incrementAndGet()
                    throw IllegalStateException("hook boom")
                }
            }

        val delivered =
            scan(MrzTextRecognizer { RecognizedText(emptyList()) }, frameCount = FRAME_COUNT, telemetry = throwing)

        assertEquals(FRAME_COUNT, delivered.size, "every frame still gives a result")
        assertTrue(delivered.all { it.result is MrzScanResult.NoMrzFound }, "no result is a capture error")
        assertEquals(FRAME_COUNT, telemetryCalls.get())
    }

    @Test
    fun every_frame_is_closed_once() {
        val frames = List(FRAME_COUNT) { CountingImageProxy() }
        scan(MrzTextRecognizer { RecognizedText(emptyList()) }, frames = frames)
        // The last frame is closed on a background thread just after its result reaches main, so wait for it.
        idleMainLooperUntil { frames.all { it.closes.get() >= 1 } }

        assertEquals(List(FRAME_COUNT) { 1 }, frames.map { it.closes.get() }, "each frame is closed exactly once")
    }

    @Test
    fun close_waits_for_a_running_recognition_before_closing_the_recognizer() {
        val recognizing = CountDownLatch(1)
        val finishRecognition = CountDownLatch(1)
        val recognizeReturned = AtomicBoolean(false)
        val closedWhileRecognizing = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val recognizer =
            object : MrzTextRecognizer<ImageProxy>, AutoCloseable {
                override suspend fun recognize(frame: ImageProxy): RecognizedText {
                    recognizing.countDown()
                    finishRecognition.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    recognizeReturned.set(true)
                    return RecognizedText(emptyList())
                }

                override fun close() {
                    if (!recognizeReturned.get()) closedWhileRecognizing.set(true)
                    closed.set(true)
                }
            }
        val scanner =
            CameraXMrzScanner(
                appContext = RuntimeEnvironment.getApplication(),
                lifecycleOwner = UnusedLifecycleOwner,
                recognizer = recognizer,
                telemetry = NoOpTelemetrySink,
            )
        try {
            scanner.startWith { framesOf(listOf(CountingImageProxy())) }
            // The fake camera session sends the frame from the main thread, so let the main Looper run.
            idleMainLooperUntil { recognizing.count == 0L }
            assertEquals(0L, recognizing.count, "precondition: recognition started")

            scanner.close()
            val closedBeforeRecognitionEnded = closed.get()
            finishRecognition.countDown()
            idleMainLooperUntil { closed.get() }

            assertTrue(!closedBeforeRecognitionEnded, "close() does not close the recognizer while it runs")
            assertTrue(closed.get(), "the recognizer is closed once recognition returns")
            assertTrue(!closedWhileRecognizing.get(), "the recognizer is closed only after recognize() returned")
        } finally {
            finishRecognition.countDown()
            scanner.close()
        }
    }

    private data class Delivery(
        val result: MrzScanResult,
        val onMain: Boolean,
    )

    private fun scan(
        recognizer: MrzTextRecognizer<ImageProxy>,
        frameCount: Int = FRAME_COUNT,
        frames: List<CountingImageProxy> = List(frameCount) { CountingImageProxy() },
        telemetry: TelemetrySink = NoOpTelemetrySink,
    ): List<Delivery> {
        assertTrue(isMainThread(), "precondition: the test must run on the main thread")
        val scanner =
            CameraXMrzScanner(
                appContext = RuntimeEnvironment.getApplication(),
                lifecycleOwner = UnusedLifecycleOwner,
                recognizer = recognizer,
                telemetry = telemetry,
            )
        val collectorScope = CoroutineScope(Dispatchers.Unconfined)
        val delivered = CopyOnWriteArrayList<Delivery>()
        try {
            collectorScope.launch { scanner.results.collect { delivered += Delivery(it, isMainThread()) } }
            scanner.startWith { framesOf(frames) }
            idleMainLooperUntil { delivered.size >= frames.size }
        } finally {
            scanner.close()
            collectorScope.cancel()
        }
        return delivered.toList()
    }

    // The production frame hand-off (frameStream) with a fake session in place of CameraX: like the camera
    // session, it runs on the main thread, and the stream releases each frame after analysis.
    private fun framesOf(frames: List<ImageProxy>): Flow<ImageProxy> =
        frameStream(Dispatchers.Main.immediate, releaseFrame = ImageProxy::close) { channel ->
            frames.forEach { channel.send(it) }
            awaitCancellation()
        }

    private fun idleMainLooperUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(IDLE_STEP_MS)
        }
    }

    private fun isMainThread(): Boolean = Looper.getMainLooper().thread === Thread.currentThread()

    // The startWith seam never binds a camera, so the lifecycle is never read.
    private object UnusedLifecycleOwner : LifecycleOwner {
        override fun getLifecycle(): Lifecycle = error("not used by the startWith seam")
    }

    private companion object {
        const val FRAME_COUNT = 5
        const val WAIT_SECONDS = 10L
        const val PROBE_WAIT_SECONDS = 2L
        const val IDLE_STEP_MS = 5L
    }
}

// An ImageProxy that counts close() calls; every other method is unused by a fake recognizer.
internal class CountingImageProxy private constructor(
    val closes: AtomicInteger,
    private val proxy: ImageProxy,
) : ImageProxy by proxy {
    constructor() : this(AtomicInteger(0))

    private constructor(closes: AtomicInteger) : this(
        closes,
        Proxy.newProxyInstance(ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)) { _, method, _ ->
            if (method.name == "close") closes.incrementAndGet()
            null
        } as ImageProxy,
    )
}
