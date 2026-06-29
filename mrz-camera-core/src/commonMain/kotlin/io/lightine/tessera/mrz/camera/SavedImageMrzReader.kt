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
 * @param F the saved-image input type — the platform file reference recognized by [recognizer]
 *   (e.g. `android.net.Uri` on Android, `NSURL` on iOS); obtain the recognizer from its gated platform factory.
 * @param acknowledgement the required saved-image-reading acknowledgement (the opt-in gate; no default).
 * @param recognizer the OCR seam for saved images.
 * @param mode strict (default) or lenient candidate extraction, forwarded to the analyse-frame core.
 * @param telemetry where per-read [CameraFrameEvent]s go; defaults to the application's registered sink
 *   ([TelemetrySinkRegistry.current][TelemetrySinkRegistry]). Pass an explicit sink to override.
 * @param referenceTimeProvider supplies the reference instant for date-window parsing; override in tests.
 */
public class SavedImageMrzReader<F>(
    acknowledgement: SavedImageReadingAcknowledgement,
    recognizer: MrzTextRecognizer<F>,
    mode: ParsingMode = ParsingMode.STRICT,
    telemetry: TelemetrySink = TelemetrySinkRegistry.current,
    referenceTimeProvider: () -> Instant = { Clock.System.now() },
) {
    // `acknowledgement` is the compile-time opt-in gate: requiring it (no default) forces the consumer to
    // name it at the call site. It carries no runtime state, so it is intentionally neither used nor retained.

    private val analyzer =
        MrzFrameAnalyzer(
            recognizer = recognizer,
            provenance = FrameProvenance.PRE_CAPTURED_IMAGE,
            mode = mode,
            telemetry = telemetry,
            referenceTimeProvider = referenceTimeProvider,
        )

    /**
     * Reads [image] once and returns the result. Never throws for OCR or parse problems — a failed OCR step
     * becomes a [`CaptureError`][MrzScanResult.CaptureError], an unparseable candidate a
     * [`Decoded`][MrzScanResult.Decoded] carrying the parser's failure, and an image with no MRZ a
     * [`NoMrzFound`][MrzScanResult.NoMrzFound], all on [SavedImageScanResult.scan]. Coroutine cancellation
     * still propagates.
     */
    public suspend fun read(image: F): SavedImageScanResult = SavedImageScanResult(scan = analyzer.analyse(image))
}
