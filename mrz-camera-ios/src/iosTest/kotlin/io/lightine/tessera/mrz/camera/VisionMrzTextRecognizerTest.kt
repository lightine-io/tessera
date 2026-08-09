package io.lightine.tessera.mrz.camera

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.runBlocking
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
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
import platform.Vision.VNImageRequestHandler
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Simulator test for [VisionMrzTextRecognizer] — runs Apple Vision end-to-end on the
 * `iosSimulatorArm64` target (the Simulator has no camera, but Vision runs on a supplied image), via
 * the internal still-image seam the recognizer exposes for exactly this. The MRZ-specific OCR accuracy
 * on a real document is a device/printed-target concern (the same OCR-brittleness caveat Android
 * recorded), so this asserts the *pipeline* — handler → request config → `performRequests` → result
 * reading → ordering — rather than a positive MRZ decode.
 */
@OptIn(ExperimentalForeignApi::class)
class VisionMrzTextRecognizerTest {
    @Test
    fun runs_the_vision_pipeline_and_returns_no_lines_for_a_blank_image() {
        // A 64×64 blank RGBA image: a valid CGImage Vision can process, containing no text.
        // Core Graphics objects are Core Foundation types — Kotlin/Native's ARC bridge does not release
        // them, so they are released explicitly (the same discipline the owns-session capture code will need).
        val colorSpace = CGColorSpaceCreateDeviceRGB()
        val context =
            CGBitmapContextCreate(
                data = null,
                width = 64u,
                height = 64u,
                bitsPerComponent = 8u,
                bytesPerRow = 0u,
                space = colorSpace,
                bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            )
        val image = CGBitmapContextCreateImage(context)
        try {
            val handler = VNImageRequestHandler(image, emptyMap<Any?, Any?>())

            val result = VisionMrzTextRecognizer().recognize(handler)

            // The pipeline ran end-to-end without throwing, and a blank image yields no recognized lines.
            assertTrue(result.lines.isEmpty(), "a blank image should produce no recognized lines")
        } finally {
            CGImageRelease(image)
            CGContextRelease(context)
            CGColorSpaceRelease(colorSpace)
        }
    }

    // TES-133: recognize() and close() are serialized by the recognizer's internal lock, and a
    // recognize() arriving after close() must NOT run the pipeline — the copy buffer its owner released
    // must stay released. Both outcomes return no lines, so the internal hasReusableCopy seam is the
    // discriminator: the full pipeline (re)creates the buffer, the closed gate does not.
    @Test
    fun recognize_after_close_is_gated_and_does_not_resurrect_the_copy_buffer() {
        withBlankSampleBuffer { sampleBuffer ->
            val recognizer = VisionMrzTextRecognizer()

            // Prime: a normal recognize on a live recognizer runs the pipeline and creates the buffer.
            val primed = runBlocking { recognizer.recognize(sampleBuffer) }
            assertTrue(primed.lines.isEmpty(), "a blank frame should produce no recognized lines")
            assertTrue(recognizer.hasReusableCopy, "a live recognize should have created the copy buffer")

            recognizer.close()
            assertFalse(recognizer.hasReusableCopy, "close must release the copy buffer")

            // Gated: after close the pipeline must not run, so the buffer must not come back.
            val gated = runBlocking { recognizer.recognize(sampleBuffer) }
            assertTrue(gated.lines.isEmpty(), "a closed recognizer must report no lines")
            assertFalse(recognizer.hasReusableCopy, "recognize after close must not recreate the buffer")
        }
    }

    // Builds a 64×64 blank BGRA CMSampleBuffer (the camera-delegate frame type) and hands it to [block],
    // releasing the Core Foundation objects afterwards regardless of outcome.
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
