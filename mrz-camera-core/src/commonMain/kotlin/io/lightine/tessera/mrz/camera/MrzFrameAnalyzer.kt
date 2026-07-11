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
    // known ICAO MRZ shape (TD1 3×30, TD2/MRV-B 2×36, TD3/MRV-A 2×44), and returns it as the candidate;
    // null when none matches. For each shape (lineCount L × lineWidth W) it slides a window of L consecutive
    // lines and takes the first where every line is exactly W long. Windowing — rather than requiring a whole
    // equal-length *run* to itself be the shape — tolerates the printed noise a live camera OCRs around the
    // zone (place-of-birth, blood group, the legal paragraph, device-observed): a stray line of the same
    // width no longer inflates the run past L and breaks the match, and the shape is still found among longer
    // output. Width stays EXACT (never padded to fit) so every candidate is parseable and no data is inferred
    // (Principle 1) — a frame where OCR genuinely dropped or split an MRZ line simply does not match, and the
    // next frame covers it. Larger shapes first (more lines ⇒ more specific) for a deterministic pick.
    private fun extractMrzCandidate(text: RecognizedText): List<String>? {
        val normalized = text.lines.map { normalizeLine(it.text) }
        for (shape in MRZ_SHAPES_BY_SPECIFICITY) {
            for (start in 0..normalized.size - shape.lineCount) {
                if ((0 until shape.lineCount).all { normalized[start + it].isMrzLineOf(shape.lineLength) }) {
                    return normalized.subList(start, start + shape.lineCount).toList()
                }
            }
        }
        return null
    }

    // A normalized line qualifies for a shape's window when it is exactly the shape's width AND every
    // character is in the MRZ alphabet (A–Z, 0–9, `<`). The alphabet guard is what lets windowing pick the
    // real zone out of same-width printed noise: a line of the right length but carrying punctuation, digits'
    // separators, or lowercase left over from surrounding text (already upper-folded and chevron-recovered by
    // normalizeLine) is rejected, so the window lands on the actual MRZ pair/triple rather than a neighbour.
    private fun String.isMrzLineOf(width: Int): Boolean = length == width && all { it in 'A'..'Z' || it in '0'..'9' || it == '<' }

    // Case is folded to upper (the MRZ alphabet is uppercase-only), and out-of-alphabet chevron glyphs an OCR
    // engine emits for the filler `<` (e.g. ML Kit reads the chevron as `«`) are recovered to `<`. Both are
    // glyph *recovery* — the source can only have been the one intended character, not a choice between two
    // valid ones — so they hold in both modes; whitespace is forgiven only in LENIENT. The raw OCR text is
    // preserved on the result (Principle 5); only the parse candidate is normalized.
    private fun normalizeLine(raw: String): String {
        val cased =
            when (mode) {
                ParsingMode.STRICT -> raw.trim().uppercase()
                ParsingMode.LENIENT -> raw.filterNot(Char::isWhitespace).uppercase()
            }
        return MRZ_CHEVRON_GLYPHS.fold(cased) { line, chevron -> line.replace(chevron, '<') }
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
        // Out-of-alphabet chevron / guillemet glyphs an OCR engine emits for the MRZ filler `<` (device-
        // observed: ML Kit reads the chevron as `«`). None are part of the MRZ alphabet (A–Z, 0–9, `<`), so
        // mapping them to `<` recovers the only glyph they can be — like folding case to upper, not choosing
        // between two valid characters (reader-not-oracle holds: no data is inferred, the raw OCR text is
        // preserved on every result). Deliberately narrow — only unambiguous chevron look-alikes, never an
        // in-alphabet character.
        private val MRZ_CHEVRON_GLYPHS: Set<Char> = setOf('«', '»', '‹', '›', '＜', '＞', '〈', '〉', '⟨', '⟩')

        // The distinct ICAO line shapes, sourced from mrz-core's format specs rather than restated as
        // magic numbers: TD1 3×30, TD2/MRV-B 2×36, TD3/MRV-A 2×44. Ordered by line count descending so the
        // window search tries the more specific (more lines) shape first — deterministic when output could
        // otherwise satisfy more than one.
        private val MRZ_SHAPES_BY_SPECIFICITY: List<MrzLineShape> =
            listOf(Td1FormatSpec, Td2FormatSpec, Td3FormatSpec, MrvAFormatSpec, MrvBFormatSpec)
                .map { MrzLineShape(it.lineCount, it.lineLength) }
                .distinct()
                .sortedByDescending { it.lineCount }
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
