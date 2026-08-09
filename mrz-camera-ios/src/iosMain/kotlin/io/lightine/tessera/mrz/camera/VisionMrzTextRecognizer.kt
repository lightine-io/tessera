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
import platform.Vision.VNRequestTextRecognitionLevelFast
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

    // The viewfinder-supplied guide rect in AVFoundation's metadata-output space (see the rect-taking
    // [restrictToMrzBand] overload), or null when only the parameterless centred-band form was used.
    // An immutable holder so the @Volatile write/read hands over all four values atomically.
    @Volatile private var bandMetadataRect: MetadataRect? = null

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

    // A second reusable request at Vision's FAST recognition level, for the saved-image rescue pass only
    // (TES-130): on tight MRZ crops the fast path reads `<` filler runs more faithfully than accurate —
    // accurate's language modelling collapses long chevron runs (device- and photo-verified 2026-08-09).
    // Same no-language-correction stance; lazily built so the live path never pays for it.
    private val fastTextRequest: VNRecognizeTextRequest by lazy {
        VNRecognizeTextRequest().apply {
            setRecognitionLevel(VNRequestTextRecognitionLevelFast)
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

    /**
     * Restrict OCR to the **exact on-screen region** a viewfinder marks — the WYSIWYG form of
     * [restrictToMrzBand]. The parameterless overload assumes the guide band is a centred fraction of the
     * frame, which is only true when the preview shows the whole frame; a real viewfinder crops it
     * (`resizeAspectFill`) and places the guide wherever its layout does, so the on-screen band and a fixed
     * frame fraction diverge — the iOS counterpart of the Android scanner's CameraX `ViewPort` alignment
     * (TES-86's WYSIWYG arc). The caller converts the guide's layer rect with AVFoundation's own
     * `AVCaptureVideoPreviewLayer.metadataOutputRectOfInterest(for:)` — which accounts for the preview's
     * `videoGravity` crop and frame size — and passes the result here verbatim.
     *
     * All four values are therefore in **AVFoundation's metadata-output space**: normalized against the
     * *unrotated* capture buffer, origin at its top-left (the space that converter documents). The mapping
     * into Vision's own region-of-interest space (normalized against the image *as oriented* by the `.right`
     * this recognizer hands Vision, origin bottom-left) stays internal — see [visionRegionOfInterest].
     * Same reporting contract as the parameterless overload: restricts where Vision looks, never what is
     * reported. May be called again when layout changes; the latest rect wins.
     */
    public fun restrictToMrzBand(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
    ) {
        bandRestricted = true
        bandMetadataRect = MetadataRect(x, y, width, height)
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
            if (bandRestricted) {
                // Both set on every frame (cheap struct/property assignment): the region-of-interest
                // rect never changes, but writing it here — rather than once in restrictToMrzBand() —
                // keeps the mutation and its use on the same (analysis) thread, avoiding any
                // cross-thread visibility question for a plain (non-Kotlin-managed) ObjC property.
                val roi = mrzBandRegionOfInterest()
                textRequest.setRegionOfInterest(roi)
                fastTextRequest.setRegionOfInterest(roi)
                // Alternate Vision's recognition level across frames (TES-132 device data): ACCURATE's
                // language modelling collapses long `<` filler runs, so many sharp, well-framed frames
                // read exactly 3 band lines that all fail their fixed width; FAST reads chevrons
                // faithfully but letters slightly worse. Alternating gives every framing both readings
                // within a fraction of a second at unchanged per-frame cost, and the consensus gate
                // downstream arbitrates as it already does between any two frames.
                bandFrameParity = !bandFrameParity
                recognize(
                    VNImageRequestHandler(copy, kCGImagePropertyOrientationRight, emptyMap<Any?, Any?>()),
                    fast = bandFrameParity,
                )
            } else {
                recognize(VNImageRequestHandler(copy, emptyMap<Any?, Any?>()))
            }
        }
    }

    // Flips every band-restricted frame to alternate the recognition level; only ever touched on the
    // single analysis thread.
    private var bandFrameParity: Boolean = false

    /** Releases the reused copy buffer; the owns-the-camera-session scanner calls this on its own close. */
    override fun close() {
        reusableCopy?.let { CVPixelBufferRelease(it) }
        reusableCopy = null
    }

    // The active region-of-interest in Vision's coordinate space — normalized to the image AS ORIENTED by
    // the `.right` handed to the request handler, origin at the oriented image's lower-left corner (the
    // VNImageBasedRequest.regionOfInterest contract; hypothesis under device confirmation, TES-129 wave 4:
    // the two instrumented device runs jointly fit oriented-space — a centred band read the document's
    // dense middle, a would-be-raw strip cut every line's width — and jointly contradict raw-buffer space).
    // The WYSIWYG rect from the viewfinder wins over the parameterless centred fallback.
    private fun mrzBandRegionOfInterest(): CValue<CGRect> {
        val metadata =
            bandMetadataRect ?: run {
                // Parameterless fallback: a centred horizontal band of the oriented image, as shipped — an
                // approximation that is only exact when the consumer's preview shows the whole frame uncropped.
                val (originY, height) = mrzBandOrigin(MRZ_BAND_HEIGHT_FRACTION)
                return CGRectMake(0.0, originY, 1.0, height)
            }
        return visionRegionOfInterest(metadata)
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
    internal fun recognize(
        handler: VNImageRequestHandler,
        fast: Boolean = false,
    ): RecognizedText = RecognizedText(mergeRowFragments(recognizeFragments(handler, fast = fast)))

    // The raw per-observation view of a Vision run — text fragments WITH their normalized bounding boxes —
    // before row reassembly. The saved-image two-pass rescue (TES-130) needs the geometry to locate and
    // re-crop candidate MRZ rows; the public path immediately merges via [recognize] above. `fast` selects
    // the FAST recognition level (see [fastTextRequest]).
    internal fun recognizeFragments(
        handler: VNImageRequestHandler,
        fast: Boolean = false,
    ): List<TextFragment> {
        val request = if (fast) fastTextRequest else textRequest

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

        // Vision returns text as observations — regions it segmented itself, NOT guaranteed to be whole
        // visual lines: on a full-page photo it routinely splits one MRZ row into several fragments
        // (device-verified 2026-08-09: a 30-char TD1 row arrived as 25 + 5 on the same row), and the
        // downstream shape match requires each line to be complete and exact-width. ML Kit joins a row's
        // words into one Text.Line before we ever see them — Vision has no such level — so callers merge
        // via [mergeRowFragments]. Pure reassembly of what Vision read; nothing is invented (TES-18 stance).
        return observations.mapNotNull { observation ->
            val candidate =
                observation.topCandidates(1u).firstOrNull() as? VNRecognizedText
                    ?: return@mapNotNull null
            observation.boundingBox.useContents {
                TextFragment(
                    x = origin.x,
                    y = origin.y,
                    width = size.width,
                    height = size.height,
                    text = candidate.string,
                    confidence = candidate.confidence,
                )
            }
        }
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

        // Maps a rect from AVFoundation's metadata-output space — normalized to the UNROTATED buffer,
        // origin top-left (the space `metadataOutputRectOfInterest(for:)` documents) — into Vision's
        // regionOfInterest space for the `.right`-oriented image this recognizer hands Vision: normalized
        // to the ORIENTED (portrait) image, origin bottom-left. `.right` means the oriented image is the
        // raw buffer rotated 90° clockwise, so with normalized top-left coordinates a raw point (x, y)
        // lands at oriented (1 - y, x); applying that to the rect's corners and then flipping the vertical
        // origin to bottom-left gives, for a metadata rect (x, y, w, h):
        //   vision = (1 - y - h,  1 - x - w,  h,  w)
        // Pure, so the corner math is host-testable without a device. Values are clamped to [0, 1]: Vision
        // rejects out-of-space rects outright (the request fails), and a viewfinder's guide rect can
        // legitimately poke a hair outside the buffer after the aspect-fill conversion.
        // Reassembles Vision's text fragments into whole visual lines: fragments whose vertical extents
        // overlap by at least half the smaller height share a row; each row's fragments join left-to-right
        // (ascending x) with nothing inserted between them; rows order top-to-bottom (Vision's normalized
        // origin is bottom-left, so descending centre-y). A merged line's confidence is the minimum of its
        // fragments' (the honest signal — a line is no more trustworthy than its worst part). Grouping
        // compares each fragment to the previous one in centre-y order, which chains — fine for MRZ rows
        // (widely separated), and a degenerate zero-height fragment can never satisfy the overlap test, so
        // it conservatively stays its own line. Pure, so the row math is host-testable without a device.
        internal fun mergeRowFragments(fragments: List<TextFragment>): List<RecognizedLine> = groupRows(fragments).map(::joinRow)

        // The row-grouping half of [mergeRowFragments], exposed separately because the saved-image rescue
        // (TES-130) needs the grouped rows WITH their geometry to locate and re-crop candidate MRZ rows.
        internal fun groupRows(fragments: List<TextFragment>): List<List<TextFragment>> {
            if (fragments.isEmpty()) return emptyList()
            val rows = mutableListOf<MutableList<TextFragment>>()
            for (fragment in fragments.sortedByDescending { it.y + it.height / 2.0 }) {
                val row = rows.lastOrNull()
                if (row != null && sharesRow(row.last(), fragment)) row.add(fragment) else rows.add(mutableListOf(fragment))
            }
            return rows
        }

        // The joining half of [mergeRowFragments]: one visual row's fragments, left-to-right, nothing
        // inserted between them; confidence is the minimum of the fragments'.
        internal fun joinRow(row: List<TextFragment>): RecognizedLine {
            val parts = row.sortedBy { it.x }
            return RecognizedLine(
                text = parts.joinToString(separator = "") { it.text },
                confidence = parts.mapNotNull { it.confidence }.minOrNull(),
            )
        }

        private fun sharesRow(
            a: TextFragment,
            b: TextFragment,
        ): Boolean {
            val overlap = minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)
            return overlap > 0.0 && overlap >= 0.5 * minOf(a.height, b.height)
        }

        internal fun visionRegionOfInterest(rect: MetadataRect): CValue<CGRect> {
            val x = (1.0 - rect.y - rect.height).coerceIn(0.0, 1.0)
            val y = (1.0 - rect.x - rect.width).coerceIn(0.0, 1.0)
            return CGRectMake(x, y, rect.height.coerceIn(0.0, 1.0 - x), rect.width.coerceIn(0.0, 1.0 - y))
        }
    }
}

// One Vision text observation reduced to what row reassembly needs: its normalized bounding box (Vision's
// bottom-left-origin space) plus the recognized text and confidence. Internal — consumers only ever see the
// merged [RecognizedLine]s.
internal data class TextFragment(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val text: String,
    val confidence: Float?,
)

// A viewfinder-supplied rect in AVFoundation's metadata-output coordinate space (normalized, unrotated
// buffer, top-left origin). Internal immutable holder so a @Volatile field hands all four values over
// atomically; kept off the public surface — the public overload takes plain Doubles.
internal data class MetadataRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

// Carries Vision's own error description from a failed performRequests so MrzFrameAnalyzer can surface
// it as a CameraError.OcrFailed. Never carries recognized text (there is none on failure).
internal class VisionRecognitionException(
    message: String,
) : Exception(message)
