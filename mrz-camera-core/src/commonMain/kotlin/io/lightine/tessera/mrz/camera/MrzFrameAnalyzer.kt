package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.telemetry.TelemetrySinkRegistry
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.ReadMethod
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The analyse-frame core of camera reading: one camera [frame][analyse] in, one [MrzScanResult] out
 * ([ADR-020](https://lightine.youtrack.cloud/articles/TES-A-49)).
 * It owns no camera and reads no document data of its own — it orchestrates one platform-agnostic
 * pipeline:
 *
 * 1. run OCR via the injected [MrzTextRecognizer] (the only platform-specific dependency);
 * 2. locate an MRZ-shaped candidate in the recognized text (see [ParsingMode]);
 * 3. hand the candidate to [`MrzParser`][MrzParser] — the same parser string input uses, so a
 *    camera-sourced MRZ validates identically to a typed-in one;
 * 4. surface the parser's verdict plus quality metadata, and emit one [CameraFrameEvent].
 *
 * Because the recognizer is injected, the whole core is unit-testable on the host with a mock that
 * returns canned text — no device, no real OCR. The generic frame type [F] is the extension seam:
 * Android binds `F = androidx.camera.core.ImageProxy`; a future USB/desktop/web source binds its own.
 *
 * **Reader, not oracle.** The analyzer never judges or corrects the reading. Quality signals are
 * exposed, never gated; the raw OCR text travels on the result. Two glyph *recoveries* are applied to the
 * parse candidate — case is folded to upper (the MRZ alphabet is uppercase-only) and out-of-alphabet
 * chevron glyphs the OCR engine emits for the filler `<` (e.g. `«`) are mapped to `<` — both recover the
 * single glyph the source can only have been, not a choice between two valid characters. Benign whitespace
 * is forgiven only in [ParsingMode.LENIENT]; nothing else about the text is altered, and the **raw** OCR
 * text (original glyphs and case) is preserved on every result, so a consumer always sees exactly what was
 * read (Principle 5).
 *
 * **Frame ownership.** The analyzer reads [frame][analyse] but never closes or retains it. The caller
 * that produced the frame (the owns-the-camera-session layer, or a test) owns its lifecycle and
 * releases it after [analyse] returns. Holding no reference to the frame keeps the memory-hygiene
 * commitment.
 *
 * @param F the platform frame type (e.g. `ImageProxy` on Android).
 * @param recognizer the OCR seam; the sole platform-specific collaborator.
 * @param mode how forgiving candidate extraction is of OCR formatting noise (default [ParsingMode.STRICT]).
 * @param telemetry where per-frame [CameraFrameEvent]s go. Defaults to the application's registered sink
 *   ([TelemetrySinkRegistry.current][TelemetrySinkRegistry]) — captured at construction, so register at
 *   startup per the registry's contract; it is [NoOpTelemetrySink] (events discarded) when none is
 *   registered. Pass an explicit sink to override per instance.
 * @param referenceTimeProvider supplies the reference instant for date-window parsing; override in
 *   tests for determinism, exactly as the string parser's `referenceTime` parameter is overridden.
 */
public class MrzFrameAnalyzer<F>(
    private val recognizer: MrzTextRecognizer<F>,
    private val mode: ParsingMode = ParsingMode.STRICT,
    private val telemetry: TelemetrySink = TelemetrySinkRegistry.current,
    private val referenceTimeProvider: () -> Instant = { Clock.System.now() },
) {
    // The read-method provenance stamped on every result. Defaults to live camera (the 0.2.0 behaviour);
    // a non-camera frame source (saved images, 0.3.0) selects PRE_CAPTURED_IMAGE via the secondary
    // constructor below. Set once at construction.
    private var provenance: FrameProvenance = FrameProvenance.LIVE_CAMERA

    /**
     * Constructs an analyzer whose frames come from a specific [provenance] — e.g.
     * [FrameProvenance.PRE_CAPTURED_IMAGE] for saved-image reading — so each result's read method is
     * stamped honestly (Principle 5 — report how the data reached the SDK). Identical to the primary
     * constructor in every other respect; the primary defaults provenance to [FrameProvenance.LIVE_CAMERA].
     * Added as a secondary constructor so the published primary constructor's binary signature is
     * unchanged ([ADR-007](https://lightine.youtrack.cloud/articles/TES-A-37) — strict backward compatibility).
     *
     * @param provenance where this analyzer's frames come from; stamped on every result's read method.
     */
    public constructor(
        recognizer: MrzTextRecognizer<F>,
        provenance: FrameProvenance,
        mode: ParsingMode = ParsingMode.STRICT,
        telemetry: TelemetrySink = TelemetrySinkRegistry.current,
        referenceTimeProvider: () -> Instant = { Clock.System.now() },
    ) : this(recognizer, mode, telemetry, referenceTimeProvider) {
        this.provenance = provenance
    }

    /**
     * Analyses a single [frame]. Never throws for OCR or parse problems: a failed OCR step becomes
     * [`MrzScanResult.CaptureError`][MrzScanResult.CaptureError], an unparseable candidate becomes
     * [`MrzScanResult.Decoded`][MrzScanResult.Decoded] carrying a `mrz-core` failure, and a frame
     * with no MRZ becomes [`MrzScanResult.NoMrzFound`][MrzScanResult.NoMrzFound]. Coroutine
     * cancellation still propagates.
     */
    public suspend fun analyse(frame: F): MrzScanResult {
        val recognizedText =
            try {
                recognizer.recognize(frame)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return MrzScanResult
                    .CaptureError(
                        error = CameraError.OcrFailed(failure.message ?: failure.toString()),
                        quality = ScanQuality(mrzRegionFound = false, ocrConfidence = null, recognizedLineCount = 0),
                    ).also(::recordTelemetry)
            }

        val candidate = extractMrzCandidate(recognizedText)
        val quality =
            ScanQuality(
                mrzRegionFound = candidate != null,
                ocrConfidence = aggregateConfidence(recognizedText),
                recognizedLineCount = recognizedText.lines.size,
            )

        val result =
            if (candidate == null) {
                MrzScanResult.NoMrzFound(recognizedText = recognizedText, quality = quality)
            } else {
                MrzScanResult.Decoded(
                    parse = MrzParser.parse(candidate, referenceTimeProvider()).withProvenance(provenance.readMethod),
                    recognizedText = recognizedText,
                    quality = quality,
                )
            }
        return result.also(::recordTelemetry)
    }

    // Finds a window of consecutive recognized lines — after mode-specific normalization — that matches a
    // known ICAO MRZ shape. The windowing, the MRZ-alphabet guard, and the glyph recovery (case fold, chevron
    // recovery) are shared with TolerantMrzMatcher via MrzLineDetection (TES-117) — see its KDoc for the
    // algorithm; the analyzer's behaviour is unchanged, only the implementation moved.
    private fun extractMrzCandidate(text: RecognizedText): List<String>? {
        val normalized = text.lines.map { MrzLineDetection.normalizeLine(it.text, mode) }
        return MrzLineDetection.findMrzWindow(normalized)
    }

    private fun aggregateConfidence(text: RecognizedText): Float? {
        val confidences = text.lines.mapNotNull { it.confidence }
        return if (confidences.isEmpty()) null else confidences.sum() / confidences.size
    }

    private fun recordTelemetry(result: MrzScanResult) {
        val outcome =
            when (result) {
                is MrzScanResult.Decoded -> CameraFrameOutcome.DECODED
                is MrzScanResult.NoMrzFound -> CameraFrameOutcome.NO_MRZ_FOUND
                is MrzScanResult.CaptureError -> CameraFrameOutcome.OCR_FAILED
            }
        telemetry.record(
            CameraFrameEvent(
                outcome = outcome,
                recognizedLineCount = result.quality.recognizedLineCount,
                mrzRegionFound = result.quality.mrzRegionFound,
                ocrConfidence = result.quality.ocrConfidence,
                detectedFormat = (result as? MrzScanResult.Decoded)?.let { formatOf(it.parse) },
            ),
        )
    }

    private fun formatOf(parse: ParseResult): MrzFormat? =
        when (parse) {
            is ParseResult.Success -> parse.document.format
            is ParseResult.PartialSuccess -> parse.document.format
            is ParseResult.Failure -> null
        }
}

// Stamps the analyzer's frame [readMethod] onto a parser result. The parser itself sees only a string, so
// it reports `BACKEND_STRING_INPUT`; the analyse-frame core knows where the frame came from — a live camera
// or a pre-captured image (see [FrameProvenance], chosen via the analyzer's constructor) — and records that
// honestly (Principle 5 — transparency: report what we know). Only the read-method changes; the parser's
// verdict (document, warnings, validation failures) is surfaced unchanged (reader, not oracle).
private fun ParseResult.withProvenance(readMethod: ReadMethod): ParseResult {
    val stamped = metadata.copy(readMethod = readMethod)
    return when (this) {
        is ParseResult.Success -> copy(metadata = stamped)
        is ParseResult.PartialSuccess -> copy(metadata = stamped)
        is ParseResult.Failure -> copy(metadata = stamped)
    }
}
