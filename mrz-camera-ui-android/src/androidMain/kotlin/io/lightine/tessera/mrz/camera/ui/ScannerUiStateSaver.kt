package io.lightine.tessera.mrz.camera.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

// TES-240: document data stays in memory only. The flow's full uiState (and the manual-entry draft) lives in
// a `retain`ed holder (ScannerFlowState.kt), which survives a configuration change (rotation, font-scale,
// locale) in memory and is gone after process death. This file is the OTHER half: the `rememberSaveable`
// Saver behind the process-death fallback, and it saves NO document data. Saved instance state leaves the
// app's process (the system keeps the Bundle and hands it back after the process is killed in the
// background), so anything put there has left the app's memory. A state that carries document data - the
// typed MRZ text, the recognized OCR lines, a parsed reading - is reduced first, by [withoutDocumentData], to
// the data-free screen the reader starts that method on, and only that is encoded. Same outcome as the iOS
// screen (tessera-swift `ScannerModel`), which keeps everything in memory.
//
// TES-102 history: before TES-240 this codec saved every variant verbatim (Review's raw lines and
// recognized text, ReadFailed's lines, ManualRaw's text) so the flow survived a configuration change. The
// configuration change is now the retained holder's job; this codec only ever has to describe "where to
// start again" after process death. It stays a pure, Compose-free codec (encodeScannerUiState /
// decodeScannerUiState - host-unit-testable with no Bundle, no Activity) plus the thin Saver
// ([scannerUiStateSaver]) built from it. Every encoded value is a Bundle-safe String / Boolean.
//
// What each variant restores as after process death:
//  * Scanning ALWAYS restores with both overlays false (see [settleAfterRecreation] - neither overlay
//    describes the fresh camera session a recreation starts).
//  * SavedImageAnalyzing restores as AwaitingSavedImagePick - the analysis coroutine
//    (`rememberCoroutineScope`) that would deliver a result died with the composition, so a restored
//    "Analyzing..." screen would spin forever; AwaitingSavedImagePick's own LaunchedEffect re-offers the
//    picker instead (see `savedImagePickAutoLaunched` in `ScannerFlow`).
//  * ManualRaw restores as an EMPTY ManualRaw (TES-240: the typed text is document data).
//  * ReadFailed restores as AwaitingSavedImagePick (TES-240: its recognized lines are document data; it is
//    only ever reached from the saved-image flow, so the reader picks a photo again).
//  * Review restores as the start screen of its own source method (TES-240: camera -> Scanning, photo ->
//    AwaitingSavedImagePick, manual -> an empty ManualRaw), so the reader scans or types again.
//  * CameraInUse, CameraUnavailable, AwaitingSavedImagePick and SavedImageEmpty restore as themselves.
//
// On top of the codec, [scannerUiStateSaver] re-validates every decoded state against the `enabledMethods`
// the flow runs on (since TES-123, the set captured when the screen opened) before letting it restore
// ([gateRestoredState]), and never restores null (TES-222).

private const val TAG_SCANNING = "Scanning"
private const val TAG_CAMERA_IN_USE = "CameraInUse"
private const val TAG_CAMERA_UNAVAILABLE = "CameraUnavailable"
private const val TAG_AWAITING_SAVED_IMAGE_PICK = "AwaitingSavedImagePick"
private const val TAG_SAVED_IMAGE_ANALYZING = "SavedImageAnalyzing"
private const val TAG_SAVED_IMAGE_EMPTY = "SavedImageEmpty"
private const val TAG_MANUAL_RAW = "ManualRaw"

/**
 * The screen a reader starts [method] on, holding no document data: camera -> [ScannerUiState.Scanning],
 * photo -> [ScannerUiState.AwaitingSavedImagePick], manual -> an empty [ScannerUiState.ManualRaw]. The same
 * targets the method switcher picks (`onSelectMethod` in `ScannerFlow`), minus the in-memory manual draft.
 */
internal fun startScreenFor(method: ScanMethod): ScannerUiState =
    when (method) {
        ScanMethod.CAMERA -> ScannerUiState.Scanning()
        ScanMethod.SAVED_IMAGE -> ScannerUiState.AwaitingSavedImagePick
        ScanMethod.MANUAL_ENTRY -> ScannerUiState.ManualRaw()
    }

/**
 * TES-240: [state] with every piece of document data removed - the form the process-death fallback may
 * hold and save. A variant that carries document data becomes the start screen of the method it belongs to,
 * so the reader scans or types again: [ScannerUiState.ManualRaw] loses its typed text (and the `parseFailed`
 * flag that described that text), [ScannerUiState.ReadFailed] (saved-image only) becomes
 * [ScannerUiState.AwaitingSavedImagePick], and [ScannerUiState.Review] becomes [startScreenFor] its
 * [`source`][ScannerUiState.Review.source]. Every other variant holds no document data and is returned
 * unchanged. Pure, so the "nothing saved carries document data" rule is host-testable.
 */
internal fun withoutDocumentData(state: ScannerUiState): ScannerUiState =
    when (state) {
        is ScannerUiState.ManualRaw -> ScannerUiState.ManualRaw()

        is ScannerUiState.ReadFailed -> ScannerUiState.AwaitingSavedImagePick

        is ScannerUiState.Review -> startScreenFor(state.source)

        is ScannerUiState.Scanning,
        ScannerUiState.CameraInUse,
        ScannerUiState.CameraUnavailable,
        ScannerUiState.AwaitingSavedImagePick,
        ScannerUiState.SavedImageAnalyzing,
        ScannerUiState.SavedImageEmpty,
        -> state
    }

/**
 * What a state becomes when the composition that showed it is rebuilt - on BOTH paths: the retained
 * in-memory state after a configuration change (ScannerFlowState.kt), and the decoded state after process
 * death ([decodeScannerUiState]). Only session-bound parts change:
 *  * [ScannerUiState.Scanning] drops both overlays. `gathering` describes the frame-agreement consensus over
 *    the (now-dead) camera stream, and `struggling` is driven by the struggle timer of the live camera
 *    session, which restarts with the camera on every recreation; showing either over a brand-new session
 *    would claim something that is not happening. The fresh session's own gates show them again if needed.
 *  * [ScannerUiState.SavedImageAnalyzing] becomes [ScannerUiState.AwaitingSavedImagePick]: the analysis
 *    coroutine died with the old composition, so nothing would ever finish the "Analyzing..." screen.
 *
 * Everything else, document data included, is returned unchanged (reader, not oracle: a recreation never
 * alters what the SDK reported).
 */
internal fun settleAfterRecreation(state: ScannerUiState): ScannerUiState =
    when (state) {
        is ScannerUiState.Scanning -> ScannerUiState.Scanning(struggling = false, gathering = false)
        ScannerUiState.SavedImageAnalyzing -> ScannerUiState.AwaitingSavedImagePick
        else -> state
    }

/**
 * Flattens [state] to a `List<Any?>` of Bundle-safe values (only a String tag) - the save half of
 * [scannerUiStateSaver], pulled out as a pure function so the encoding is host-unit-testable with no
 * `Saver`, no Bundle, and no Compose runtime. TES-240: [state] is first reduced by [withoutDocumentData], so
 * no payload ever carries typed text, recognized lines or a reading; a Review or ReadFailed is encoded as the
 * start screen it reduces to. The first element is always a tag identifying the variant;
 * [decodeScannerUiState] dispatches on it.
 */
internal fun encodeScannerUiState(state: ScannerUiState): List<Any?> =
    when (val dataFree = withoutDocumentData(state)) {
        // Neither struggling nor gathering is encoded - Scanning always restores with both false
        // ([settleAfterRecreation]), so there is nothing worth spending a slot on for either flag.
        is ScannerUiState.Scanning -> listOf(TAG_SCANNING)

        ScannerUiState.CameraInUse -> listOf(TAG_CAMERA_IN_USE)

        ScannerUiState.CameraUnavailable -> listOf(TAG_CAMERA_UNAVAILABLE)

        ScannerUiState.AwaitingSavedImagePick -> listOf(TAG_AWAITING_SAVED_IMAGE_PICK)

        // Tagged distinctly from AwaitingSavedImagePick (rather than reusing its tag at encode time) so the
        // substitution is visible and documented at the decode site, not silently folded in here.
        ScannerUiState.SavedImageAnalyzing -> listOf(TAG_SAVED_IMAGE_ANALYZING)

        ScannerUiState.SavedImageEmpty -> listOf(TAG_SAVED_IMAGE_EMPTY)

        // TES-240: the tag alone. The typed text never reaches the Bundle (withoutDocumentData emptied it).
        is ScannerUiState.ManualRaw -> listOf(TAG_MANUAL_RAW)

        // Unreachable: withoutDocumentData never returns these. Encoded as the same start screens it maps them
        // to, so the `when` stays exhaustive without a throw.
        is ScannerUiState.ReadFailed -> listOf(TAG_AWAITING_SAVED_IMAGE_PICK)

        is ScannerUiState.Review -> encodeScannerUiState(startScreenFor(dataFree.source))
    }

/**
 * Rebuilds a [ScannerUiState] from [saved] (as produced by [encodeScannerUiState]), or `null` when the
 * payload is unreadable - an unknown tag, or the wrong arity for its tag. A payload written by an app version
 * from before TES-240 (a Review / ReadFailed tag, or a ManualRaw carrying its text) is unreadable on
 * purpose: it decodes to `null`, so document data saved by an older version is never restored.
 * [scannerUiStateSaver] turns `null` into [initialState] (TES-222). Note that this function alone does NOT
 * apply the config-aware gate ([gateRestoredState]) - [scannerUiStateSaver] composes the two.
 */
internal fun decodeScannerUiState(saved: List<Any?>): ScannerUiState? {
    val tag = saved.getOrNull(0) as? String ?: return null
    if (saved.size != 1) return null
    return when (tag) {
        TAG_SCANNING -> settleAfterRecreation(ScannerUiState.Scanning())

        TAG_CAMERA_IN_USE -> ScannerUiState.CameraInUse

        TAG_CAMERA_UNAVAILABLE -> ScannerUiState.CameraUnavailable

        TAG_AWAITING_SAVED_IMAGE_PICK -> ScannerUiState.AwaitingSavedImagePick

        // The substitution: an in-flight analysis has no coroutine left to finish it, so restore the honest
        // re-offer instead of a screen that would spin forever (see the file header).
        TAG_SAVED_IMAGE_ANALYZING -> settleAfterRecreation(ScannerUiState.SavedImageAnalyzing)

        TAG_SAVED_IMAGE_EMPTY -> ScannerUiState.SavedImageEmpty

        TAG_MANUAL_RAW -> ScannerUiState.ManualRaw()

        else -> null
    }
}

/**
 * Re-validates a decoded [state] against the CURRENT [enabledMethods] before letting it restore - TES-102's
 * config-aware restore gate, applied by [scannerUiStateSaver] after [decodeScannerUiState].
 *
 * A `rememberSaveable` restore fires on EVERY Activity recreation (rotation, font-scale, locale) - the exact
 * same trigger [initialState] already re-derives a FRESH flow's start screen from. Before this gate, the
 * Saver restored the decoded state unconditionally, which regressed that guarantee: a host whose
 * `enabledMethods` can change between sessions (e.g. a compliance host drops
 * [`MANUAL_ENTRY`][ScanMethod.MANUAL_ENTRY]) would still have a saved [ScannerUiState.ManualRaw] resurrect
 * into a fully working entry screen on the very next rotation, because the Bundle remembers nothing about
 * which methods are still allowed. This function closes that gap: it returns `null` - the same "give up,
 * let the flow restart" signal a corrupt payload already produces in [decodeScannerUiState] - whenever
 * [state]'s owning method is not in [enabledMethods], so `rememberSaveable`'s init lambda re-derives
 * [initialState] from the CURRENT config instead. It returns [state] unchanged otherwise.
 *
 * Owning method per variant:
 *  * [`Scanning`][ScannerUiState.Scanning] / [`CameraInUse`][ScannerUiState.CameraInUse] /
 *    [`CameraUnavailable`][ScannerUiState.CameraUnavailable] → [`CAMERA`][ScanMethod.CAMERA];
 *  * [`AwaitingSavedImagePick`][ScannerUiState.AwaitingSavedImagePick] /
 *    [`SavedImageEmpty`][ScannerUiState.SavedImageEmpty] / [`ReadFailed`][ScannerUiState.ReadFailed] →
 *    [`SAVED_IMAGE`][ScanMethod.SAVED_IMAGE] ([ScannerUiState.ReadFailed] is only ever reached from the
 *    saved-image flow - see `backEffect`'s KDoc in `MrzScannerScreen.kt`);
 *  * [`ManualRaw`][ScannerUiState.ManualRaw] → [`MANUAL_ENTRY`][ScanMethod.MANUAL_ENTRY];
 *  * [`Review`][ScannerUiState.Review] → its own [`source`][ScannerUiState.Review.source] - a
 *    not-yet-accepted review whose producing method the host has since disabled restores as a fresh start on
 *    an allowed method rather than offering a "Rescan" into a method that no longer exists.
 *
 * TES-240: the decoded state now only seeds the flow after process death. On a configuration change the
 * retained in-memory state wins (ScannerFlowState.kt) and this gate's result is not used: that state was
 * produced in this same presentation, under the same opening config. [ScannerUiState.SavedImageAnalyzing],
 * [ScannerUiState.ReadFailed] and [ScannerUiState.Review] are included only for `when` exhaustiveness and as
 * a pure owning-method table: [decodeScannerUiState] never produces any of them (see the file header).
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

            // Never decoded - see the KDoc above.
            ScannerUiState.SavedImageAnalyzing -> ScanMethod.SAVED_IMAGE
        }
    return state.takeIf { owningMethod in enabledMethods }
}

/**
 * Builds the [Saver] behind the flow's process-death fallback (`rememberScannerFlowState`, TES-240), composing [decodeScannerUiState] (the pure codec)
 * with [gateRestoredState] (the config-aware restore gate, TES-102). A state that is unreadable or rejected by
 * the gate restores as [initialState] for [enabledMethods] (TES-222), never as null. Takes [enabledMethods] as a parameter so
 * `ScannerFlow` builds it from the config it runs on. Since TES-123 that is the config captured when the
 * screen opened ([rememberOpenedConfig]), which survives the same recreation, so the gate checks against the
 * opening set. It still matters when that capture cannot be restored and falls back to the config passed at
 * that moment: a restored state is then checked against that config, never trusted blindly.
 */
internal fun scannerUiStateSaver(enabledMethods: Set<ScanMethod>): Saver<ScannerUiState, Any> =
    listSaver(
        save = { state -> encodeScannerUiState(state) },
        // TES-222: never null. `rememberSaveable` does NOT fall back to its init lambda when restore returns
        // null; it keeps the null inside the MutableState and the screen crashes on a non-null parameter. So a
        // rejected or unreadable saved state is replaced here with the same first screen a fresh opening gets.
        restore = { saved ->
            decodeScannerUiState(saved)?.let { gateRestoredState(it, enabledMethods) } ?: initialState(enabledMethods)
        },
    )
