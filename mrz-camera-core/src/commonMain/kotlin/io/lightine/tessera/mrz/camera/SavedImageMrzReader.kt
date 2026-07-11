package io.lightine.tessera.mrz.camera

import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.telemetry.TelemetrySinkRegistry
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Reads an MRZ from a single **saved image** — the headless convenience for pre-captured image reading,
 * the saved-image sibling of the live-camera [MrzCameraScanner]
 * ([ADR-023](https://lightine.youtrack.cloud/articles/TES-A-63)). Built on the analyse-frame core: a still
 * image is one frame with no next frame to retry, so this wraps a [MrzFrameAnalyzer] configured with
 * [FrameProvenance.PRE_CAPTURED_IMAGE]. The consumer never sets provenance and so cannot mis-stamp the read
 * method, and a saved-image MRZ validates identically to a camera-sourced one (same parser, same result).
 *
 * **Opt-in, disabled by default.** Construction REQUIRES a [SavedImageReadingAcknowledgement] with no
 * default — saved images carry more risk than a live capture
 * ([reading-risks](https://lightine.youtrack.cloud/articles/TES-A-11)). Passing it is the consumer's explicit
 * acknowledgement; it confers no SDK judgement about the image (Principle 1).
 *
 * **Tolerant disambiguation ([tolerant]).** Orthogonal to [mode] (strict/lenient line detection): when on,
 * each read additionally enumerates how the recognized MRZ's ambiguous glyphs could be resolved and surfaces
 * every distinct, parseable reconstruction on [SavedImageScanResult.candidates] — never picking one (see
 * [MrzCandidate]). Off by default.
 *
 * **Capture metadata ([metadataReader] + [metadataPolicy]).** When a [metadataReader] is supplied and
 * [metadataPolicy] is not [CaptureMetadataPolicy.NONE], each read also returns the image's EXIF on
 * [SavedImageScanResult.captureMetadata]. [metadataPolicy] is the opt-in-within-opt-in gate for GPS PII —
 * the policy is forwarded to the reader, which suppresses GPS at read time under
 * [CaptureMetadataPolicy.EXCLUDING_LOCATION] (see [CaptureMetadata]). Off by default.
 *
 * @param F the saved-image input type — the platform file reference recognized by [recognizer]
 *   (e.g. `android.net.Uri` on Android, `NSURL` on iOS); obtain the recognizer from its gated platform factory.
 * @param acknowledgement the required saved-image-reading acknowledgement (the opt-in gate; no default).
 * @param recognizer the OCR seam for saved images.
 * @param mode lenient (default) or strict line detection, forwarded to the analyse-frame core. **Lenient by
 *   default** because saved images are OCR'd by ML Kit, which routinely injects spaces into MRZ lines
 *   (device-observed): lenient strips all whitespace before shape-matching so a real photo's MRZ band is still
 *   detected, where strict would fail its fixed line width and read nothing. Whitespace is never meaningful in
 *   an MRZ. Pass [`STRICT`][ParsingMode.STRICT] only for already-clean input.
 * @param tolerant when `true`, also surface candidate disambiguations on the result (default `false`).
 * @param metadataReader the EXIF-reading seam; when `null` no capture metadata is read (default).
 * @param metadataPolicy how much capture metadata to read; [CaptureMetadataPolicy.NONE] (default) reads none.
 * @param telemetry where per-read [CameraFrameEvent]s go; defaults to the application's registered sink
 *   ([TelemetrySinkRegistry.current][TelemetrySinkRegistry]). Pass an explicit sink to override.
 * @param referenceTimeProvider supplies the reference instant for date-window parsing; override in tests.
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
     * Reads [image] once and returns the result. [SavedImageScanResult.scan] is the primary read; if
     * [tolerant] is on, [SavedImageScanResult.candidates] also carries the surfaced disambiguations, and if a
     * [metadataReader] is configured with a non-`NONE` [metadataPolicy], [SavedImageScanResult.captureMetadata]
     * carries the image's EXIF. Never throws for OCR or parse problems — a failed OCR step becomes a
     * [`CaptureError`][MrzScanResult.CaptureError], an unparseable candidate a [`Decoded`][MrzScanResult.Decoded]
     * carrying the parser's failure, and an image with no MRZ a [`NoMrzFound`][MrzScanResult.NoMrzFound], all
     * on [SavedImageScanResult.scan]. Coroutine cancellation still propagates.
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

    /** Releases the OCR recognizer if it holds closeable resources (e.g. the bundled ML Kit recognizer). */
    override fun close() {
        ownedRecognizer?.close()
    }
}
