package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.formats.MrvAFormatSpec
import io.lightine.tessera.mrz.formats.MrvBFormatSpec
import io.lightine.tessera.mrz.formats.Td1FormatSpec
import io.lightine.tessera.mrz.formats.Td2FormatSpec
import io.lightine.tessera.mrz.formats.Td3FormatSpec
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.telemetry.TelemetrySinkRegistry
import io.lightine.tessera.types.vocabulary.MrzFormat
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The analyse-frame core of camera reading: one camera [frame][analyse] in, one [MrzScanResult] out
 * ([ADR-020](https://github.com/lightine-io/tessera/blob/main/docs/decisions/0020-camera-reading-architecture.md)).
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
 * exposed, never gated; the raw OCR text travels on the result; case is folded to upper (the MRZ
 * alphabet is uppercase-only, so this recovers the intended glyph rather than choosing between two
 * distinct characters) and benign whitespace is forgiven only in [ParsingMode.LENIENT]; nothing else
 * about the text is altered.
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
                    parse = MrzParser.parse(candidate, referenceTimeProvider()),
                    recognizedText = recognizedText,
                    quality = quality,
                )
            }
        return result.also(::recordTelemetry)
    }

    // Finds the first run of consecutive recognized lines whose (count, length) — after mode-specific
    // normalization — exactly matches a known ICAO MRZ shape (TD1 3×30, TD2/MRV-B 2×36, TD3/MRV-A
    // 2×44), and returns that run as the candidate; null when no run matches. Exact-shape matching is
    // deliberately conservative for this slice: sliding a window over a longer mis-segmented OCR run
    // is a later refinement, and the live stream's next-frame retry covers the gap meanwhile.
    private fun extractMrzCandidate(text: RecognizedText): List<String>? {
        val normalized = text.lines.map { normalizeLine(it.text) }
        var start = 0
        while (start < normalized.size) {
            val length = normalized[start].length
            var end = start + 1
            while (end < normalized.size && normalized[end].length == length) end++
            if (length > 0 && MrzLineShape(end - start, length) in MRZ_SHAPES) {
                return normalized.subList(start, end).toList()
            }
            start = end
        }
        return null
    }

    private fun normalizeLine(raw: String): String =
        when (mode) {
            ParsingMode.STRICT -> raw.trim().uppercase()
            ParsingMode.LENIENT -> raw.filterNot(Char::isWhitespace).uppercase()
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

    private data class MrzLineShape(
        val lineCount: Int,
        val lineLength: Int,
    )

    private companion object {
        // The distinct ICAO line shapes, sourced from mrz-core's format specs rather than restated as
        // magic numbers: TD1 3×30, TD2/MRV-B 2×36, TD3/MRV-A 2×44.
        private val MRZ_SHAPES: Set<MrzLineShape> =
            listOf(Td1FormatSpec, Td2FormatSpec, Td3FormatSpec, MrvAFormatSpec, MrvBFormatSpec)
                .map { MrzLineShape(it.lineCount, it.lineLength) }
                .toSet()
    }
}
