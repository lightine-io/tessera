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
 * Analyses one camera frame: runs OCR, locates an MRZ, parses it, and returns a [MrzScanResult].
 *
 * Before parsing it folds case to upper and maps out-of-alphabet chevron glyphs to `<`;
 * [ParsingMode.LENIENT] also removes spaces. The raw OCR text stays on every result. The analyzer
 * emits one [CameraFrameEvent] per frame. It reads a frame but never closes or retains it: the
 * caller releases the frame after [analyse] returns.
 *
 * @param F the platform frame type, for example `ImageProxy` on Android.
 * @param recognizer the OCR engine.
 * @param mode how tolerant candidate extraction is of OCR formatting noise. The default is
 *   [ParsingMode.STRICT].
 * @param telemetry where per-frame [CameraFrameEvent]s go. The default is the sink registered
 *   when the analyzer is constructed, or [NoOpTelemetrySink] when none is registered.
 * @param referenceTimeProvider supplies the reference instant for date parsing. The default is the
 *   system clock.
 * @since 0.2.0
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
     * Creates an analyzer that stamps [provenance] as the reading method of every result.
     *
     * @param recognizer the OCR engine.
     * @param provenance where the frames come from.
     * @param mode how tolerant candidate extraction is of OCR formatting noise. The default is
     *   [ParsingMode.STRICT].
     * @param telemetry where per-frame [CameraFrameEvent]s go. The default is the registered sink.
     * @param referenceTimeProvider supplies the reference instant for date parsing. The default is
     *   the system clock.
     * @since 0.3.0
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
     * Analyses a single [frame] and returns the result.
     *
     * An OCR failure is a [MrzScanResult.CaptureError] holding [CameraError.OcrFailed], an
     * unparseable candidate is a [MrzScanResult.Decoded] holding the parse failure, and a frame
     * with no MRZ is a [MrzScanResult.NoMrzFound]; none of these throw. Coroutine cancellation
     * propagates. It calls [MrzTextRecognizer.recognize] on the caller's coroutine context.
     *
     * @param frame the frame to analyse.
     * @return the outcome for this frame.
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

    // The one place a frame event is sent. The sink is the app's code, so an exception from it is dropped
    // (reading continues, nothing is reported as a capture failure); cancellation still propagates (TES-175).
    private fun recordTelemetry(result: MrzScanResult) {
        try {
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
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ignored: Exception) {
            // Dropped on purpose: a failing hook must not stop reading.
        }
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
