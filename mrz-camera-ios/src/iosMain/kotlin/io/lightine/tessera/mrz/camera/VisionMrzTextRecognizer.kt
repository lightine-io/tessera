package io.lightine.tessera.mrz.camera

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.CVBufferPropagateAttachments
import platform.CoreVideo.CVImageBufferRef
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetHeightOfPlane
import platform.CoreVideo.CVPixelBufferGetPixelFormatType
import platform.CoreVideo.CVPixelBufferGetPlaneCount
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVReturnSuccess
import platform.Foundation.NSError
import platform.ImageIO.kCGImagePropertyOrientationRight
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRequestTextRecognitionLevelAccurate
import platform.posix.memcpy
import kotlin.concurrent.Volatile

/**
 * The iOS [MrzTextRecognizer], backed by Apple Vision (`VNRecognizeTextRequest`). It reads each
 * AVFoundation [CMSampleBufferRef][platform.CoreMedia.CMSampleBufferRef], copies it into an independent
 * pixel buffer (so Vision never references the camera's buffer pool — see *Frame ownership*), runs Vision
 * on-device, and returns the recognized lines for [MrzFrameAnalyzer] to turn into an MRZ candidate.
 * It makes no trust decision about what it reads — OCR only. The iOS analogue of the Android
 * `MlKitMrzTextRecognizer`, binding the analyse-frame core's frame type to `F = CMSampleBufferRef`
 * (the type an `AVCaptureVideoDataOutput` delivers), exactly as Android binds `F = ImageProxy`.
 *
 * **Vision configuration.** [`recognitionLevel`][VNRecognizeTextRequest.recognitionLevel] is `.accurate`
 * and **[`usesLanguageCorrection`][VNRecognizeTextRequest.usesLanguageCorrection] is `false`**: the MRZ is
 * not natural language, so language correction would *change* characters — the reader-not-oracle stance
 * forbids that. Recognized observations are ordered top-to-bottom (Vision does not guarantee reading
 * order) so the analyzer sees the MRZ lines in document order.
 *
 * **Frame ownership — copied off the camera pool.** This reads the sample buffer but never releases it;
 * the caller that produced the frame (the `AVCaptureVideoDataOutput` delegate, wired up by the
 * owns-the-camera-session layer) owns its lifetime, exactly as AVFoundation requires. Crucially it holds
 * **no reference** to the camera's frame buffer once [recognize] returns: it byte-copies the frame into an
 * independent, plain (heap-backed, not IOSurface-backed) `CVPixelBuffer` — row-by-row, honouring each
 * buffer's own bytes-per-row — and runs Vision on the copy. This follows Apple's
 * [guidance](https://developer.apple.com/forums/thread/679250): when you hold a sample buffer longer than
 * the delegate callback (we do — Vision is slower than the frame interval), copy the pixel data out so the
 * finite `AVCaptureVideoDataOutput` pool can recycle, rather than handing Vision the camera buffer and
 * pinning it through the graphics stack's deferred lifetime. A *single* copy buffer is reused across frames
 * and released on [close]: reusing one buffer instead of allocating per frame keeps the copy's own memory
 * to one frame's worth (device-verified — a fresh buffer per frame piled up). It is safe because
 * `performRequests` is synchronous: Vision has finished reading before the next frame overwrites the
 * buffer. (The capture *stall* seen during bring-up was a **separate** bug — the camera session's
 * sample-buffer delegate being garbage-collected, fixed in `AVCaptureMrzScanner` by holding a strong
 * reference — not anything about this buffer; see that class and the project's issue tracker.)
 *
 * **Threading.** Vision's `performRequests` is synchronous and CPU-bound; [recognize] runs it on the
 * calling coroutine's thread. The owns-the-camera-session layer drives frames on a background dispatch
 * queue, off the main thread.
 *
 * **PII.** Recognized MRZ text is kept out of any thrown failure message — on a Vision failure there is
 * no recognized text to leak, and the failure surfaces as [`CameraError.OcrFailed`][CameraError.OcrFailed]
 * carrying only Vision's own error description.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public class VisionMrzTextRecognizer :
    MrzTextRecognizer<CMSampleBufferRef>,
    AutoCloseable {
    // A single destination buffer the camera frame is copied into and Vision runs on, reused across
    // frames (see [recognize]). Held until [close]; recreated if the frame's dimensions or format change.
    private var reusableCopy: CVImageBufferRef? = null
    private var reusableWidth: ULong = 0u
    private var reusableHeight: ULong = 0u
    private var reusableFormat: UInt = 0u

    // When set, Vision restricts recognition to the MRZ band (see restrictToMrzBand) instead of the whole
    // frame. @Volatile because it may be set from any thread while [recognize] reads it on the analysis
    // thread — the Android counterpart of MlKitMrzTextRecognizer's bandRestricted.
    @Volatile private var bandRestricted: Boolean = false

    // One Vision request, configured once and reused across frames. Vision supports running a request
    // repeatedly via performRequests, and reuse avoids re-allocating the request (and re-resolving its
    // text-recognition model) on every frame. recognize() is called serially on the analysis thread, so a
    // single shared instance is safe. See [recognize] for why the configuration is what it is.
    private val textRequest: VNRecognizeTextRequest by lazy {
        VNRecognizeTextRequest().apply {
            setRecognitionLevel(VNRequestTextRecognitionLevelAccurate)
            setUsesLanguageCorrection(false)
        }
    }

    /**
     * Restrict OCR to the **MRZ band** — a centred horizontal band spanning the full width of the frame,
     * matching the on-screen framing guide. Opt-in (the default UI enables it; headless reading stays
     * full-frame). It changes only *where* Vision looks, never what is reported (Principle 1 untouched — it
     * makes no trust decision and does not detect the MRZ, it restricts a fixed region). Idempotent; call
     * before reading. The band is a fixed internal fraction ([MRZ_BAND_HEIGHT_FRACTION]) — same name and
     * value as the Android counterpart's `MRZ_BAND_HEIGHT_FRACTION`. This is the Apple Vision counterpart
     * of ML Kit's crop-the-input approach — ML Kit has no region-of-interest, so Android crops the frame;
     * Vision has one ([VNRecognizeTextRequest.regionOfInterest]), so this sets it instead of cropping.
     *
     * Also fixes the orientation the request runs with. Vision's `regionOfInterest` is normalized against
     * the image Vision considers *upright*, but this scanner's camera buffers otherwise reach Vision in the
     * sensor's raw (landscape) orientation — neither this recognizer nor the owning `AVCaptureMrzScanner`
     * rotates them (TES-86). So restricting the band also passes Vision the documented back-camera/portrait
     * orientation (`.right` — the exact case Apple's own `CGImagePropertyOrientation` documentation uses:
     * a portrait photo's sensor data is landscape and tagged `.right`), so the band lands on the actual MRZ
     * band and the cropped OCR itself reads upright text. This correction is scoped to the opt-in path
     * only: the unrestricted default below is unchanged (no orientation, full frame), so a headless
     * consumer's reading stays exactly what shipped.
     */
    public fun restrictToMrzBand() {
        bandRestricted = true
    }

    override suspend fun recognize(frame: CMSampleBufferRef): RecognizedText {
        // No backing image buffer (a malformed or non-video sample): nothing to recognize.
        val pixelBuffer = CMSampleBufferGetImageBuffer(frame) ?: return RecognizedText(emptyList())
        // Copy the frame off the camera's finite buffer pool, into a SINGLE reused buffer (see
        // "Frame ownership"): nothing below ever references the camera buffer (so the owns-session layer
        // can return it to the pool immediately), and reusing one buffer caps memory — Vision retains each
        // input and releases it lazily on its own thread, so a fresh buffer per frame piles up to >1.5 GB
        // before draining (device-verified); one reused buffer holds that to ~one frame's worth.
        val copy = copyIntoReusable(pixelBuffer) ?: return RecognizedText(emptyList())
        // Drain Vision's per-frame autoreleased *objects* (request handler, results, observations) on this
        // run-loop-less analysis thread; without an enclosing pool they would live until the next GC.
        return autoreleasepool {
            val handler =
                if (bandRestricted) {
                    // Both set on every frame (cheap struct/property assignment): the region-of-interest
                    // rect never changes, but writing it here — rather than once in restrictToMrzBand() —
                    // keeps the mutation and its use on the same (analysis) thread, avoiding any
                    // cross-thread visibility question for a plain (non-Kotlin-managed) ObjC property.
                    textRequest.setRegionOfInterest(mrzBandRegionOfInterest)
                    VNImageRequestHandler(copy, kCGImagePropertyOrientationRight, emptyMap<Any?, Any?>())
                } else {
                    VNImageRequestHandler(copy, emptyMap<Any?, Any?>())
                }
            recognize(handler)
        }
    }

    /** Releases the reused copy buffer; the owns-the-camera-session scanner calls this on its own close. */
    override fun close() {
        reusableCopy?.let { CVPixelBufferRelease(it) }
        reusableCopy = null
    }

    // The MRZ band as a normalized, BOTTOM-LEFT-origin CGRect — Vision's regionOfInterest coordinate space
    // (developer.apple.com/documentation/vision/vnimagebasedrequest/regionofinterest: normalized to the
    // processed image, origin at the image's lower-left corner; default {{0,0},{1,1}}, i.e. the whole
    // frame). Computed once (the fraction is a fixed constant) and reused across frames alongside the
    // shared [textRequest]. See [mrzBandOrigin] for the pure band math this is built from.
    private val mrzBandRegionOfInterest: CValue<CGRect> by lazy {
        val (originY, height) = mrzBandOrigin(MRZ_BAND_HEIGHT_FRACTION)
        CGRectMake(0.0, originY, 1.0, height)
    }

    // Copies the camera frame's bytes into [reusableCopy] (plane by plane), allocating/reallocating that
    // buffer only when the source's dimensions or pixel format change. The destination is its OWN
    // plain (heap-backed, NOT IOSurface-backed) buffer — a separate allocation, not from the camera's
    // finite AVCaptureVideoDataOutput pool — so Vision never references the camera buffer (capture can't
    // starve) and stays on the CPU path (an IOSurface-backed copy re-stalled capture on device; see the
    // realloc block). Reusing one buffer bounds memory. `performRequests` is synchronous, so Vision has
    // finished reading the previous frame before the next overwrites it. The copy is done row by row
    // honouring each buffer's own bytes-per-row (see [copyRows]): the camera buffer and the copy can pad
    // rows to different alignments, and a single whole-plane memcpy with one stride would shear the image
    // into noise Vision reads as no text. Returns null if allocation fails. The buffer is owned by this
    // recognizer and freed in [close], not per frame.
    private fun copyIntoReusable(source: CVImageBufferRef): CVImageBufferRef? =
        memScoped {
            val width = CVPixelBufferGetWidth(source)
            val height = CVPixelBufferGetHeight(source)
            val format = CVPixelBufferGetPixelFormatType(source)
            if (reusableCopy == null || width != reusableWidth || height != reusableHeight || format != reusableFormat) {
                reusableCopy?.let { CVPixelBufferRelease(it) }
                reusableCopy = null
                // Plain (heap-backed) destination — deliberately NOT IOSurface-backed. The copy exists to
                // keep Vision off the camera's finite AVCaptureVideoDataOutput pool; a plain malloc-backed
                // buffer is enough and Vision reads MRZ text from it fine (device-verified 2026-05-31, with
                // equal source/destination strides). An IOSurface-backed variant was tried and gave no
                // benefit, so the simpler plain buffer is used. (The capture stall that dominated bring-up
                // was a separate bug — a GC-collected capture delegate; see AVCaptureMrzScanner's
                // `captureDelegate`. It is NOT caused by this buffer's backing.)
                val out = alloc<CVPixelBufferRefVar>()
                if (CVPixelBufferCreate(null, width, height, format, null, out.ptr) != kCVReturnSuccess) return null
                reusableCopy = out.value ?: return null
                reusableWidth = width
                reusableHeight = height
                reusableFormat = format
            }
            val destination = reusableCopy ?: return null

            CVPixelBufferLockBaseAddress(source, kCVPixelBufferLock_ReadOnly)
            CVPixelBufferLockBaseAddress(destination, 0u)
            try {
                val planeCount = CVPixelBufferGetPlaneCount(source)
                if (planeCount == 0uL) {
                    copyRows(
                        source = CVPixelBufferGetBaseAddress(source),
                        destination = CVPixelBufferGetBaseAddress(destination),
                        sourceStride = CVPixelBufferGetBytesPerRow(source),
                        destinationStride = CVPixelBufferGetBytesPerRow(destination),
                        rows = CVPixelBufferGetHeight(source),
                    )
                } else {
                    var plane = 0uL
                    while (plane < planeCount) {
                        copyRows(
                            source = CVPixelBufferGetBaseAddressOfPlane(source, plane),
                            destination = CVPixelBufferGetBaseAddressOfPlane(destination, plane),
                            sourceStride = CVPixelBufferGetBytesPerRowOfPlane(source, plane),
                            destinationStride = CVPixelBufferGetBytesPerRowOfPlane(destination, plane),
                            rows = CVPixelBufferGetHeightOfPlane(source, plane),
                        )
                        plane++
                    }
                }
            } finally {
                CVPixelBufferUnlockBaseAddress(destination, 0u)
                CVPixelBufferUnlockBaseAddress(source, kCVPixelBufferLock_ReadOnly)
            }
            // Carry the camera buffer's colour attachments (YCbCr matrix, colour primaries, transfer
            // function) onto the copy so Vision interprets the raw YUV with the correct colour space and
            // range rather than guessing from a bare buffer.
            CVBufferPropagateAttachments(source, destination)
            destination
        }

    // Copies [rows] rows from [source] to [destination], honouring each buffer's own bytes-per-row. The
    // camera buffer and the copy can pad rows to different alignments (equal on the verified device, but
    // not guaranteed across formats/devices), so copying whole planes with a single stride could misalign
    // every row but the first; copy row by row, moving min(sourceStride, destinationStride) bytes so
    // neither buffer is over-read or over-written.
    private fun copyRows(
        source: COpaquePointer?,
        destination: COpaquePointer?,
        sourceStride: ULong,
        destinationStride: ULong,
        rows: ULong,
    ) {
        if (source == null || destination == null) return
        val sourceBytes = source.reinterpret<ByteVar>()
        val destinationBytes = destination.reinterpret<ByteVar>()
        val rowBytes = minOf(sourceStride, destinationStride)
        var row = 0uL
        while (row < rows) {
            memcpy(
                destinationBytes + (row * destinationStride).toLong(),
                sourceBytes + (row * sourceStride).toLong(),
                rowBytes.convert(),
            )
            row++
        }
    }

    // The Vision request itself, over a prepared image handler. The public CMSampleBuffer path builds a
    // handler from a copy of the frame's pixel buffer; a test builds one from a still image (CGImage) so
    // the request config, the run, the result reading, and the top-to-bottom ordering are all exercised on
    // the simulator without a camera. Throwing on a Vision failure is the seam's contract: MrzFrameAnalyzer
    // catches it and surfaces CameraError.OcrFailed.
    internal fun recognize(handler: VNImageRequestHandler): RecognizedText {
        val request = textRequest

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val ok = handler.performRequests(listOf(request), errorPtr.ptr)
            if (!ok) {
                val message = errorPtr.value?.localizedDescription ?: "Vision text recognition failed"
                throw VisionRecognitionException(message)
            }
        }

        @Suppress("UNCHECKED_CAST")
        val observations = (request.results as? List<VNRecognizedTextObservation>).orEmpty()

        return RecognizedText(
            observations
                // Vision does not guarantee reading order; sort by vertical position. Its normalized
                // coordinate origin is bottom-left, so a larger boundingBox.origin.y is higher on the
                // page — descending y is top-to-bottom reading order.
                .sortedByDescending { it.boundingBox.useContents { origin.y } }
                .mapNotNull { observation ->
                    val candidate = observation.topCandidates(1u).firstOrNull() as? VNRecognizedText
                    candidate?.let { RecognizedLine(text = it.string, confidence = it.confidence) }
                },
        )
    }

    internal companion object {
        // The fraction of the (upright) frame height the MRZ band occupies — same name and value as
        // Android's MlKitMrzTextRecognizer.MRZ_BAND_HEIGHT_FRACTION (0.18f there; Double here, CGFloat's
        // Kotlin/Native representation). Centred: see mrzBandOrigin. Tuning history / rationale: TES-86.
        internal const val MRZ_BAND_HEIGHT_FRACTION: Double = 0.18

        // The centred band's (originY, height), normalized within Vision's regionOfInterest space, for a
        // given band height fraction. Pure, so the band math is host-testable without a device — unlike
        // Android's pixel-based crop, Vision's regionOfInterest is normalized against the frame regardless
        // of its pixel size, so (unlike mrzBandRect on Android) this needs no frame-size input.
        internal fun mrzBandOrigin(heightFraction: Double): Pair<Double, Double> {
            val height = heightFraction.coerceIn(0.0, 1.0)
            return ((1.0 - height) / 2.0) to height
        }
    }
}

// Carries Vision's own error description from a failed performRequests so MrzFrameAnalyzer can surface
// it as a CameraError.OcrFailed. Never carries recognized text (there is none on failure).
private class VisionRecognitionException(
    message: String,
) : Exception(message)
