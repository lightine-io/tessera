package io.lightine.tessera.mrz.camera

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFURLRef
import platform.CoreGraphics.CGImageCreateWithImageInRect
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSURL
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.CGImageSourceRef
import platform.Vision.VNImageRequestHandler

/**
 * The iOS saved-image [MrzTextRecognizer], backed by Apple Vision — the saved-image sibling of the camera
 * [VisionMrzTextRecognizer]. It runs the **same** Vision text-recognition pipeline the camera path uses
 * (`.accurate` recognition, **no** language correction so the MRZ characters are never altered, rows
 * reassembled top-to-bottom), plus a saved-image-only **two-pass MRZ rescue** ([VisionSavedImageRescue],
 * TES-130): the live camera reads a tight guide-band crop where Vision sees the MRZ large and whole, but a
 * full-page photo gives Vision the MRZ as a tiny strip — it splits rows and collapses `<` filler, so the
 * exact-width shape match downstream can never fire. The rescue re-crops the candidate MRZ rows located by
 * the first pass and re-reads each tight crop (both Vision recognition levels — FAST reads chevron filler
 * more faithfully than accurate's language modelling), keeping only reads whose stripped length matches an
 * ICAO MRZ width. Selection among actual OCR reads, never invention — no character is padded or guessed,
 * and the parser's check digits remain the arbiter. Pass-1 rows are always appended after the rescued
 * lines, so a photo the first pass already read correctly is never made worse.
 *
 * Obtained from the gated factory [visionSavedImageRecognizer]; the class is `internal` so it is only
 * reachable through that factory, which requires the saved-image-reading acknowledgement (the public
 * constructor cannot bypass it).
 *
 * iOS reads saved images from a file [NSURL] only in 0.3.0: the URL-based handler reads the file and applies
 * its EXIF orientation, whereas Vision's `NSData` initializer assumes an upright image — so an `NSData`
 * overload is deferred ([ADR-023](https://lightine.youtrack.cloud/articles/TES-A-63)). The rescue's own
 * crops undo the EXIF orientation with pure rect math ([VisionSavedImageRescue.rawCropRect]) and hand each
 * crop to Vision with the file's orientation, so both passes read upright text. A Vision failure in pass 1
 * throws, which [MrzFrameAnalyzer] catches and surfaces as [`CameraError.OcrFailed`][CameraError.OcrFailed]
 * (the OCR-seam contract); a pass-2 failure only skips the rescue (pass 1's rows still return).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class VisionSavedImageRecognizer(
    private val vision: VisionMrzTextRecognizer = VisionMrzTextRecognizer(),
) : MrzTextRecognizer<NSURL>,
    AutoCloseable {
    override suspend fun recognize(frame: NSURL): RecognizedText =
        // Vision's file read + recognition is blocking and CPU-bound; run it off the caller's (possibly main)
        // thread so a consumer calling read() from a UI coroutine does not freeze. The saved-image path has no
        // owns-the-session layer to do this, unlike the camera path (which drives Vision on Dispatchers.Default
        // — see AVCaptureMrzScanner); Dispatchers.IO is JVM-only, so Default is the Kotlin/Native equivalent.
        // Mirrors the Android recognizer's off-caller-thread recognize().
        withContext(Dispatchers.Default) {
            // Drain Vision's per-frame autoreleased objects (handler, results, observations) on this
            // run-loop-less analysis thread, exactly as the camera recognizer does.
            autoreleasepool {
                val fragments = vision.recognizeFragments(VNImageRequestHandler(uRL = frame, options = emptyMap<Any?, Any?>()))
                val rows = VisionMrzTextRecognizer.groupRows(fragments)
                val passOneLines = rows.map(VisionMrzTextRecognizer::joinRow)

                val candidates = rows.filter { row -> VisionSavedImageRescue.isMrzCandidate(strippedText(row)) }
                if (candidates.isEmpty()) return@autoreleasepool RecognizedText(passOneLines)

                // Rescued lines lead (top-to-bottom, the analyzer's consecutive-window order), pass-1 rows
                // follow as the unmodified fallback — the shape match slides over all of it.
                RecognizedText(rescueCandidates(frame, candidates) + passOneLines)
            }
        }

    // Pass 2: crop each candidate region out of the RAW bitmap (undoing EXIF orientation with pure rect
    // math) and re-read it at both Vision levels; collect exact-width reads. Any plumbing failure — image
    // load, unsupported orientation, crop, Vision error — skips the rescue for that region: the rescue only
    // ever adds lines, it never blocks pass 1's result. FAST runs before accurate (photo-verified: fast
    // reads chevron filler better), and identical rescued strings from overlapping regions/levels dedupe.
    private fun rescueCandidates(
        frame: NSURL,
        candidates: List<List<TextFragment>>,
    ): List<RecognizedLine> {
        // NSURL is toll-free bridged to CFURL; CFBridgingRetain hands us a +1 CF reference to release.
        val cfUrl: CFURLRef = CFBridgingRetain(frame)?.reinterpret() ?: return emptyList()
        try {
            val source = CGImageSourceCreateWithURL(cfUrl, null) ?: return emptyList()
            try {
                val orientation = imageOrientation(source)
                val image = CGImageSourceCreateImageAtIndex(source, 0u, null) ?: return emptyList()
                try {
                    return rescueFromImage(image, orientation, candidates)
                } finally {
                    CGImageRelease(image)
                }
            } finally {
                CFRelease(source)
            }
        } finally {
            CFRelease(cfUrl)
        }
    }

    private fun rescueFromImage(
        image: CGImageRef,
        orientation: Int,
        candidates: List<List<TextFragment>>,
    ): List<RecognizedLine> {
        val width = CGImageGetWidth(image).toDouble()
        val height = CGImageGetHeight(image).toDouble()
        if (width <= 0.0 || height <= 0.0) return emptyList()
        val rescued = mutableListOf<RecognizedLine>()
        val seen = mutableSetOf<String>()
        for (row in candidates) {
            // Pad generously: dropped trailing filler means the true row extends past the observed box
            // (photo-verified), so the crop reaches beyond it horizontally and by over half a row height
            // vertically — the same margins the photo-set validation used.
            val region = VisionSavedImageRescue.rowRegion(row)
            val padded = region.padded(padX = HORIZONTAL_PAD, padY = region.height * VERTICAL_PAD_FACTOR + 0.005)
            val raw = VisionSavedImageRescue.rawCropRect(padded, orientation) ?: continue
            val crop =
                CGImageCreateWithImageInRect(
                    image,
                    CGRectMake(raw.x * width, raw.y * height, raw.width * width, raw.height * height),
                ) ?: continue
            try {
                for (fast in listOf(true, false)) {
                    val fragments =
                        try {
                            vision.recognizeFragments(
                                VNImageRequestHandler(crop, orientation.toUInt(), emptyMap<Any?, Any?>()),
                                fast = fast,
                            )
                        } catch (_: VisionRecognitionException) {
                            continue
                        }
                    for (line in VisionMrzTextRecognizer.groupRows(fragments).map(VisionMrzTextRecognizer::joinRow)) {
                        val stripped = line.text.filterNot(Char::isWhitespace)
                        for (text in VisionSavedImageRescue.rescuedLines(stripped)) {
                            if (seen.add(text)) rescued.add(RecognizedLine(text = text, confidence = line.confidence))
                        }
                    }
                }
            } finally {
                CGImageRelease(crop)
            }
        }
        return rescued
    }

    // Reads the file's EXIF orientation (1 when absent — an unrotated image). kCGImagePropertyOrientation's
    // bridged dictionary key is the string "Orientation"; CFBridgingRelease takes ownership of the copy.
    private fun imageOrientation(source: CGImageSourceRef): Int {
        val properties = CGImageSourceCopyPropertiesAtIndex(source, 0u, null) ?: return 1
        val bridged = CFBridgingRelease(properties) as? Map<Any?, *> ?: return 1
        return (bridged["Orientation"] as? Number)?.toInt() ?: 1
    }

    private companion object {
        // Crop paddings, validated against the 13-photo set (TES-130): 6% horizontal, 60% of the row height
        // vertically (plus a small absolute floor for hairline boxes).
        private const val HORIZONTAL_PAD: Double = 0.06
        private const val VERTICAL_PAD_FACTOR: Double = 0.6
    }

    private fun strippedText(row: List<TextFragment>): String = VisionMrzTextRecognizer.joinRow(row).text.filterNot(Char::isWhitespace)

    /** Releases the underlying Vision recognizer's resources. */
    override fun close() {
        vision.close()
    }
}

/**
 * Creates the iOS saved-image OCR recognizer. Reading saved images is opt-in: this REQUIRES a
 * [SavedImageReadingAcknowledgement] (no default) — the consumer's explicit acknowledgement of the higher
 * saved-image risk (see [SavedImageMrzReader] and
 * [reading-risks](https://lightine.youtrack.cloud/articles/TES-A-11)). The returned recognizer reads a file
 * [NSURL] via Apple Vision; pass it to [SavedImageMrzReader], which releases it when the reader is closed.
 *
 * @param acknowledgement the required saved-image-reading acknowledgement (the opt-in gate).
 */
public fun visionSavedImageRecognizer(acknowledgement: SavedImageReadingAcknowledgement): MrzTextRecognizer<NSURL> =
    VisionSavedImageRecognizer()
