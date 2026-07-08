// @Composable functions are PascalCase by Compose convention, which ktlint's standard function-naming
// rule flags. Suppressed at file scope (the idiomatic per-Compose-file exemption) rather than repo-wide:
// Spotless's ktlint step does not reliably pass the editorconfig `ignore_when_annotated_with` value
// through, and forcing it via editorConfigOverride perturbs the code-style baseline for other modules.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.lightine.tessera.mrz.camera.CameraError
import io.lightine.tessera.mrz.camera.CameraXMrzScanner
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.SavedImageMrzReader
import io.lightine.tessera.mrz.camera.SavedImageReadingAcknowledgement
import io.lightine.tessera.mrz.camera.mlKitSavedImageRecognizer
import io.lightine.tessera.mrz.parsing.ParseResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * The default Android MRZ scanner screen — the single public entry point of this module. Drop it into a
 * host app to get an out-of-the-box scanning UI (live camera, saved image, and manual entry, per
 * [config]) without building one; consumers who want their own UI use the headless reading APIs directly
 * and never depend on this module.
 *
 * The screen owns the whole reading flow internally and reports back exactly once through [onResult] —
 * either a [`Confirmed`][MrzScannerResult.Confirmed] reading the user accepted or
 * [`Cancelled`][MrzScannerResult.Cancelled]. It adds no trust judgement of its own: a reading confirmed
 * here is the SDK's verbatim verdict (Principle 1).
 *
 * **Slice status:** all three reading methods are wired end to end and tied together by a shared navigation
 * scaffold (a top bar whose ✕ cancels from every screen, plus a Camera / Photo / Type method switcher on the
 * capture & entry screens — TES-71). The live-camera path routes a decoded MRZ to a review screen (parsed
 * fields + honest observations) the user accepts or rescans, a parse failure to a "couldn't read" screen,
 * [`INSTANT_RETURN`][ReviewMode.INSTANT_RETURN] returns a decode with no review step, a long spell with no
 * read overlays a neutral "still looking / type it instead" hint, and the two camera-status conditions
 * (another app holds the camera — recoverable; the camera can't start — terminal) each show a notice with a
 * manual-entry escape. Manual raw-MRZ entry and saved-image (photo) reading are wired. The flow's start
 * screen derives from [`enabledMethods`][MrzScannerConfig.enabledMethods]. The signature (one config in, one
 * result out) is the shape that freezes at the 0.5.0 tag.
 *
 * **Localization / rebranding.** All user-facing copy is drawn from this module's overridable `tessera_*`
 * string resources: a consumer app translates or rebrands any label by defining a string with the same key
 * in its own resources (Android's resource merge prefers the app's value — no API involved). The key set
 * is a frozen part of the public contract; see `res/values/strings.xml`.
 *
 * **Permission boundary.** The screen never requests the `CAMERA` permission itself; it *reads* whether
 * the permission is held and, when it is not, hands the request to the host through
 * [config.onRequestPermission][MrzScannerConfig.onRequestPermission] (`scope.md` "permission boundary").
 *
 * @param config appearance and permission-handoff options; see [MrzScannerConfig].
 * @param onResult called once when the flow ends, with the user's terminal decision.
 */
@Composable
public fun MrzScannerScreen(
    config: MrzScannerConfig,
    onResult: (MrzScannerResult) -> Unit,
) {
    TesseraScannerTheme(theme = config.theme) {
        Surface(modifier = Modifier.fillMaxSize().testTag(SCANNER_ROOT_TEST_TAG)) {
            ScannerFlow(config = config, onResult = onResult)
        }
    }
}

/** A stable semantics anchor for the screen's root, used by the host-side Compose tests. Not user-facing. */
internal const val SCANNER_ROOT_TEST_TAG: String = "tessera-mrz-scanner-root"

/** Semantics anchor for the live camera viewfinder. Not user-facing. */
internal const val VIEWFINDER_TEST_TAG: String = "tessera-mrz-viewfinder"

/** Semantics anchor for the torch (flashlight) toggle over the live preview. Matches the iOS identifier. Not user-facing. */
internal const val TORCH_TEST_TAG: String = "tessera-mrz-torch"

/** Semantics anchor for the MRZ framing-guide hint under the dashed frame. Matches the iOS identifier. Not user-facing. */
internal const val GUIDE_HINT_TEST_TAG: String = "tessera-mrz-guide-hint"

/** Semantics anchor for the camera-initializing loading state (mockup 01b). Not user-facing. */
internal const val INITIALIZING_TEST_TAG: String = "tessera-mrz-initializing"

/** Semantics anchor for the review screen (parsed fields + observations). Not user-facing. */
internal const val REVIEW_TEST_TAG: String = "tessera-mrz-review"

/** Semantics anchor for the review screen's expanded all-fields + raw-MRZ view. Not user-facing. */
internal const val REVIEW_EXPANDED_TEST_TAG: String = "tessera-mrz-review-expanded"

/** Semantics anchor for the "couldn't read this MRZ" screen. Not user-facing. */
internal const val READ_FAILED_TEST_TAG: String = "tessera-mrz-read-failed"

/** Semantics anchor for the struggling hint overlaid on the live preview (mockup 02). Not user-facing. */
internal const val STRUGGLING_TEST_TAG: String = "tessera-mrz-struggling"

/** Semantics anchor for the "analyzing photo" screen (mockup 07c). Not user-facing. */
internal const val SAVED_IMAGE_ANALYZING_TEST_TAG: String = "tessera-mrz-saved-image-analyzing"

/** Semantics anchor for the saved-image candidates screen (mockup 07). Not user-facing. */
internal const val SAVED_IMAGE_CANDIDATES_TEST_TAG: String = "tessera-mrz-saved-image-candidates"

/** Semantics anchor for the "no MRZ found in this photo" screen (mockup 07b). Not user-facing. */
internal const val SAVED_IMAGE_EMPTY_TEST_TAG: String = "tessera-mrz-saved-image-empty"

/**
 * Holds the current [ScannerUiState] and, via [ScannerScaffold] + [ScannerBody], dispatches the matching
 * screen under the shared chrome (the top-bar ✕ cancel + the method switcher, TES-71). The screen is a
 * one-shot flow: a reading method decodes an MRZ, which routes to the review or read-failed screen (or
 * straight back to the host under [`INSTANT_RETURN`][ReviewMode.INSTANT_RETURN]); a cancel anywhere (the top
 * bar's ✕, or a screen's own back) reports [`Cancelled(USER_DISMISSED)`][DismissReason.USER_DISMISSED]. The
 * start screen is [initialState] over [`enabledMethods`][MrzScannerConfig.enabledMethods], and the switcher
 * flips between the enabled methods (camera → [`Scanning`][ScannerUiState.Scanning], photo → the photo picker
 * via [`AwaitingSavedImagePick`][ScannerUiState.AwaitingSavedImagePick], type →
 * [`ManualRaw`][ScannerUiState.ManualRaw]).
 *
 * The scanner's per-frame result stream drives the state continuously through the pure [reduceCameraResult]
 * reducer (applied in `onCameraResult` below): a decode routes on (guarded by a one-shot latch so a running
 * stream fires only once), a [`CameraInUse`][ScannerUiState.CameraInUse] notice self-resumes to
 * [`Scanning`][ScannerUiState.Scanning] when a clean frame proves the camera reconnected (it is recoverable
 * — no retry), and a [`CameraUnavailable`][ScannerUiState.CameraUnavailable] notice is terminal. After the
 * configured [`struggleTimeout`][MrzScannerConfig.struggleTimeout] with no decode, the preview overlays a
 * neutral "still looking / type it instead" hint (mockup 02). The permission-mode states in [ScannerUiState]
 * are handled inside [CameraCapture], not dispatched here.
 */
@Composable
private fun ScannerFlow(
    config: MrzScannerConfig,
    onResult: (MrzScannerResult) -> Unit,
) {
    // The flow starts on the method [initialState] derives from the consumer's enabledMethods (camera first,
    // else manual entry, else saved image; empty → camera defensively) rather than always camera, so a
    // consumer who disabled camera lands on a working entry point (TES-71).
    var uiState: ScannerUiState by remember { mutableStateOf(initialState(config.enabledMethods)) }

    // A one-shot latch: once a decode has routed on (to review / read-failed / straight back), later decoded
    // frames from the still-running stream must not re-fire. Kept as flow state (not inside the collector) so
    // it survives the state transitions the collector drives — e.g. a CameraInUse → Scanning auto-resume.
    var decodeRouted by remember { mutableStateOf(false) }

    val onCancel = { onResult(MrzScannerResult.Cancelled(DismissReason.USER_DISMISSED)) }

    // Routing the moment the reader decodes an MRZ, delegated to the pure decision in routeDecode: a parse
    // failure shows the "couldn't read" screen, instant-return hands the decode straight back, and review
    // mode parks it on the review screen. Kept pure (no camera, no Compose) so it is host-unit-testable.
    val routeDecoded = { decoded: MrzScanResult.Decoded ->
        when (val route = routeDecode(decoded, config.reviewMode)) {
            is DecodeRoute.ShowReadFailed -> uiState = ScannerUiState.ReadFailed(route.capturedText)
            is DecodeRoute.ReturnConfirmed -> onResult(MrzScannerResult.Confirmed(route.decoded))
            is DecodeRoute.ShowReview -> uiState = ScannerUiState.Review(route.decoded)
        }
    }

    // The continuous state reducer over the scanner's result stream. Every per-frame result runs through the
    // pure reduceCameraResult and the resulting CameraFlowEffect is applied to uiState here — so the flow's
    // reaction to each result kind (decode, camera-in-use, camera-unavailable, transient miss) is a pure,
    // host-testable decision, and this callback only applies it. CameraInUse self-resumes: it stays bound and
    // any later non-error result (StayScanning / GoDecoded) flips the flow back to Scanning, so no retry is
    // needed. Repeated GoDecoded frames are guarded by the decodeRouted latch (route only the first).
    val onCameraResult = { result: MrzScanResult ->
        when (val effect = reduceCameraResult(result)) {
            is CameraFlowEffect.GoDecoded -> {
                if (!decodeRouted) {
                    decodeRouted = true
                    routeDecoded(effect.decoded)
                }
            }

            CameraFlowEffect.GoCameraInUse -> {
                uiState = ScannerUiState.CameraInUse
            }

            CameraFlowEffect.GoCameraUnavailable -> {
                uiState = ScannerUiState.CameraUnavailable
            }

            // A transient miss (NoMrzFound / OcrFailed). No routing; but if a recoverable CameraInUse notice
            // is showing, a clean frame proves the camera has reconnected — return to scanning.
            CameraFlowEffect.StayScanning -> {
                if (uiState is ScannerUiState.CameraInUse) uiState = ScannerUiState.Scanning()
            }
        }
    }

    val onManualEntry = { uiState = ScannerUiState.ManualRaw() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Route a picked saved image through the tolerant SavedImageMrzReader, entirely on-device, then apply the
    // pure mapSavedImageResult: candidates → the candidates screen (never picking one); a single decode →
    // routeDecode (identical to a camera decode); nothing readable → the empty screen. The reader is built,
    // used, and closed per pick (it owns the ML Kit recognizer's lifetime). Saved-image reading is opt-in and
    // off by default — reaching this flow at all means the consumer enabled ScanMethod.SAVED_IMAGE, which IS
    // the acknowledgement (ADR-023), so the SavedImageReadingAcknowledgement is constructed here internally
    // with no separate user screen. Arm the decode latch afresh so a candidate/single-decode route fires.
    val readPickedImage = { uri: Uri ->
        uiState = ScannerUiState.SavedImageAnalyzing
        scope.launch {
            val acknowledgement = SavedImageReadingAcknowledgement()
            val result =
                SavedImageMrzReader(
                    acknowledgement = acknowledgement,
                    recognizer = mlKitSavedImageRecognizer(acknowledgement, context),
                    tolerant = true,
                ).use { reader -> reader.read(uri) }
            decodeRouted = false
            when (val outcome = mapSavedImageResult(result)) {
                is SavedImageOutcome.Candidates -> uiState = ScannerUiState.SavedImageCandidates(outcome.candidates)
                is SavedImageOutcome.SingleDecode -> routeDecoded(outcome.decoded)
                SavedImageOutcome.Empty -> uiState = ScannerUiState.SavedImageEmpty
            }
        }
        Unit
    }

    // The system photo picker, restricted to images. Registered here (a launcher must be created in
    // composition, not inside a callback); a null Uri means the user dismissed the picker with no selection,
    // so nothing changes (the AwaitingSavedImagePick prompt then offers a re-pick). enterSavedImage() launches
    // it — reached from the method switcher's Photo tab and the SAVED_IMAGE-only initial state (TES-71).
    val savedImagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri != null) readPickedImage(uri)
        }
    val enterSavedImage = {
        savedImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // The method switcher's Photo tab and the SAVED_IMAGE-only initial state both land on
    // AwaitingSavedImagePick; entering it launches the picker. Keyed on the state object so a re-entry (e.g.
    // switch away and back to Photo) re-launches — AwaitingSavedImagePick is a data object, so its identity is
    // stable across recompositions and the effect fires once per transition into it, not on every recompose.
    LaunchedEffect(uiState) {
        if (uiState == ScannerUiState.AwaitingSavedImagePick) enterSavedImage()
    }

    // Switching reading method from the switcher: camera → Scanning, photo → the await-pick launcher state,
    // type → ManualRaw. Re-arm the decode latch so a fresh method's first decode routes on (a previous decode
    // may have set it). Pure target selection; the picker for Photo is launched by the LaunchedEffect above.
    val onSelectMethod = { method: ScanMethod ->
        decodeRouted = false
        uiState =
            when (method) {
                ScanMethod.CAMERA -> ScannerUiState.Scanning()
                ScanMethod.SAVED_IMAGE -> ScannerUiState.AwaitingSavedImagePick
                ScanMethod.MANUAL_ENTRY -> ScannerUiState.ManualRaw()
            }
    }

    ScannerScaffold(
        enabledMethods = config.enabledMethods,
        currentState = uiState,
        onClose = onCancel,
        onSelectMethod = onSelectMethod,
    ) {
        ScannerBody(
            state = uiState,
            config = config,
            onResult = onResult,
            onCameraResult = onCameraResult,
            onManualEntry = onManualEntry,
            onCancel = onCancel,
            enterSavedImage = enterSavedImage,
            setState = { uiState = it },
            armDecodeLatch = { decodeRouted = false },
        )
    }
}

/**
 * The per-state screen dispatch — the exhaustive `when` over [ScannerUiState], rendered inside the
 * [ScannerScaffold] body (so the shared top bar + method switcher sit above it, TES-71). Split out of
 * [ScannerFlow] purely so the flow function stays readable; it holds no state of its own, receiving the
 * flow's callbacks. [setState] mutates the flow's `uiState`; [armDecodeLatch] clears the one-shot decode
 * latch (used where a screen re-enters scanning and the next decode must route again).
 */
@Composable
private fun ScannerBody(
    state: ScannerUiState,
    config: MrzScannerConfig,
    onResult: (MrzScannerResult) -> Unit,
    onCameraResult: (MrzScanResult) -> Unit,
    onManualEntry: () -> Unit,
    onCancel: () -> Unit,
    enterSavedImage: () -> Unit,
    setState: (ScannerUiState) -> Unit,
    armDecodeLatch: () -> Unit,
) {
    // Route a decode straight to the matching state via routeDecode — shared by manual entry and candidate
    // pick, both of which produce a Decoded that follows the identical decode routing (read-failed / review /
    // straight back under INSTANT_RETURN). Kept local so the two call sites do not each re-derive it.
    val routeThroughDecode = { decoded: MrzScanResult.Decoded ->
        when (val route = routeDecode(decoded, config.reviewMode)) {
            is DecodeRoute.ShowReadFailed -> setState(ScannerUiState.ReadFailed(route.capturedText))
            is DecodeRoute.ReturnConfirmed -> onResult(MrzScannerResult.Confirmed(route.decoded))
            is DecodeRoute.ShowReview -> setState(ScannerUiState.Review(route.decoded))
        }
    }

    when (state) {
        is ScannerUiState.Scanning -> {
            CameraCapture(
                config = config,
                struggling = state.struggling,
                onCameraResult = onCameraResult,
                onStruggling = { setState(ScannerUiState.Scanning(struggling = true)) },
                onManualEntry = onManualEntry,
            )
        }

        is ScannerUiState.CameraInUse -> {
            CameraInUseContent(onManualEntry = onManualEntry)
        }

        is ScannerUiState.CameraUnavailable -> {
            CameraUnavailableContent(onManualEntry = onManualEntry)
        }

        is ScannerUiState.Review -> {
            ReviewContent(
                decoded = state.decoded,
                expanded = state.expanded,
                onToggleExpanded = { setState(state.copy(expanded = !state.expanded)) },
                onUse = { onResult(MrzScannerResult.Confirmed(state.decoded)) },
                // Rescanning arms the flow for a fresh decode: clear the one-shot latch so the next decoded
                // frame routes on rather than being swallowed as a repeat.
                onRescan = {
                    armDecodeLatch()
                    setState(ScannerUiState.Scanning())
                },
            )
        }

        is ScannerUiState.ReadFailed -> {
            ReadFailedContent(
                capturedText = state.capturedText,
                onTryAgain = {
                    armDecodeLatch()
                    setState(ScannerUiState.Scanning())
                },
                // The read-failed / error escape into manual entry (also reachable from the method switcher).
                onManualEntry = onManualEntry,
            )
        }

        is ScannerUiState.ManualRaw -> {
            ManualRawContent(
                state = state,
                onTextChange = { setState(state.copy(text = it)) },
                // Assemble a Decoded from the typed text (pure, host-tested), then route it exactly as a
                // camera decode: a parse Failure shows the read-failed screen, a Success / PartialSuccess
                // goes to review (or straight back under INSTANT_RETURN). The manual-entry read method flows
                // through, so the review screen shows "Read by manual entry" with no extra wiring.
                onRead = { hint -> routeThroughDecode(assembleManualDecoded(state.text, hint)) },
                onBack = onCancel,
            )
        }

        ScannerUiState.AwaitingSavedImagePick -> {
            // Entering this state launches the picker (a LaunchedEffect in ScannerFlow). This content only
            // shows if that pick was dismissed with no photo — a neutral re-pick prompt so the screen is never
            // blank (TES-71).
            AwaitingSavedImagePickContent(onChoosePhoto = enterSavedImage)
        }

        ScannerUiState.SavedImageAnalyzing -> {
            SavedImageAnalyzingContent()
        }

        is ScannerUiState.SavedImageCandidates -> {
            SavedImageCandidatesContent(
                candidates = state.candidates,
                // The user chose a candidate: wrap it into a Decoded (pure, host-tested) and route it exactly
                // as any decode — its own parse verdict carried through, no SDK judgement (the user decided).
                onPick = { candidate -> routeThroughDecode(candidateDecoded(candidate)) },
                onChooseDifferent = enterSavedImage,
            )
        }

        ScannerUiState.SavedImageEmpty -> {
            SavedImageEmptyContent(
                onChooseDifferent = enterSavedImage,
                onManualEntry = onManualEntry,
            )
        }

        // The remaining ScannerUiState variants (the permission-mode states, manual field-by-field entry)
        // are the declared contract for the later 0.5.0 slices — not reachable here. (The camera-initializing
        // state is not a flow state at all — CameraPreview renders it from a null preview surface, see there.)
        else -> {}
    }
}

/**
 * Where a decoded reading routes, decided purely from the parse verdict and the configured [ReviewMode].
 * A parse failure is never an accepted reading; instant-return returns a non-failure decode straight to the
 * host; review mode parks it for the user. Extracted from [ScannerFlow] as a pure, Compose-free decision so
 * the routing (including the reader-not-oracle rule that a `PartialSuccess` still routes to review, not
 * away) is host-unit-testable without a camera.
 */
internal sealed interface DecodeRoute {
    /** The decode did not parse; show the "couldn't read" screen with the captured text. */
    data class ShowReadFailed(
        val capturedText: io.lightine.tessera.mrz.camera.RecognizedText,
    ) : DecodeRoute

    /** Instant-return: hand the decode straight back to the host as `Confirmed`, no review step. */
    data class ReturnConfirmed(
        val decoded: MrzScanResult.Decoded,
    ) : DecodeRoute

    /** Review mode: park the decode on the review screen for the user to accept or rescan. */
    data class ShowReview(
        val decoded: MrzScanResult.Decoded,
    ) : DecodeRoute
}

/**
 * The routing decision for a decoded reading. A [`ParseResult.Failure`][ParseResult.Failure] routes to
 * [DecodeRoute.ShowReadFailed] regardless of mode; otherwise [ReviewMode.INSTANT_RETURN] routes to
 * [DecodeRoute.ReturnConfirmed] and [ReviewMode.REVIEW] to [DecodeRoute.ShowReview]. A `PartialSuccess`
 * (check-digit mismatch) is a non-failure and follows the same path as `Success` — the UI never treats a
 * mismatch as a failure or diverts it (Principle 1).
 */
internal fun routeDecode(
    decoded: MrzScanResult.Decoded,
    reviewMode: ReviewMode,
): DecodeRoute =
    when {
        decoded.parse is ParseResult.Failure -> DecodeRoute.ShowReadFailed(decoded.recognizedText)
        reviewMode == ReviewMode.INSTANT_RETURN -> DecodeRoute.ReturnConfirmed(decoded)
        else -> DecodeRoute.ShowReview(decoded)
    }

/**
 * What one scanner result means for the flow state — the pure decision [reduceCameraResult] returns, applied
 * by [ScannerFlow]'s `onCameraResult`. Extracted (Compose-free, camera-free) so the flow's reaction to the
 * per-frame result stream is host-unit-testable without a real CameraX device, mirroring how [routeDecode]
 * makes the decode routing testable.
 */
internal sealed interface CameraFlowEffect {
    /** A decode is available; route it exactly as [routeDecode] decides (then the one-shot latch stops repeats). */
    data class GoDecoded(
        val decoded: MrzScanResult.Decoded,
    ) : CameraFlowEffect

    /** Another app holds the camera (recoverable). Show the in-use notice; it self-resumes on the next clean frame. */
    data object GoCameraInUse : CameraFlowEffect

    /** The camera cannot be started (terminal). Show the unavailable notice; no auto-recovery, no retry. */
    data object GoCameraUnavailable : CameraFlowEffect

    /** A transient per-frame miss (`NoMrzFound` / `OcrFailed`) — keep scanning, no state change of its own. */
    data object StayScanning : CameraFlowEffect
}

/**
 * The flow-state decision for one [MrzScanResult] off the scanner's stream, decided purely from the result
 * kind:
 *  * a [`Decoded`][MrzScanResult.Decoded] → [CameraFlowEffect.GoDecoded];
 *  * a [`CaptureError`][MrzScanResult.CaptureError] carrying [`CameraInUse`][CameraError.CameraInUse] →
 *    [CameraFlowEffect.GoCameraInUse] (recoverable — the caller lets it self-resume, no retry button);
 *  * a `CaptureError` carrying [`CameraUnavailable`][CameraError.CameraUnavailable] →
 *    [CameraFlowEffect.GoCameraUnavailable] (terminal);
 *  * a `CaptureError` carrying [`OcrFailed`][CameraError.OcrFailed] (a transient per-frame OCR miss) and a
 *    [`NoMrzFound`][MrzScanResult.NoMrzFound] → [CameraFlowEffect.StayScanning].
 *
 * [`PermissionDenied`][CameraError.PermissionDenied] is not mapped to a distinct effect here: the screen's
 * own permission gate ([CameraCapture]) owns the permission path before the stream starts, so a
 * permission-denied capture error simply keeps scanning (the gate governs it). Pure and Compose-free so the
 * whole mapping is unit-testable off-device.
 */
internal fun reduceCameraResult(result: MrzScanResult): CameraFlowEffect =
    when (result) {
        is MrzScanResult.Decoded -> {
            CameraFlowEffect.GoDecoded(result)
        }

        is MrzScanResult.NoMrzFound -> {
            CameraFlowEffect.StayScanning
        }

        is MrzScanResult.CaptureError -> {
            when (result.error) {
                is CameraError.CameraInUse -> CameraFlowEffect.GoCameraInUse
                is CameraError.CameraUnavailable -> CameraFlowEffect.GoCameraUnavailable
                is CameraError.OcrFailed -> CameraFlowEffect.StayScanning
                is CameraError.PermissionDenied -> CameraFlowEffect.StayScanning
            }
        }
    }

/**
 * The camera-permission gate and, once the permission is held, the live preview with a results collector.
 * Reads whether `CAMERA` is held and re-reads it whenever the host returns to the foreground, so a grant made
 * outside this screen (via the host's request OR via the OS settings) takes effect without the consumer
 * re-launching it. When held, the live preview shows and every scanner result is surfaced through
 * [onCameraResult] (the flow reduces it); otherwise the adaptive [PermissionContent] shows — Grant mode while
 * a request can still succeed, Settings mode once the permission is permanently denied.
 *
 * **Permission boundary (scope).** The SDK only ever *reads* the permission status
 * ([Context.checkSelfPermission] and [Activity.shouldShowRequestPermissionRationale], both read-only) and
 * *navigates* (opening the OS app-settings screen by Intent). It NEVER calls `requestPermissions` /
 * `ActivityResultContracts.RequestPermission` — the actual "ask" stays with the host through
 * [config.onRequestPermission][MrzScannerConfig.onRequestPermission].
 *
 * The mode is [permissionScreenState] applied to three read-only signals recomputed on `ON_RESUME`:
 *  * `granted` — [Context.hasCameraPermission];
 *  * `hasAsked` — [rememberSaveable] so it survives recreation; set true in the Grant action, right before
 *    the host request, because the platform's rationale signal alone cannot tell "never asked" apart from
 *    "permanently denied";
 *  * `showRationale` — [Activity.shouldShowRequestPermissionRationale], read only when an [Activity] is
 *    reachable ([findActivity]); with no Activity permanent denial cannot be detected, so the mode is forced
 *    to [PermissionScreenState.NEEDS_GRANT] rather than showing a dead "Open Settings".
 *
 * @param struggling whether the "still looking / type it instead" hint is overlaid on the preview.
 * @param onCameraResult every scanner result off the stream, for the flow's continuous reducer.
 * @param onStruggling fired once the struggle timeout elapses with no decode (flips the flow to struggling).
 * @param onManualEntry the "type it instead" escape into manual entry.
 */
@Composable
private fun CameraCapture(
    config: MrzScannerConfig,
    struggling: Boolean,
    onCameraResult: (MrzScanResult) -> Unit,
    onStruggling: () -> Unit,
    onManualEntry: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // The Activity backing the screen, unwrapped from the Compose LocalContext's ContextWrapper chain. Needed
    // for the read-only shouldShowRequestPermissionRationale; null when none is reachable (then permanent
    // denial cannot be detected and the mode degrades to NEEDS_GRANT).
    val activity = remember(context) { context.findActivity() }

    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    // Whether the host has been handed a request in this flow. rememberSaveable so it survives recreation
    // (rotation / process death) — otherwise a permanently-denied screen would wrongly revert to Grant mode.
    var hasAsked by rememberSaveable { mutableStateOf(false) }
    // shouldShowRequestPermissionRationale is a read-only status query (NOT a request). False when an Activity
    // is unreachable — without one, permanent denial is undetectable, so the mode stays NEEDS_GRANT.
    var showRationale by remember { mutableStateOf(activity.shouldShowCameraRationale()) }

    // Recompute granted + rationale whenever the host returns to the foreground, so returning from the host's
    // permission request OR from the OS settings screen updates the mode without a re-launch.
    DisposableEffect(lifecycleOwner, activity) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    granted = context.hasCameraPermission()
                    showRationale = activity.shouldShowCameraRationale()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (permissionScreenState(granted = granted, hasAsked = hasAsked, showRationale = showRationale)) {
        PermissionScreenState.GRANTED -> {
            CameraPreview(
                struggleTimeout = config.struggleTimeout,
                struggling = struggling,
                showTorchButton = config.showTorchButton,
                torchOnByDefault = config.torchOnByDefault,
                onCameraResult = onCameraResult,
                onStruggling = onStruggling,
                onManualEntry = onManualEntry,
            )
        }

        PermissionScreenState.NEEDS_GRANT -> {
            PermissionContent(
                state = PermissionScreenState.NEEDS_GRANT,
                // Grant hands the request to the host (the SDK never requests it). Mark that we've asked
                // first, so a subsequent permanent denial is detected on the next ON_RESUME.
                onGrant = {
                    hasAsked = true
                    config.onRequestPermission?.invoke()
                },
                onOpenSettings = { context.openAppSettings() },
                onManualEntry = onManualEntry,
                hasRequestHandler = config.onRequestPermission != null,
            )
        }

        PermissionScreenState.PERMANENTLY_DENIED -> {
            PermissionContent(
                state = PermissionScreenState.PERMANENTLY_DENIED,
                onGrant = {
                    hasAsked = true
                    config.onRequestPermission?.invoke()
                },
                // Open Settings is the UI's own navigation (not a permission request) — always available here.
                onOpenSettings = { context.openAppSettings() },
                onManualEntry = onManualEntry,
                hasRequestHandler = config.onRequestPermission != null,
            )
        }
    }
}

/** Read-only rationale query, guarded for a null [Activity]: no Activity → `false` (permanent denial is then undetectable). */
private fun Activity?.shouldShowCameraRationale(): Boolean = this?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true

/**
 * Unwraps the [ContextWrapper] chain of a Compose [LocalContext] to the backing [Activity], or `null` if none
 * is reachable. The Compose context is usually a `ContextThemeWrapper` around the host `Activity`; walking the
 * `baseContext` chain finds it without assuming the immediate context is the Activity.
 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Opens this app's OS settings page (`ACTION_APPLICATION_DETAILS_SETTINGS`) so the user can turn Camera on
 * after a permanent denial. This is *navigation*, not a permission request — it stays within the permission
 * boundary (the UI owns it). The `FLAG_ACTIVITY_NEW_TASK` lets it start from a non-Activity context safely.
 */
private fun Context.openAppSettings() {
    val intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    startActivity(intent)
}

/**
 * The live preview. The scanner ([CameraXMrzScanner]) owns the camera session and lifecycle; this
 * composable only draws and observes. One scanner is tied to this composition with its opt-in preview
 * armed, run while the preview is shown, and closed when it leaves. Only the `SurfaceRequest` surface
 * handle is hoisted into Compose state — never an `ImageProxy`, frame, or decoded field held in state (the
 * module's memory-hygiene commitment): a scanner result is handed straight to [onCameraResult] and not
 * retained here.
 *
 * The results collector consumes the scanner's per-frame [`results`][CameraXMrzScanner.results] flow **in
 * full**, forwarding every result to [onCameraResult] — the flow's pure [reduceCameraResult] reducer decides
 * what each one means (route a decode once, show/clear a camera-in-use notice, surface camera-unavailable).
 * The one-shot decode latch lives in the flow, not here, so the collector stays a plain forwarder and the
 * camera keeps running under a notice so a recoverable in-use interruption can self-resume.
 *
 * The struggle timeout is a [LaunchedEffect] that waits [struggleTimeout] and then fires [onStruggling]; it
 * is keyed on the scanner so it starts once per preview session, and the camera keeps running underneath —
 * a later decode still routes normally, and the flow drops the hint on any progress. The config default is
 * 10s (finite); a non-finite value ([Duration.INFINITE]) means "never struggle", so the effect does not arm.
 *
 * **Torch (TES-84).** When [showTorchButton] is set and the bound camera has a flash unit, a torch toggle
 * ([TorchButton]) overlays the live preview (mockup 01, top-end). The scanner owns the flash — the seam
 * ([CameraXMrzScanner.setTorchEnabled] / [CameraXMrzScanner.hasTorch]) drives it via CameraX's async
 * `enableTorch` (no rebind, so toggling never interrupts capture). [torchOnByDefault] is seeded before
 * [CameraXMrzScanner.start] so it takes effect the instant the camera binds; the torch clears when the
 * session unbinds (this preview leaving), mirroring the iOS torch behaviour.
 */
@Composable
private fun CameraPreview(
    struggleTimeout: Duration,
    struggling: Boolean,
    showTorchButton: Boolean,
    torchOnByDefault: Boolean,
    onCameraResult: (MrzScanResult) -> Unit,
    onStruggling: () -> Unit,
    onManualEntry: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner =
        remember(context, lifecycleOwner) {
            CameraXMrzScanner(
                appContext = context.applicationContext,
                lifecycleOwner = lifecycleOwner,
            ).apply { enablePreview() }
        }

    // Torch on/off, hoisted so the button reflects it and it survives recreation (rotation / process death).
    // Seeded from the consumer's torchOnByDefault; a fresh scanner (e.g. this preview re-mounting after a
    // rescan) re-applies the current desired state on bind via the DisposableEffect below, so the button and
    // the flash never drift apart.
    var torchOn by rememberSaveable { mutableStateOf(torchOnByDefault) }

    DisposableEffect(scanner) {
        // Seed the desired torch state before start() so CameraX honours it the moment the camera binds (the
        // scanner holds it and applies it on bind — no rebind, so capture is never interrupted). A no-op on a
        // device with no flash unit.
        scanner.setTorchEnabled(torchOn)
        scanner.start()
        onDispose { scanner.close() }
    }

    LaunchedEffect(scanner) {
        scanner.results.collect { result -> onCameraResult(result) }
    }

    // Struggle timeout: after struggleTimeout with no decode, surface the neutral hint. The camera keeps
    // running, so a decode arriving later still routes; the flow clears the hint on progress.
    LaunchedEffect(scanner) {
        if (struggleTimeout.isFinite()) {
            delay(struggleTimeout)
            onStruggling()
        }
    }

    val surfaceRequest by scanner.surfaceRequest.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        // While the scanner has not yet produced a preview surface, the camera + on-device OCR are still
        // warming up (mockup 01b). Show the initializing loading state rather than a blank dark box — a null
        // surface reads as frozen otherwise. It auto-swaps to the live viewfinder the moment the surface
        // arrives. This is the real, device-driven trigger for "initializing"; there is no separate flow
        // state for it (ScannerFlow never produces one — the surface's nullness IS the signal), so it lives
        // here where it can be observed.
        val request = surfaceRequest
        if (request != null) {
            CameraXViewfinder(
                surfaceRequest = request,
                modifier = Modifier.fillMaxSize().testTag(VIEWFINDER_TEST_TAG),
            )

            // Torch toggle (mockup 01, top-end) — over a live preview only, and only when the consumer left
            // it enabled AND the device actually has a flash unit. hasTorch() is reliable at this point:
            // CameraX delivers the preview surface only *after* the camera has bound, so a non-null request
            // means the bound camera (and its flash-unit info) exists. TopEnd is layout-direction-aware, so
            // the control mirrors to the correct side under RTL.
            val hasTorch = remember(request) { scanner.hasTorch() }
            if (showTorchButton && hasTorch) {
                TorchButton(
                    torchOn = torchOn,
                    onToggle = {
                        torchOn = !torchOn
                        scanner.setTorchEnabled(torchOn)
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                )
            }

            // The MRZ framing guide (mockup 01, TES-87): a dashed frame sitting low in the view — where the
            // MRZ band lands on a document held upright — with its hint below. On-device OCR reads all text
            // in frame and the reader isolates the MRZ downstream; guiding the user to frame just the band
            // gives a cleaner, faster read. Advisory only — it never gates capture. Shown over a live preview
            // only, mirroring the iOS MrzGuideOverlay (tessera-swift 6c2aecf).
            MrzGuideOverlay(modifier = Modifier.align(Alignment.BottomCenter))
        } else {
            InitializingContent()
        }

        // The struggle hint (mockup 02) sits over the live preview, near the top, so the camera stays visible
        // underneath. Neutral advisory copy — never an error or a verdict (Principle 1). The viewfinder stays
        // full-bleed, but this overlaid control is width-capped + centred (contentMaxWidth, TES-78) so on a
        // wide screen it stays reachable near the middle rather than stretching edge-to-edge.
        if (struggling) {
            StrugglingHint(
                onManualEntry = onManualEntry,
                modifier = Modifier.align(Alignment.TopCenter).contentMaxWidth(),
            )
        }
    }
}

/**
 * The struggling hint overlaid on the live preview (mockup 02): a neutral advisory line ("still looking — try
 * more light or move closer") and a "Type it instead" affordance into manual entry. Advisory only — it never
 * states an error or a verdict, and the camera keeps scanning underneath (a decode arriving after the hint
 * still routes normally). [onManualEntry] switches to manual raw entry.
 *
 * `internal` (not `private`): the live-preview host it overlays needs a real camera and cannot run under
 * Robolectric, so the hint's copy and its "Type it instead" affordance are host-tested through this entry
 * point directly (the same composable the flow overlays), per the testing-layers rule.
 */
@Composable
internal fun StrugglingHint(
    onManualEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp).testTag(STRUGGLING_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.tessera_scanner_struggling_hint),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            // The hint overlays the preview via the struggle-timeout auto-transition (mockup 02), so it is a
            // polite live region — announced when it appears without the user having to move focus to it.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(onClick = onManualEntry) {
            Text(text = stringResource(R.string.tessera_scanner_struggling_manual))
        }
    }
}

/**
 * The torch (flashlight) toggle overlaid on the live preview (mockup 01, top-end) — [TES-84]. A compact
 * circular control the user taps to turn the device flash on or off while scanning in low light. It carries
 * no trust meaning; it only helps the camera see (Principle 1 is untouched). Styled for legibility over the
 * live camera: a translucent-black pill when off, the theme accent when on. Deliberately a text glyph (🔦)
 * rather than a Material icon vector — this module does not depend on `material-icons` (the same reason the
 * top-bar ✕ is a glyph, see [ScannerScaffold]).
 *
 * **A11y (TES-47/TES-58).** A [`Switch`][Role.Switch]-role [`toggleable`][toggleable], so a screen reader
 * announces the on/off state itself ("on"/"off") — the state is never carried by colour alone. The control's
 * name comes from the overridable `tessera_scanner_torch` label; the glyph is decorative
 * ([clearAndSetSemantics]) so it is not spoken as "flashlight". [minimumInteractiveComponentSize] guarantees
 * the ≥48dp touch target while the visible pill stays compact.
 *
 * `internal` (not `private`): the real torch wiring lives in [CameraPreview], which drives real CameraX and
 * cannot run under Robolectric, so the button's rendering, label, toggle semantics, and click are host-tested
 * through this entry point directly — the same testing-layers pattern as [StrugglingHint].
 *
 * @param torchOn whether the torch is currently on (drives the icon tint and the toggle state).
 * @param onToggle fired when the user taps the control (the caller flips [torchOn] and drives the flash).
 */
@Composable
internal fun TorchButton(
    torchOn: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.tessera_scanner_torch)
    Box(
        modifier =
            modifier
                .testTag(TORCH_TEST_TAG)
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .background(
                    if (torchOn) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f),
                ).toggleable(
                    value = torchOn,
                    role = Role.Switch,
                    onValueChange = { onToggle() },
                ).semantics { contentDescription = label }
                .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // Decorative glyph — the label + switch role carry the meaning for a screen reader.
            text = "🔦",
            color = if (torchOn) MaterialTheme.colorScheme.onPrimary else Color.White,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * The MRZ framing guide overlaid on the live preview (mockup 01, TES-87) — the Android mirror of the iOS
 * `MrzGuideOverlay` (tessera-swift `6c2aecf`). A dashed rounded frame sits low in the view, where the
 * machine-readable zone lands on a document held upright, with a hint below it. Purely advisory: it never
 * gates capture, and the reader still isolates the MRZ from wherever it appears in frame — it only nudges
 * the user to frame the band for a cleaner, faster read (Principle 1 is untouched — this makes no trust
 * judgement).
 *
 * **A11y (TES-47/TES-58).** The dashed frame is decorative ([clearAndSetSemantics] — a screen reader is not
 * told about a drawn rectangle); the hint carries the meaning as a spoken label (tagged [GUIDE_HINT_TEST_TAG],
 * matching the iOS identifier). Copy is the overridable `tessera_scanner_camera_guide` string.
 *
 * `internal` (not `private`): the live-preview host it overlays needs a real camera and cannot run under
 * Robolectric, so the guide's frame and hint are host-tested through this entry point directly — the same
 * testing-layers pattern as [StrugglingHint] / [TorchButton].
 */
@Composable
internal fun MrzGuideOverlay(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The dashed guide frame — decorative (the hint below carries the meaning). Drawn rather than built
        // from a bordered shape because Compose has no dashed-border modifier; a dashed stroke on a rounded
        // rect matches the iOS RoundedRectangle.strokeBorder(dash: [8, 6]) exactly.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clearAndSetSemantics {}
                    .drawBehind {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.9f),
                            cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
                            style =
                                Stroke(
                                    width = 2.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()), 0f),
                                ),
                        )
                    },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.tessera_scanner_camera_guide),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(GUIDE_HINT_TEST_TAG).padding(bottom = 48.dp),
        )
    }
}

/**
 * The camera-initializing loading state (mockup 01b), shown by [CameraPreview] while the scanner has not yet
 * produced a preview surface — the camera and on-device text reader are still warming up. A centred loading
 * indicator over a title and an on-device honesty sub-line, so the moment before the first frame is a clear
 * "starting up" state rather than a blank dark box that reads as frozen. It auto-swaps to the live viewfinder
 * the instant the surface arrives (there is no button and no timeout — the surface's arrival is the trigger).
 *
 * **A11y (TES-47).** The state appears via an auto-transition (the screen mounts straight into it before the
 * surface exists), so the "Starting camera…" title is a **polite** live region — a screen reader announces it
 * on arrival without the user moving focus. The spinner is decorative (the title carries the meaning), so it
 * is removed from the semantics tree; its own animation already honours the platform animation scale
 * (reduce-motion), needing no extra gating — mirroring [SavedImageAnalyzingContent].
 *
 * `internal` (not `private`): the real null-surface trigger lives inside [CameraPreview], which drives real
 * CameraX and cannot run under Robolectric, so this content is host-tested through this entry point directly,
 * per the testing-layers rule (same pattern as [StrugglingHint]).
 */
@Composable
internal fun InitializingContent() {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .contentMaxWidth()
                .padding(24.dp)
                .testTag(INITIALIZING_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Decorative spinner — the title states what is happening; cleared so it is not an unlabeled node.
        CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics {})
        Text(
            text = stringResource(R.string.tessera_scanner_initializing_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            text = stringResource(R.string.tessera_scanner_initializing_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Context.hasCameraPermission(): Boolean = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
