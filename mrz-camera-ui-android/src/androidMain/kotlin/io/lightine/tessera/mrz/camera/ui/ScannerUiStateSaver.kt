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
import kotlin.time.Clock
import kotlin.time.Instant

// TES-102: the flow's uiState used to live in plain `remember { mutableStateOf(...) }`
// (MrzScannerScreen.ScannerFlow) — every configuration change (rotation, font-scale, locale) recreates the
// Activity and, with nothing but `remember`, discards the whole flow: a user mid-review or mid-manual-entry
// on a config change was silently bounced back to the start screen. This file is the `rememberSaveable`
// Saver that fixes it, split into a pure, Compose-free codec (encodeScannerUiState / decodeScannerUiState —
// host-unit-testable with no Bundle, no Activity, no rotation) plus the thin Saver ([scannerUiStateSaver])
// built from it. Bundles only hold a fixed set of primitive-ish types, so every variant is flattened to a
// `List<Any?>` of String / Boolean / Int / Float / Long / nested ArrayList<String|Float> — never the rich
// domain objects themselves.
//
// Not every variant restores itself unchanged (reader, not oracle still holds — restoration never alters
// what the SDK reported, but it CAN honestly decline to resurrect a session-bound in-flight state):
//  * Scanning ALWAYS restores with both overlays false, never as whatever it was showing before the
//    recreation — see [decodeScannerUiState]'s TAG_SCANNING branch for why `struggling` is not honest to
//    resurrect either, alongside `gathering`.
//  * SavedImageAnalyzing restores as AwaitingSavedImagePick — the analysis coroutine
//    (`rememberCoroutineScope`) that would deliver a result died with the composition, so a restored
//    "Analyzing…" screen would spin forever with nothing left to finish it; AwaitingSavedImagePick's own
//    LaunchedEffect honestly re-launches the picker instead (guarded, in `ScannerFlow`, by a saveable latch
//    so a bare restore does not silently re-fire that picker with no new user gesture — see
//    `savedImagePickAutoLaunched` there).
//  * Review re-parses its saved raw lines via MrzParser (pure and deterministic), pinned to the CLOCK READING
//    TAKEN AT ENCODE TIME rather than a fresh restore-time clock, and restamps the read method the
//    camera/photo/manual path actually used — see [decodeReview] for the full rationale.
//
// On top of the codec, [scannerUiStateSaver] re-validates every decoded state against the consumer's
// CURRENT `enabledMethods` before letting it restore ([gateRestoredState]) — see that function's KDoc for
// why a Saver that restored unconditionally would have regressed a guarantee `initialState` already gave a
// FRESH flow.

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
 * Flattens [state] to a `List<Any?>` of Bundle-safe primitives (String, Boolean, Int, Float, Long, and nested
 * `ArrayList` of those) — the save half of [scannerUiStateSaver], pulled out as a pure function so the
 * encoding is host-unit-testable with no `Saver`, no Bundle, and no Compose runtime. The first element is
 * always a tag identifying the variant; [decodeScannerUiState] dispatches on it. See the file header for
 * which variants restore as something other than themselves.
 */
internal fun encodeScannerUiState(state: ScannerUiState): List<Any?> =
    when (state) {
        // Neither struggling nor gathering is encoded — Scanning always restores with both false
        // (decodeScannerUiState's TAG_SCANNING branch), so there is nothing worth spending a slot on for
        // either flag.
        is ScannerUiState.Scanning -> {
            listOf(TAG_SCANNING)
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
 * [scannerUiStateSaver] then falls back to `rememberSaveable`'s own init lambda on `null`, so a corrupt or
 * from-a-future-version payload degrades to "start the flow over" rather than crashing restoration. Note
 * that this function alone does NOT apply the config-aware gate ([gateRestoredState]) — [scannerUiStateSaver]
 * composes the two; call sites that want the gate applied (i.e. every real `rememberSaveable` restore) go
 * through the Saver, not this function directly.
 */
internal fun decodeScannerUiState(saved: List<Any?>): ScannerUiState? {
    val tag = saved.getOrNull(0) as? String ?: return null
    return when (tag) {
        TAG_SCANNING -> {
            // Both overlays restore false, never whatever they were before the recreation:
            //  * gathering describes the frame-agreement consensus live over the (now-dead) camera stream, so
            //    resurrecting it would claim a wait that is not happening (unchanged reasoning from before
            //    TES-102's gate-review — see the file header).
            //  * struggling used to be restored (it reads as a latched "we've been trying a while" hint), but
            //    that contradicts the TES-97 gate it exists to serve: the overlay is only honest to show once
            //    OCR has actually returned text THIS session (`sawTextEver`) AND the struggle timeout has
            //    elapsed THIS session (`struggleTimeoutElapsed`) — both plain, non-Saveable `remember` state in
            //    `ScannerFlow`, which reset to false on every recreation along with the camera itself. A
            //    restored `struggling = true` would show the "still looking / type it instead" hint over a
            //    brand-new session that has analyzed exactly zero frames — worse than the plain framing guide
            //    it would replace. The fresh session's own struggle timer and gates re-latch it honestly (and
            //    quickly, if the document really is still hard to read) rather than resurrecting a stale claim.
            if (saved.size != 1) return null
            ScannerUiState.Scanning(struggling = false, gathering = false)
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

/**
 * Re-validates a decoded [state] against the CURRENT [enabledMethods] before letting it restore — TES-102's
 * config-aware restore gate, applied by [scannerUiStateSaver] after [decodeScannerUiState].
 *
 * A `rememberSaveable` restore fires on EVERY Activity recreation (rotation, font-scale, locale) — the exact
 * same trigger [initialState] already re-derives a FRESH flow's start screen from. Before this gate, the
 * Saver restored the decoded state unconditionally, which regressed that guarantee: a host whose
 * `enabledMethods` can change between sessions (e.g. a compliance host drops
 * [`MANUAL_ENTRY`][ScanMethod.MANUAL_ENTRY]) would still have a saved [ScannerUiState.ManualRaw] resurrect
 * into a fully working entry screen on the very next rotation, because the Bundle remembers nothing about
 * which methods are still allowed. This function closes that gap: it returns `null` — the same "give up,
 * let the flow restart" signal a corrupt payload already produces in [decodeScannerUiState] — whenever
 * [state]'s owning method is not in [enabledMethods], so `rememberSaveable`'s init lambda re-derives
 * [initialState] from the CURRENT config instead. It returns [state] unchanged otherwise.
 *
 * Owning method per variant:
 *  * [`Scanning`][ScannerUiState.Scanning] / [`CameraInUse`][ScannerUiState.CameraInUse] /
 *    [`CameraUnavailable`][ScannerUiState.CameraUnavailable] → [`CAMERA`][ScanMethod.CAMERA];
 *  * [`AwaitingSavedImagePick`][ScannerUiState.AwaitingSavedImagePick] /
 *    [`SavedImageEmpty`][ScannerUiState.SavedImageEmpty] / [`ReadFailed`][ScannerUiState.ReadFailed] →
 *    [`SAVED_IMAGE`][ScanMethod.SAVED_IMAGE] ([ScannerUiState.ReadFailed] is only ever reached from the
 *    saved-image flow — see `backEffect`'s KDoc in `MrzScannerScreen.kt`);
 *  * [`ManualRaw`][ScannerUiState.ManualRaw] → [`MANUAL_ENTRY`][ScanMethod.MANUAL_ENTRY];
 *  * [`Review`][ScannerUiState.Review] → its own [`source`][ScannerUiState.Review.source] — a
 *    not-yet-accepted review whose producing method the host has since disabled restores as a fresh start on
 *    an allowed method rather than offering a "Rescan" into a method that no longer exists.
 *
 * [ScannerUiState.SavedImageAnalyzing] is included only for `when` exhaustiveness: [decodeScannerUiState]
 * never actually produces it (it decodes to [ScannerUiState.AwaitingSavedImagePick] — see the file header),
 * so this branch is unreachable in practice, not a case this function meaningfully decides.
 *
 * No special-casing an empty [enabledMethods]: every owning method then fails the `in` check and every state
 * gates to `null` uniformly. [initialState]'s own defensive "empty set → camera" fallback already owns that
 * edge case for a fresh flow, so this function does not need to duplicate it for a restored one.
 */
internal fun gateRestoredState(
    state: ScannerUiState,
    enabledMethods: Set<ScanMethod>,
): ScannerUiState? {
    val owningMethod =
        when (state) {
            is ScannerUiState.Scanning,
            ScannerUiState.CameraInUse,
            ScannerUiState.CameraUnavailable,
            -> ScanMethod.CAMERA

            ScannerUiState.AwaitingSavedImagePick,
            ScannerUiState.SavedImageEmpty,
            is ScannerUiState.ReadFailed,
            -> ScanMethod.SAVED_IMAGE

            is ScannerUiState.ManualRaw -> ScanMethod.MANUAL_ENTRY

            is ScannerUiState.Review -> state.source

            // Unreachable — see the KDoc above.
            ScannerUiState.SavedImageAnalyzing -> ScanMethod.SAVED_IMAGE
        }
    return state.takeIf { owningMethod in enabledMethods }
}

/**
 * Builds the [Saver] `ScannerFlow` uses for its `uiState`, composing [decodeScannerUiState] (the pure codec)
 * with [gateRestoredState] (the config-aware restore gate, TES-102). Takes [enabledMethods] as a parameter —
 * rather than being a single top-level `val` the way the pre-gate Saver was — precisely so `ScannerFlow` can
 * build it fresh from the consumer's CURRENT [`MrzScannerConfig.enabledMethods`][MrzScannerConfig.enabledMethods]
 * at composition time; a stale, captured-once `enabledMethods` would defeat the whole point of the gate.
 */
internal fun scannerUiStateSaver(enabledMethods: Set<ScanMethod>): Saver<ScannerUiState, Any> =
    listSaver(
        save = { state -> encodeScannerUiState(state) },
        restore = { saved -> decodeScannerUiState(saved)?.let { gateRestoredState(it, enabledMethods) } },
    )

// ---------------------------------------------------------------------------------------------------------
// RecognizedText / RecognizedLine — shared by ReadFailed and Review. Bundles cannot hold a null inside a
// float list, so a missing per-line confidence (RecognizedLine.confidence == null) is encoded as NaN and
// decoded back to null via [encodeNullableFloat] / [decodeNullableFloat] — NaN is never a genuine confidence
// (ScanQuality / RecognizedLine document confidence as [0, 1]), so it is an unambiguous sentinel.
// ---------------------------------------------------------------------------------------------------------

/**
 * Encodes a nullable confidence-like `Float?` as a Bundle-safe non-null `Float`: `null` becomes [Float.NaN].
 * Paired with [decodeNullableFloat]. Used for both [RecognizedLine.confidence] and [ScanQuality.ocrConfidence]
 * — both are documented as `[0, 1]` or `null`, so `NaN` can never collide with a genuine value.
 */
private fun encodeNullableFloat(value: Float?): Float = value ?: Float.NaN

/** The decode half of [encodeNullableFloat]: `NaN` decodes back to `null`, any other value passes through. */
private fun decodeNullableFloat(value: Float): Float? = value.takeUnless(Float::isNaN)

private fun encodeRecognizedText(text: RecognizedText): Pair<ArrayList<String>, ArrayList<Float>> {
    val texts = ArrayList<String>(text.lines.size)
    val confidences = ArrayList<Float>(text.lines.size)
    for (line in text.lines) {
        texts.add(line.text)
        confidences.add(encodeNullableFloat(line.confidence))
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
            RecognizedLine(text = texts[i], confidence = decodeNullableFloat(confidences[i]))
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
 * sentinel), the parse's [`ReadMethod`][ReadMethod] by name, [ScannerUiState.Review.source] by name,
 * [ScannerUiState.Review.expanded], and — appended LAST, so every earlier index-based test and decode slot
 * is undisturbed — the wall-clock instant AT ENCODE TIME, in epoch milliseconds. See [decodeReview] for why
 * that instant (rather than a fresh restore-time clock) is what the re-parse is pinned to.
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
        encodeNullableFloat(quality.ocrConfidence),
        quality.recognizedLineCount,
        decoded.parse.metadata.readMethod.name,
        state.source.name,
        state.expanded,
        Clock.System.now().toEpochMilliseconds(),
    )
}

/**
 * Rebuilds a [ScannerUiState.Review] from its saved payload by RE-PARSING the saved raw lines with
 * [MrzParser.parse] — parsing is pure and deterministic over lines that already parsed once (they reached
 * [ScannerUiState.Review] in the first place), so replaying it reconstructs the identical
 * [`MrzDocument`][io.lightine.tessera.mrz.model.MrzDocument] without persisting the whole verdict (a far
 * heavier carrier than eleven primitives). Only [ResultMetadata.readMethod][io.lightine.tessera.mrz.parsing.ResultMetadata]
 * then needs restamping: the parser only ever sees strings, so it always reports `BACKEND_STRING_INPUT`, but
 * the saved payload remembers whether this reading actually came from the camera, a saved photo, or manual
 * entry — restamping is the identical move `mrz-core`'s `ManualMrzReader.withReadMethod` and
 * `mrz-camera-core`'s `MrzFrameAnalyzer.withProvenance` already make for the live paths: only the read method
 * changes, the parser's verdict (document, warnings, validation failures) is surfaced unchanged (Principle
 * 1). The rebuilt [MrzScanResult.Decoded] carries the SAVED [RecognizedText] and [ScanQuality] — not
 * synthesized ones — so what the user saw before the configuration change is exactly what they see after.
 *
 * The re-parse's `referenceTime` is PINNED to the wall-clock instant [encodeReview] captured at encode time
 * (the saved payload's last slot), not a fresh `Clock.System.now()` at restore time. `referenceTime` feeds
 * both the date-window (century) inference AND — via [ResultMetadata.warnings] — computed observations like
 * an expiry-past delta; re-parsing at a fresh restore-time clock could, across a long process-death restore,
 * report a warning that has silently changed shape from what the user was actually looking at (a passport a
 * few days from expiry could cross that boundary between review and restore, for instance). Pinning to the
 * encode-time clock reconstructs the review as-of (approximately) the last moment the user actually saw it.
 * The residual imprecision — encode-time versus the ORIGINAL parse-time, which `ResultMetadata` does not
 * carry forward for this codec to recover — is at most a few seconds within one live session, a far narrower
 * window than the unbounded restore-time drift this replaces.
 *
 * Defensive at every step: an unreadable field, an enum name [ReadMethod] / [ScanMethod] no longer
 * recognizes, or (should the saved raw lines somehow fail to re-parse — see [encodeReview]'s `Failure`
 * branch) a re-parse that comes back [ParseResult.Failure] all return `null`, so the flow falls back to its
 * initial state rather than showing a corrupted review.
 */
private fun decodeReview(saved: List<Any?>): ScannerUiState.Review? {
    if (saved.size != 11) return null
    val rawLines = (saved[1] as? List<*>)?.let(::asStringListOrNull) ?: return null
    val texts = (saved[2] as? List<*>)?.let(::asStringListOrNull) ?: return null
    val confidences = (saved[3] as? List<*>)?.let(::asFloatListOrNull) ?: return null
    val mrzRegionFound = saved[4] as? Boolean ?: return null
    val ocrConfidenceRaw = saved[5] as? Float ?: return null
    val recognizedLineCount = saved[6] as? Int ?: return null
    val readMethodName = saved[7] as? String ?: return null
    val sourceName = saved[8] as? String ?: return null
    val expanded = saved[9] as? Boolean ?: return null
    val referenceTimeMillis = saved[10] as? Long ?: return null

    val recognizedText = decodeRecognizedText(texts, confidences) ?: return null
    val readMethod = ReadMethod.entries.find { it.name == readMethodName } ?: return null
    val source = ScanMethod.entries.find { it.name == sourceName } ?: return null
    val referenceTime = Instant.fromEpochMilliseconds(referenceTimeMillis)

    val reparsed = MrzParser.parse(rawLines, referenceTime)
    // Hoisted once, from `metadata` on ParseResult's sealed BASE (every variant carries it — no cast
    // needed) — rather than re-deriving `reparsed.metadata.copy(readMethod = readMethod)` independently in
    // each success-shaped branch below, the way the pre-cleanup version did.
    val stamped = reparsed.metadata.copy(readMethod = readMethod)
    val restamped =
        when (reparsed) {
            is ParseResult.Success -> reparsed.copy(metadata = stamped)

            is ParseResult.PartialSuccess -> reparsed.copy(metadata = stamped)

            // Should be unreachable for lines that already parsed once (see encodeReview's KDoc) —
            // defensive only. Falling back to null (rather than e.g. ReadFailed) keeps this codec's contract
            // simple: it only ever produces a Review or nothing.
            is ParseResult.Failure -> return null
        }

    val quality =
        ScanQuality(
            mrzRegionFound = mrzRegionFound,
            ocrConfidence = decodeNullableFloat(ocrConfidenceRaw),
            recognizedLineCount = recognizedLineCount,
        )
    val decoded = MrzScanResult.Decoded(parse = restamped, recognizedText = recognizedText, quality = quality)
    return ScannerUiState.Review(decoded = decoded, source = source, expanded = expanded)
}
