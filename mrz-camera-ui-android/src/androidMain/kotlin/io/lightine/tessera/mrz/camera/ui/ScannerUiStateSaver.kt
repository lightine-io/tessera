package io.lightine.tessera.mrz.camera.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.RecognizedLine
import io.lightine.tessera.mrz.camera.RecognizedText
import io.lightine.tessera.mrz.camera.ScanQuality
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.vocabulary.ReadMethod

// TES-102: the flow's uiState used to live in plain `remember { mutableStateOf(...) }`
// (MrzScannerScreen.ScannerFlow) — every configuration change (rotation, font-scale, locale) recreates the
// Activity and, with nothing but `remember`, discards the whole flow: a user mid-review or mid-manual-entry
// on a config change was silently bounced back to the start screen. This file is the `rememberSaveable`
// Saver that fixes it, split into a pure, Compose-free codec (encodeScannerUiState / decodeScannerUiState —
// host-unit-testable with no Bundle, no Activity, no rotation) plus the thin Saver built from it. Bundles
// only hold a fixed set of primitive-ish types, so every variant is flattened to a `List<Any?>` of String /
// Boolean / Int / Float / nested ArrayList<String|Float> — never the rich domain objects themselves.
//
// Not every variant restores itself unchanged (reader, not oracle still holds — restoration never alters
// what the SDK reported, but it CAN honestly decline to resurrect a session-bound in-flight state):
//  * Scanning.gathering is never restored (`false` on restore) — it describes the frame-agreement consensus
//    live over the (now-dead) camera stream, so resurrecting it would claim a wait that is not happening;
//    Scanning.struggling IS restored — it is a latched "we've been trying a while" hint, honest to keep.
//  * SavedImageAnalyzing restores as AwaitingSavedImagePick — the analysis coroutine
//    (`rememberCoroutineScope`) that would deliver a result died with the composition, so a restored
//    "Analyzing…" screen would spin forever with nothing left to finish it; AwaitingSavedImagePick's own
//    LaunchedEffect honestly re-launches the picker instead.
//  * Review re-parses its saved raw lines via MrzParser (pure and deterministic) and restamps the read
//    method the camera/photo/manual path actually used — see [decodeReview] for the full rationale.

private const val TAG_SCANNING = "Scanning"
private const val TAG_CAMERA_IN_USE = "CameraInUse"
private const val TAG_CAMERA_UNAVAILABLE = "CameraUnavailable"
private const val TAG_AWAITING_SAVED_IMAGE_PICK = "AwaitingSavedImagePick"
private const val TAG_SAVED_IMAGE_ANALYZING = "SavedImageAnalyzing"
private const val TAG_SAVED_IMAGE_EMPTY = "SavedImageEmpty"
private const val TAG_MANUAL_RAW = "ManualRaw"
private const val TAG_READ_FAILED = "ReadFailed"
private const val TAG_REVIEW = "Review"

/**
 * Flattens [state] to a `List<Any?>` of Bundle-safe primitives (String, Boolean, Int, Float, and nested
 * `ArrayList` of those) — the save half of [ScannerUiStateSaver], pulled out as a pure function so the
 * encoding is host-unit-testable with no `Saver`, no Bundle, and no Compose runtime. The first element is
 * always a tag identifying the variant; [decodeScannerUiState] dispatches on it. See the file header for
 * which variants restore as something other than themselves.
 */
internal fun encodeScannerUiState(state: ScannerUiState): List<Any?> =
    when (state) {
        // gathering is deliberately NOT encoded — it always restores false (decodeScannerUiState), so there
        // is nothing worth spending a slot on.
        is ScannerUiState.Scanning -> {
            listOf(TAG_SCANNING, state.struggling)
        }

        ScannerUiState.CameraInUse -> {
            listOf(TAG_CAMERA_IN_USE)
        }

        ScannerUiState.CameraUnavailable -> {
            listOf(TAG_CAMERA_UNAVAILABLE)
        }

        ScannerUiState.AwaitingSavedImagePick -> {
            listOf(TAG_AWAITING_SAVED_IMAGE_PICK)
        }

        // Tagged distinctly from AwaitingSavedImagePick (rather than reusing its tag at encode time) so the
        // substitution is visible and documented at the decode site, not silently folded in here.
        ScannerUiState.SavedImageAnalyzing -> {
            listOf(TAG_SAVED_IMAGE_ANALYZING)
        }

        ScannerUiState.SavedImageEmpty -> {
            listOf(TAG_SAVED_IMAGE_EMPTY)
        }

        is ScannerUiState.ManualRaw -> {
            listOf(TAG_MANUAL_RAW, state.text, state.parseFailed)
        }

        is ScannerUiState.ReadFailed -> {
            val (texts, confidences) = encodeRecognizedText(state.capturedText)
            listOf(TAG_READ_FAILED, texts, confidences)
        }

        is ScannerUiState.Review -> {
            encodeReview(state)
        }
    }

/**
 * Rebuilds a [ScannerUiState] from [saved] (as produced by [encodeScannerUiState]), or `null` when the
 * payload is unreadable — an unknown tag, the wrong arity for its tag, a value of the wrong type, or (for
 * [ScannerUiState.Review]) an enum name or raw-line set that no longer reconstructs a valid reading.
 * [ScannerUiStateSaver] then falls back to `rememberSaveable`'s own init lambda on `null`, so a corrupt or
 * from-a-future-version payload degrades to "start the flow over" rather than crashing restoration.
 */
internal fun decodeScannerUiState(saved: List<Any?>): ScannerUiState? {
    val tag = saved.getOrNull(0) as? String ?: return null
    return when (tag) {
        TAG_SCANNING -> {
            if (saved.size != 2) return null
            val struggling = saved[1] as? Boolean ?: return null
            // gathering always restores false: the frame-agreement consensus it reflects is live-session
            // state over a camera stream that died with the old composition (see the file header).
            ScannerUiState.Scanning(struggling = struggling, gathering = false)
        }

        TAG_CAMERA_IN_USE -> {
            ScannerUiState.CameraInUse.takeIf { saved.size == 1 }
        }

        TAG_CAMERA_UNAVAILABLE -> {
            ScannerUiState.CameraUnavailable.takeIf { saved.size == 1 }
        }

        TAG_AWAITING_SAVED_IMAGE_PICK -> {
            ScannerUiState.AwaitingSavedImagePick.takeIf { saved.size == 1 }
        }

        // The substitution: an in-flight analysis has no coroutine left to finish it, so restore the honest
        // re-offer instead of a screen that would spin forever (see the file header).
        TAG_SAVED_IMAGE_ANALYZING -> {
            ScannerUiState.AwaitingSavedImagePick.takeIf { saved.size == 1 }
        }

        TAG_SAVED_IMAGE_EMPTY -> {
            ScannerUiState.SavedImageEmpty.takeIf { saved.size == 1 }
        }

        TAG_MANUAL_RAW -> {
            if (saved.size != 3) return null
            val text = saved[1] as? String ?: return null
            val parseFailed = saved[2] as? Boolean ?: return null
            ScannerUiState.ManualRaw(text = text, parseFailed = parseFailed)
        }

        TAG_READ_FAILED -> {
            if (saved.size != 3) return null
            val texts = (saved[1] as? List<*>)?.let(::asStringListOrNull) ?: return null
            val confidences = (saved[2] as? List<*>)?.let(::asFloatListOrNull) ?: return null
            val capturedText = decodeRecognizedText(texts, confidences) ?: return null
            ScannerUiState.ReadFailed(capturedText = capturedText)
        }

        TAG_REVIEW -> {
            decodeReview(saved)
        }

        else -> {
            null
        }
    }
}

/** The [Saver] used by `ScannerFlow`'s `uiState`, delegating to the pure [encodeScannerUiState] / [decodeScannerUiState] codec above. */
internal val ScannerUiStateSaver: Saver<ScannerUiState, Any> =
    listSaver(
        save = { state -> encodeScannerUiState(state) },
        restore = { saved -> decodeScannerUiState(saved) },
    )

// ---------------------------------------------------------------------------------------------------------
// RecognizedText / RecognizedLine — shared by ReadFailed and Review. Bundles cannot hold a null inside a
// float list, so a missing per-line confidence (RecognizedLine.confidence == null) is encoded as NaN and
// decoded back to null (takeUnless { it.isNaN() }) — NaN is never a genuine confidence (ScanQuality /
// RecognizedLine document confidence as [0, 1]), so it is an unambiguous sentinel.
// ---------------------------------------------------------------------------------------------------------

private fun encodeRecognizedText(text: RecognizedText): Pair<ArrayList<String>, ArrayList<Float>> {
    val texts = ArrayList<String>(text.lines.size)
    val confidences = ArrayList<Float>(text.lines.size)
    for (line in text.lines) {
        texts.add(line.text)
        confidences.add(line.confidence ?: Float.NaN)
    }
    return texts to confidences
}

private fun decodeRecognizedText(
    texts: List<String>,
    confidences: List<Float>,
): RecognizedText? {
    if (texts.size != confidences.size) return null
    val lines =
        texts.indices.map { i ->
            RecognizedLine(text = texts[i], confidence = confidences[i].takeUnless(Float::isNaN))
        }
    return RecognizedText(lines)
}

private fun asStringListOrNull(list: List<*>): List<String>? {
    val typed = list.filterIsInstance<String>()
    return typed.takeIf { it.size == list.size }
}

private fun asFloatListOrNull(list: List<*>): List<Float>? {
    val typed = list.filterIsInstance<Float>()
    return typed.takeIf { it.size == list.size }
}

// ---------------------------------------------------------------------------------------------------------
// Review — the one variant whose restore does real reconstruction rather than a straight rebuild.
// ---------------------------------------------------------------------------------------------------------

/**
 * Encodes a [ScannerUiState.Review]. `decoded.parse` here is always [ParseResult.Success] or
 * [ParseResult.PartialSuccess] (a [ParseResult.Failure] never reaches [ScannerUiState.Review] — [routeDecode]
 * sends a failure to [ScannerUiState.ReadFailed] instead), so [MrzDocument.rawLines][io.lightine.tessera.mrz.model.MrzDocument.rawLines]
 * is always available in practice. The `Failure` branch below is defensive only: it encodes an empty raw-line
 * list, which [decodeReview] then fails to re-parse into anything but [ParseResult.Failure] — falling
 * through to the same "return null, let the flow restart" path a corrupt payload takes, rather than special-
 * casing an input shape that should be unreachable.
 *
 * Saved, alongside the raw lines: the recognized-text lines (parallel `texts` / `confidences` lists, the
 * same NaN-sentinel scheme [ReadFailed] uses), [ScanQuality]'s three fields (`ocrConfidence` via the same
 * sentinel), the parse's [`ReadMethod`][ReadMethod] by name, [ScannerUiState.Review.source] by name, and
 * [ScannerUiState.Review.expanded].
 */
private fun encodeReview(state: ScannerUiState.Review): List<Any?> {
    val decoded = state.decoded
    val rawLines =
        when (val parse = decoded.parse) {
            is ParseResult.Success -> parse.document.rawLines
            is ParseResult.PartialSuccess -> parse.document.rawLines
            is ParseResult.Failure -> emptyList()
        }
    val (texts, confidences) = encodeRecognizedText(decoded.recognizedText)
    val quality = decoded.quality
    return listOf(
        TAG_REVIEW,
        ArrayList(rawLines),
        texts,
        confidences,
        quality.mrzRegionFound,
        quality.ocrConfidence ?: Float.NaN,
        quality.recognizedLineCount,
        decoded.parse.metadata.readMethod.name,
        state.source.name,
        state.expanded,
    )
}

/**
 * Rebuilds a [ScannerUiState.Review] from its saved payload by RE-PARSING the saved raw lines with
 * [MrzParser.parse] — parsing is pure and deterministic over lines that already parsed once (they reached
 * [ScannerUiState.Review] in the first place), so replaying it reconstructs the identical
 * [`MrzDocument`][io.lightine.tessera.mrz.model.MrzDocument] without persisting the whole verdict (a far
 * heavier carrier than nine primitives). Only [ResultMetadata.readMethod][io.lightine.tessera.mrz.parsing.ResultMetadata]
 * then needs restamping: the parser only ever sees strings, so it always reports `BACKEND_STRING_INPUT`, but
 * the saved payload remembers whether this reading actually came from the camera, a saved photo, or manual
 * entry — restamping is the identical move `mrz-core`'s `ManualMrzReader.withReadMethod` and
 * `mrz-camera-core`'s `MrzFrameAnalyzer.withProvenance` already make for the live paths: only the read method
 * changes, the parser's verdict (document, warnings, validation failures) is surfaced unchanged (Principle
 * 1). The rebuilt [MrzScanResult.Decoded] carries the SAVED [RecognizedText] and [ScanQuality] — not
 * synthesized ones — so what the user saw before the configuration change is exactly what they see after.
 *
 * One accepted, documented edge: the re-parse runs against a fresh `referenceTime` (`Clock.System.now()`,
 * [MrzParser.parse]'s default), so date-window (century) inference could in principle differ from the
 * original parse if a restore happens to straddle a resolution-boundary instant. The alternative —
 * persisting the whole verdict instead of replaying the parse — is a far heavier carrier for a window this
 * narrow, so this is accepted rather than engineered around.
 *
 * Defensive at every step: an unreadable field, an enum name [ReadMethod] / [ScanMethod] no longer
 * recognizes, or (should the saved raw lines somehow fail to re-parse — see [encodeReview]'s `Failure`
 * branch) a re-parse that comes back [ParseResult.Failure] all return `null`, so the flow falls back to its
 * initial state rather than showing a corrupted review.
 */
private fun decodeReview(saved: List<Any?>): ScannerUiState.Review? {
    if (saved.size != 10) return null
    val rawLines = (saved[1] as? List<*>)?.let(::asStringListOrNull) ?: return null
    val texts = (saved[2] as? List<*>)?.let(::asStringListOrNull) ?: return null
    val confidences = (saved[3] as? List<*>)?.let(::asFloatListOrNull) ?: return null
    val mrzRegionFound = saved[4] as? Boolean ?: return null
    val ocrConfidenceRaw = saved[5] as? Float ?: return null
    val recognizedLineCount = saved[6] as? Int ?: return null
    val readMethodName = saved[7] as? String ?: return null
    val sourceName = saved[8] as? String ?: return null
    val expanded = saved[9] as? Boolean ?: return null

    val recognizedText = decodeRecognizedText(texts, confidences) ?: return null
    val readMethod = ReadMethod.entries.find { it.name == readMethodName } ?: return null
    val source = ScanMethod.entries.find { it.name == sourceName } ?: return null

    val restamped =
        when (val reparsed = MrzParser.parse(rawLines)) {
            is ParseResult.Success -> reparsed.copy(metadata = reparsed.metadata.copy(readMethod = readMethod))

            is ParseResult.PartialSuccess -> reparsed.copy(metadata = reparsed.metadata.copy(readMethod = readMethod))

            // Should be unreachable for lines that already parsed once (see encodeReview's KDoc) —
            // defensive only. Falling back to null (rather than e.g. ReadFailed) keeps this codec's contract
            // simple: it only ever produces a Review or nothing.
            is ParseResult.Failure -> return null
        }

    val quality =
        ScanQuality(
            mrzRegionFound = mrzRegionFound,
            ocrConfidence = ocrConfidenceRaw.takeUnless(Float::isNaN),
            recognizedLineCount = recognizedLineCount,
        )
    val decoded = MrzScanResult.Decoded(parse = restamped, recognizedText = recognizedText, quality = quality)
    return ScannerUiState.Review(decoded = decoded, source = source, expanded = expanded)
}
