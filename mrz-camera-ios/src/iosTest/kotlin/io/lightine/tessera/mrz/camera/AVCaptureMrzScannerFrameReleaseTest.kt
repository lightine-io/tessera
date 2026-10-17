package io.lightine.tessera.mrz.camera

import io.lightine.tessera.telemetry.NoOpTelemetrySink
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import platform.CoreFoundation.CFGetRetainCount
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreMedia.CMFormatDescriptionRef
import platform.CoreMedia.CMFormatDescriptionRefVar
import platform.CoreMedia.CMSampleBufferCreateReadyWithImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMSampleBufferRefVar
import platform.CoreMedia.CMVideoFormatDescriptionCreateForImageBuffer
import platform.CoreMedia.kCMTimingInfoInvalid
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.Foundation.timeIntervalSinceDate
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.posix.usleep
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [AVCaptureMrzScanner] releases every camera frame exactly once, also when it stops mid-frame (TES-219).
 *
 * The Simulator has no camera, so frames go in through the internal `startWith` seam. The fake camera
 * mirrors the live one: a capture thread retains each new frame and offers it to the same kind of frame
 * channel `cameraFrames()` uses, and the stream hands frames on with the same `emitFrames`. Each frame's retain count shows
 * how often the scanner released it: the test keeps two references of its own, so a frame released exactly
 * once ends at 2 (3 means never released, 1 means released twice).
 */
@OptIn(ExperimentalForeignApi::class)
class AVCaptureMrzScannerFrameReleaseTest {
    @Test
    fun everyFrameIsReleasedExactlyOnceWhenTheScanStopsMidStream() {
        assertTrue(NSThread.isMainThread, "precondition: the test must run on the main thread")
        val recognizer =
            MrzTextRecognizer<CMSampleBufferRef> {
                yield()
                RecognizedText(emptyList())
            }
        val scanner = AVCaptureMrzScanner(recognizer = recognizer, telemetry = NoOpTelemetrySink)
        val collectorScope = CoroutineScope(Dispatchers.Unconfined)
        val wrong = mutableListOf<String>()
        withPixelBuffer { pixelBuffer, format ->
            try {
                collectorScope.launch { scanner.results.collect {} }
                repeat(ITERATIONS) { iteration ->
                    val camera = FakeCamera(pixelBuffer, format)
                    scanner.startWith(camera::frames)
                    // Vary the stop point so it lands in different phases of the hand-off.
                    spinMainRunLoopFor(seconds = (iteration % 5) * 0.001)
                    scanner.stop()
                    val ended = spinMainRunLoopUntil { camera.isFinished }
                    assertTrue(ended, "iteration $iteration: the stream ends after stop()")
                    val counts = camera.produced.map { CFGetRetainCount(it).toInt() }
                    counts.forEachIndexed { index, count ->
                        if (count != 2) wrong += "iteration $iteration frame $index: retain count $count"
                    }
                    camera.produced.forEach { frame -> repeat(CFGetRetainCount(frame).toInt()) { CFRelease(frame) } }
                }
            } finally {
                scanner.close()
                collectorScope.cancel()
            }
        }
        assertTrue(wrong.isEmpty(), "every frame is released exactly once (retain count 2); ${wrong.size} wrong: ${wrong.take(20)}")
    }

    // A stand-in for the live capture session: a capture thread offers frames to a frame channel until the
    // session ends, as the AVCaptureVideoDataOutput delegate does on its dispatch queue.
    private class FakeCamera(
        private val pixelBuffer: CVPixelBufferRef,
        private val format: CMFormatDescriptionRef,
    ) {
        // Written by the capture thread only; read after [isFinished] is seen.
        val produced = mutableListOf<CMSampleBufferRef>()
        private val stopRequested = AtomicInt(0)
        private val captureThreadDone = AtomicInt(0)
        private val streamDone = AtomicInt(0)

        val isFinished: Boolean get() = captureThreadDone.value == 1 && streamDone.value == 1

        fun frames(): Flow<CMSampleBufferRef> =
            flow {
                val frames =
                    Channel<CMSampleBufferRef>(
                        capacity = 1,
                        onBufferOverflow = BufferOverflow.DROP_OLDEST,
                        onUndeliveredElement = { CFRelease(it) },
                    )
                dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                    while (stopRequested.value == 0) {
                        val frame = newFrame()
                        // The test's own two references.
                        CFRetain(frame)
                        produced += frame
                        // The stream's reference, as the capture delegate takes it.
                        CFRetain(frame)
                        if (!frames.trySend(frame).isSuccess) CFRelease(frame)
                        usleep(200u)
                    }
                    captureThreadDone.value = 1
                }
                try {
                    emitFrames(frames) { CFRelease(it) }
                } finally {
                    stopRequested.value = 1
                    frames.close()
                    streamDone.value = 1
                }
            }

        private fun newFrame(): CMSampleBufferRef =
            memScoped {
                val sampleVar = alloc<CMSampleBufferRefVar>()
                CMSampleBufferCreateReadyWithImageBuffer(
                    kCFAllocatorDefault,
                    pixelBuffer,
                    format,
                    kCMTimingInfoInvalid.ptr,
                    sampleVar.ptr,
                )
                requireNotNull(sampleVar.value) { "could not create a test sample buffer" }
            }
    }

    private fun spinMainRunLoopFor(seconds: Double) {
        NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(seconds))
    }

    private fun spinMainRunLoopUntil(condition: () -> Boolean): Boolean {
        val deadline = NSDate.dateWithTimeIntervalSinceNow(5.0)
        while (!condition() && NSDate().timeIntervalSinceDate(deadline) < 0.0) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.002))
        }
        return condition()
    }

    // A 64x64 blank BGRA pixel buffer and its format, shared by every test frame, released after [block].
    private fun withPixelBuffer(block: (CVPixelBufferRef, CMFormatDescriptionRef) -> Unit) {
        memScoped {
            val pixelBufferVar = alloc<CVPixelBufferRefVar>()
            CVPixelBufferCreate(kCFAllocatorDefault, 64u, 64u, kCVPixelFormatType_32BGRA, null, pixelBufferVar.ptr)
            val pixelBuffer = requireNotNull(pixelBufferVar.value) { "could not create the test pixel buffer" }
            try {
                val formatVar = alloc<CMFormatDescriptionRefVar>()
                CMVideoFormatDescriptionCreateForImageBuffer(kCFAllocatorDefault, pixelBuffer, formatVar.ptr)
                val format = requireNotNull(formatVar.value) { "could not create the test format description" }
                try {
                    block(pixelBuffer, format)
                } finally {
                    CFRelease(format)
                }
            } finally {
                CVPixelBufferRelease(pixelBuffer)
            }
        }
    }

    private companion object {
        const val ITERATIONS = 300
    }
}
