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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreMedia.CMFormatDescriptionRefVar
import platform.CoreMedia.CMSampleBufferCreateReadyWithImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMSampleBufferRefVar
import platform.CoreMedia.CMVideoFormatDescriptionCreateForImageBuffer
import platform.CoreMedia.kCMTimingInfoInvalid
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.Foundation.timeIntervalSinceDate
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [AVCaptureMrzScanner] delivers [AVCaptureMrzScanner.results] on the main thread and runs recognition off
 * it (TES-200).
 *
 * The Simulator has no camera, so synthetic frames go in through the internal `startWith` seam, the same
 * pipeline `start()` uses with live frames. The collector uses [Dispatchers.Unconfined], which, like a Swift
 * collector of a Kotlin Flow, has no dispatcher of its own and runs on whichever thread resumes it. The test
 * runs on the main thread and spins the main run loop while it waits, so `Dispatchers.Main` work runs
 * (nothing drains the main queue in a unit test otherwise).
 */
@OptIn(ExperimentalForeignApi::class)
class AVCaptureMrzScannerThreadingTest {
    @Test
    fun resultsArriveOnMainAndRecognitionRunsOffMain() {
        assertTrue(NSThread.isMainThread, "precondition: the test must run on the main thread")
        val frameCount = 3
        val recognizerCalls = AtomicInt(0)
        val recognizerOnMain = AtomicInt(0)
        // A distinct line per call, so the order of the results shows the order of the frames.
        val recognizer =
            MrzTextRecognizer<CMSampleBufferRef> {
                val call = recognizerCalls.incrementAndGet()
                if (NSThread.isMainThread) recognizerOnMain.incrementAndGet()
                RecognizedText(listOf(RecognizedLine("FRAME$call", confidence = null)))
            }
        val scanner = AVCaptureMrzScanner(recognizer = recognizer, telemetry = NoOpTelemetrySink)
        val collectorScope = CoroutineScope(Dispatchers.Unconfined)
        val delivered = mutableListOf<Pair<MrzScanResult, Boolean>>()
        withBlankSampleBuffer { sample ->
            try {
                collectorScope.launch {
                    scanner.results.collect { delivered += it to NSThread.isMainThread }
                }
                scanner.startWith { framesOf(sample, frameCount) }
                spinMainRunLoopUntil { delivered.size >= frameCount }
            } finally {
                scanner.close()
                collectorScope.cancel()
            }
        }

        assertEquals(frameCount, delivered.size, "every frame gives one result")
        assertTrue(delivered.all { it.second }, "every result arrives on the main thread")
        assertEquals(frameCount, recognizerCalls.value)
        assertEquals(0, recognizerOnMain.value, "recognition never runs on the main thread")
        val texts = delivered.map { (result, _) -> (result as MrzScanResult.NoMrzFound).recognizedText }
        assertEquals((1..frameCount).map { "FRAME$it" }, texts.map { it.lines.single().text })
    }

    // Each frame is CFRetain'd before it is emitted and released after, as the camera's frame stream does
    // (startWith's contract, TES-219).
    private fun framesOf(
        sample: CMSampleBufferRef,
        count: Int,
    ): Flow<CMSampleBufferRef> =
        flow {
            repeat(count) {
                CFRetain(sample)
                try {
                    emit(sample)
                } finally {
                    CFRelease(sample)
                }
            }
        }

    private fun spinMainRunLoopUntil(condition: () -> Boolean) {
        val deadline = NSDate.dateWithTimeIntervalSinceNow(10.0)
        while (!condition() && NSDate().timeIntervalSinceDate(deadline) < 0.0) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.02))
        }
    }

    // A 64x64 blank BGRA CMSampleBuffer (the camera-delegate frame type), released after [block].
    private fun withBlankSampleBuffer(block: (CMSampleBufferRef) -> Unit) {
        memScoped {
            val pixelBufferVar = alloc<CVPixelBufferRefVar>()
            CVPixelBufferCreate(kCFAllocatorDefault, 64u, 64u, kCVPixelFormatType_32BGRA, null, pixelBufferVar.ptr)
            val pixelBuffer = requireNotNull(pixelBufferVar.value) { "could not create the test pixel buffer" }
            try {
                val formatVar = alloc<CMFormatDescriptionRefVar>()
                CMVideoFormatDescriptionCreateForImageBuffer(kCFAllocatorDefault, pixelBuffer, formatVar.ptr)
                val format = requireNotNull(formatVar.value) { "could not create the test format description" }
                try {
                    val sampleVar = alloc<CMSampleBufferRefVar>()
                    CMSampleBufferCreateReadyWithImageBuffer(
                        kCFAllocatorDefault,
                        pixelBuffer,
                        format,
                        kCMTimingInfoInvalid.ptr,
                        sampleVar.ptr,
                    )
                    val sample = requireNotNull(sampleVar.value) { "could not create the test sample buffer" }
                    try {
                        block(sample)
                    } finally {
                        CFRelease(sample)
                    }
                } finally {
                    CFRelease(format)
                }
            } finally {
                CVPixelBufferRelease(pixelBuffer)
            }
        }
    }
}
