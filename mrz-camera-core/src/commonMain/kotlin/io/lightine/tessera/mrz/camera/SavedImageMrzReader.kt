package io.lightine.tessera.mrz.camera

import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.telemetry.TelemetrySinkRegistry
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Reads an MRZ from one saved image.
 *
 * Saved-image reading is off unless you pass a [SavedImageReadingAcknowledgement]. Results carry
 * the reading method `PRE_CAPTURED_IMAGE`. The reader wraps a [MrzFrameAnalyzer], so parsing and
 * validation match a camera read. Close the reader to release the recognizer.
 *
 * @param F the saved-image input type that [recognizer] reads, for example `android.net.Uri` or
 *   `NSURL`.
 * @param acknowledgement the required acknowledgement. It has no default.
 * @param recognizer the OCR engine for saved images.
 * @param mode how tolerant line detection is of OCR formatting noise. The default is
 *   [ParsingMode.LENIENT], because saved-image OCR can put stray spaces in an MRZ line and
 *   [ParsingMode.STRICT] then reports no MRZ.
 * @param tolerant when `true`, [SavedImageScanResult.candidates] lists the reconstructions of
 *   ambiguous characters (see [MrzCandidate]). The default is `false`.
 * @param metadataReader reads the capture metadata. When `null`, none is read. The default is `null`.
 * @param metadataPolicy how much capture metadata to read. The default is
 *   [CaptureMetadataPolicy.NONE].
 * @param telemetry where per-read [CameraFrameEvent]s go. The default is the registered sink
 *   ([TelemetrySinkRegistry.current][TelemetrySinkRegistry]).
 * @param referenceTimeProvider supplies the reference instant for date parsing. The default is the
 *   system clock.
 * @since 0.3.0
 */
public class SavedImageMrzReader<F>(
    acknowledgement: SavedImageReadingAcknowledgement,
    recognizer: MrzTextRecognizer<F>,
    private val mode: ParsingMode = ParsingMode.LENIENT,
    private val tolerant: Boolean = false,
    private val metadataReader: CaptureMetadataReader<F>? = null,
    private val metadataPolicy: CaptureMetadataPolicy = CaptureMetadataPolicy.NONE,
    telemetry: TelemetrySink = TelemetrySinkRegistry.current,
    private val referenceTimeProvider: () -> Instant = { Clock.System.now() },
) : AutoCloseable {
    // `acknowledgement` is the compile-time opt-in gate: requiring it (no default) forces the consumer to
    // name it at the call site. It carries no runtime state, so it is intentionally neither used nor retained.

    // The reader owns the OCR recognizer's lifetime: an AutoCloseable recognizer (e.g. the bundled ML Kit
    // one) is released on [close], mirroring CameraXMrzScanner; a non-closeable recognizer needs no cleanup.
    private val ownedRecognizer = recognizer as? AutoCloseable

    private val analyzer =
        MrzFrameAnalyzer(
            recognizer = recognizer,
            provenance = FrameProvenance.PRE_CAPTURED_IMAGE,
            mode = mode,
            telemetry = telemetry,
            referenceTimeProvider = referenceTimeProvider,
        )

    /**
     * Reads [image] once and returns the result.
     *
     * [SavedImageScanResult.candidates] is filled only when [tolerant] is on.
     * [SavedImageScanResult.captureMetadata] is filled only when a metadata reader is set and the
     * policy is not [CaptureMetadataPolicy.NONE]. An OCR failure is a [MrzScanResult.CaptureError],
     * an unparseable candidate is a [MrzScanResult.Decoded] holding the parser's failure, and an
     * image with no MRZ is a [MrzScanResult.NoMrzFound]; none of these throw. Coroutine
     * cancellation propagates, and so does an exception from the [CaptureMetadataReader].
     *
     * @param image the saved image to read.
     * @return the scan outcome with any candidates and capture metadata.
     */
    public suspend fun read(image: F): SavedImageScanResult {
        val scan = analyzer.analyse(image)
        val candidates = if (tolerant) candidatesFor(scan) else emptyList()
        val captureMetadata =
            if (metadataPolicy != CaptureMetadataPolicy.NONE) metadataReader?.read(image, metadataPolicy) else null
        return SavedImageScanResult(scan = scan, candidates = candidates, captureMetadata = captureMetadata)
    }

    // Runs the tolerant matcher over the SAME recognized text the analyse step already produced (no second
    // OCR). A capture failure carries no text, so it yields no candidates.
    private fun candidatesFor(scan: MrzScanResult): List<MrzCandidate> {
        val recognizedText =
            when (scan) {
                is MrzScanResult.Decoded -> scan.recognizedText
                is MrzScanResult.NoMrzFound -> scan.recognizedText
                is MrzScanResult.CaptureError -> return emptyList()
            }
        return TolerantMrzMatcher(mode, referenceTimeProvider).candidates(recognizedText)
    }

    /** Closes the recognizer if it is [AutoCloseable]. */
    override fun close() {
        ownedRecognizer?.close()
    }
}
