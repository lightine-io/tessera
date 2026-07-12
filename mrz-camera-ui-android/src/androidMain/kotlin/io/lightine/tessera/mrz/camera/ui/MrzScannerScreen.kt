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
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.ViewPort
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.lightine.tessera.mrz.camera.CameraError
import io.lightine.tessera.mrz.camera.CameraXMrzScanner
import io.lightine.tessera.mrz.camera.ConsensusVerdict
import io.lightine.tessera.mrz.camera.MlKitMrzTextRecognizer
import io.lightine.tessera.mrz.camera.MrzDecodeConsensus
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.ParsingMode
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

/** Semantics anchor for the "hold steady" gathering cue overlaid on the live preview. Not user-facing. */
internal const val GATHERING_TEST_TAG: String = "tessera-mrz-gathering"

/** How long the "hold steady" cue lingers after the last decoded frame before a miss clears it (anti-strobe). */
private const val GATHERING_CUE_LINGER_MS: Long = 600L

/**
 * The space [MrzGuideOverlay]'s guidance region (framing hint / gathering / struggling) reserves at the
 * bottom of the live preview for the ALWAYS-pinned "What's the MRZ?" link below it (TES-95) — enough for that
 * link's own touch target plus a comfortable gap, so the guidance region's height cap ([MrzGuideOverlay])
 * never computes a height that would still let the two overlap.
 */
private val GuidanceBottomReserved = 64.dp

/** Semantics anchor for the "analyzing photo" screen (mockup 07c). Not user-facing. */
internal const val SAVED_IMAGE_ANALYZING_TEST_TAG: String = "tessera-mrz-saved-image-analyzing"

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
 * stream fires only once), a [`CameraInUse`][ScannerUiState.CameraInUse] notice replaces the live preview (the
 * scanner is torn down under it, see [CameraInUseContent]) and recovers only on the next `ON_RESUME` — not
 * from any later frame, since none are flowing — and a [`CameraUnavailable`][ScannerUiState.CameraUnavailable]
 * notice is terminal. After the configured [`struggleTimeout`][MrzScannerConfig.struggleTimeout] with no
 * decode, the preview overlays a neutral "still looking / type it instead" hint (mockup 02). The
 * permission-mode states in [ScannerUiState] are handled inside [CameraCapture], not dispatched here.
 */
@Composable
private fun ScannerFlow(
    config: MrzScannerConfig,
    onResult: (MrzScannerResult) -> Unit,
) {
    // The flow starts on the method [initialState] derives from the consumer's enabledMethods (camera first,
    // else manual entry, else saved image; empty → camera defensively) rather than always camera, so a
    // consumer who disabled camera lands on a working entry point (TES-71).
    //
    // TES-102: rememberSaveable(stateSaver = scannerUiStateSaver(...)) — not plain remember — so the whole
    // flow state survives a configuration change (rotation, font-scale, locale), which recreates the Activity
    // and used to silently reset the flow to its start screen. See ScannerUiStateSaver.kt for what each
    // variant restores as. Built fresh from config.enabledMethods (rather than a single top-level Saver) so
    // the restore gate inside it — gateRestoredState — re-validates a restored state against the CURRENT
    // config: this restores the pre-fix guarantee that recreation re-derives the flow from the CURRENT
    // enabledMethods (initialState already did this for a fresh flow; a naively-restoring Saver would have
    // regressed it, letting a saved state resurrect a method the host has since disabled). A host that
    // mutates enabledMethods MID-session, with no recreation in between, was never re-validated before this
    // fix either and stays out of scope — nothing here re-checks a live, un-recreated composition.
    //
    // By deliberate contrast, the session-scoped helpers declared right below (decodeRouted, the consensus
    // tally, sawTextEver, struggleTimeoutElapsed) stay plain remember: they describe the LIVE camera session
    // — a latch over a stream, a frame-agreement tally, per-session flags — which restarts fresh after a
    // recreation along with the camera itself, so persisting them would describe a session that no longer
    // exists.
    var uiState: ScannerUiState by rememberSaveable(
        stateSaver = scannerUiStateSaver(config.enabledMethods),
    ) { mutableStateOf(initialState(config.enabledMethods)) }

    // A one-shot latch: once a decode has routed on (to review / read-failed / straight back), later decoded
    // frames from the still-running stream must not re-fire. Kept as flow state (not inside the collector) so
    // it survives the state transitions the collector drives — e.g. a CameraInUse → Scanning transition on
    // the next ON_RESUME.
    var decodeRouted by remember { mutableStateOf(false) }

    // Frame-agreement gate over the live decode stream: a decode routes on only once consensusReads frames
    // read the SAME document, so a transient OCR misread the MRZ checksums cannot catch (e.g. a filler `<`
    // read as a letter in the name field) cannot win on one bad frame. Reset alongside decodeRouted whenever
    // a fresh live session begins. Saved-image / manual entry are one-shot and route without this gate.
    val consensus = remember(config.consensusReads) { MrzDecodeConsensus(config.consensusReads) }

    // Wall-clock of the last frame that decoded an MRZ, so the "hold steady" cue lingers briefly instead of
    // strobing: detection flickers frame-to-frame even on a steady card (a miss between two decodes is normal),
    // so the cue is cleared only after GATHERING_CUE_LINGER_MS with no decode — long enough to bridge the
    // gaps, short enough to drop promptly once the document actually leaves. A 1-element holder (not Compose
    // state) so updating it never triggers recomposition. `elapsedRealtime` is monotonic (no wall-clock jumps).
    val lastDecodeAtMs = remember { longArrayOf(0L) }

    // TES-93: the in-progress manual-entry text, hoisted here (survives the flow's lifetime) rather than
    // living only inside ScannerUiState.ManualRaw — which onSelectMethod / rescan used to rebuild from scratch
    // (an empty ManualRaw()), silently discarding whatever the user had typed on a Manual→other→Manual
    // round trip. rememberSaveable so it also survives rotation / process death, matching the rest of the
    // flow's persisted state.
    var manualDraft by rememberSaveable { mutableStateOf("") }

    // TES-97: whether OCR has returned text for at least one frame during this scanning session — the gate for
    // entering Struggling (something is in view but unparseable) rather than staying on the plain framing
    // guide (nothing has been in view at all, so "try more light or move farther away" would be misleading).
    // Plain (non-Compose) state, like lastDecodeAtMs, so updating it never triggers recomposition on its own;
    // reset alongside decodeRouted / consensus whenever a fresh live session begins.
    val sawTextEver = remember { booleanArrayOf(false) }

    // TES-97: whether the configured struggle timeout has already elapsed for this session. Split from
    // sawTextEver so a frame that only starts carrying text AFTER the timer already fired still flips the UI
    // to Struggling the moment it arrives, rather than requiring text to have appeared before the timer.
    val struggleTimeoutElapsed = remember { booleanArrayOf(false) }

    // A short confirming haptic when a live-camera read is accepted — the hands-free "it scanned" cue. Honours
    // the device's system haptic setting; opt-out via config.hapticFeedback. Camera path only (manual/saved
    // image get their own tap/selection feedback).
    val haptics = LocalHapticFeedback.current

    val onCancel = { onResult(MrzScannerResult.Cancelled(DismissReason.USER_DISMISSED)) }

    // Routing the moment the reader decodes an MRZ, delegated to the pure decision in routeDecode: a parse
    // failure shows the "couldn't read" screen, instant-return hands the decode straight back, and review
    // mode parks it on the review screen. Kept pure (no camera, no Compose) so it is host-unit-testable.
    val routeDecoded = { decoded: MrzScanResult.Decoded, source: ScanMethod ->
        when (val route = routeDecode(decoded, config.reviewMode)) {
            is DecodeRoute.ShowReadFailed -> uiState = ScannerUiState.ReadFailed(route.capturedText)
            is DecodeRoute.ReturnConfirmed -> onResult(MrzScannerResult.Confirmed(route.decoded))
            is DecodeRoute.ShowReview -> uiState = ScannerUiState.Review(route.decoded, source)
        }
    }

    // The continuous state reducer over the scanner's result stream. Every per-frame result runs through the
    // pure reduceCameraResult and the resulting CameraFlowEffect is applied to uiState here — so the flow's
    // reaction to each result kind (decode, camera-in-use, camera-unavailable, transient miss) is a pure,
    // host-testable decision, and this callback only applies it. CameraInUse is NOT recovered from here: the
    // notice replaces the live preview (CameraInUseContent, see ScannerBody), so the scanner is torn down and
    // no further results reach this callback while it is showing — recovery is the ON_RESUME observer below,
    // the only path back to Scanning. Repeated GoDecoded frames are guarded by the decodeRouted latch (route
    // only the first).
    val onCameraResult = { result: MrzScanResult ->
        when (val effect = reduceCameraResult(result)) {
            is CameraFlowEffect.GoDecoded -> {
                // Live camera is continuous: a parse Failure is a transient bad frame (blurred/garbled OCR —
                // e.g. a chevron misread), not a verdict. Keep scanning and wait for a clean frame rather than
                // committing the flow to a read-failed screen on one bad read (TES-86). Only a parseable read
                // (Success / PartialSuccess — a check-digit mismatch still routes to review, Principle 1)
                // routes on; the struggle hint + manual-entry escape cover persistent trouble. Manual entry
                // and saved-image are one-shot, so they still surface a failure via read-failed (their own path
                // in ScannerBody). The latch only trips on a routed read, so a later clean frame still decodes.
                //
                // A parseable frame is offered to the consensus gate rather than routed on sight: it routes
                // only once consensusReads frames agree on the same document, so a transient misread the MRZ
                // has no check digit for (a filler `<` read as a letter in the name — ASGAR<→ASGARK) can't win
                // on one frame. Gathering → keep scanning; Confirmed → route once (the latch stops repeats).
                if (!decodeRouted && effect.decoded.parse !is ParseResult.Failure) {
                    val verdict = consensus.offer(effect.decoded)
                    when (verdict) {
                        is ConsensusVerdict.Confirmed -> {
                            decodeRouted = true
                            if (config.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            routeDecoded(effect.decoded, ScanMethod.CAMERA)
                        }

                        // Frames are agreeing but the threshold isn't met yet — surface the "hold steady" cue so
                        // the consensus wait reads as active feedback. Only while actually scanning, and getting a
                        // decode means we're making progress, so it supersedes any struggling hint.
                        is ConsensusVerdict.Gathering -> {
                            lastDecodeAtMs[0] = SystemClock.elapsedRealtime()
                            val current = uiState
                            if (current is ScannerUiState.Scanning && !current.gathering) {
                                // copy() so a struggling flag already latched is preserved UNDER the gathering
                                // cue — the two are render-precedence, not mutually exclusive; the one-shot
                                // struggle timer never re-fires, so overwriting it here would kill the "still
                                // looking / Enter details manually" escape for the rest of the session.
                                uiState = current.copy(gathering = true)
                            }
                        }
                    }
                }
            }

            CameraFlowEffect.GoCameraInUse -> {
                uiState = ScannerUiState.CameraInUse
            }

            CameraFlowEffect.GoCameraUnavailable -> {
                uiState = ScannerUiState.CameraUnavailable
            }

            // A transient miss (NoMrzFound / OcrFailed). No routing. This can only be observed while the live
            // preview is actually running (Scanning), never while a CameraInUse notice is showing — that notice
            // replaces the preview and tears the scanner down, so this callback is not invoked at all until the
            // next ON_RESUME re-mounts it (see the DisposableEffect below). The one clean-up here is the "hold
            // steady" cue: it drops if it was showing, because the MRZ has left the frame and the cue must not
            // linger over an empty preview. The consensus tally is kept (a brief occlusion shouldn't lose
            // progress); it resets only on confirm / rescan / method switch.
            is CameraFlowEffect.StayScanning -> {
                // TES-97: fold whether OCR has returned text at least once this session — the gate for whether
                // the struggle timeout is allowed to show Struggling at all (see onStruggling below).
                sawTextEver[0] = struggleGateAdvance(sawTextEver[0], effect)

                val current = uiState
                if (current is ScannerUiState.Scanning) {
                    var next = current
                    // Clear the cue only after the linger window with no decode, so a single miss between two
                    // decodes (normal even on a steady card) does not strobe it; a document that has actually
                    // left the frame stops producing decodes and clears within GATHERING_CUE_LINGER_MS.
                    if (next.gathering && SystemClock.elapsedRealtime() - lastDecodeAtMs[0] > GATHERING_CUE_LINGER_MS) {
                        next = next.copy(gathering = false)
                    }
                    // TES-97: the struggle timeout already elapsed, but nothing carried text until just now —
                    // this qualifying frame arriving late still flips the UI to Struggling (rather than only
                    // checking at the moment the timer itself fires, in onStruggling below).
                    if (!next.struggling && struggleTimeoutElapsed[0] && sawTextEver[0]) {
                        next = next.copy(struggling = true)
                    }
                    if (next != current) uiState = next
                }
            }
        }
    }

    val onManualEntry = { uiState = ScannerUiState.ManualRaw(text = manualDraft) }

    // Retry the camera when the app returns to the foreground while stuck on the camera-in-use notice. That
    // notice replaces the live preview (CameraInUseContent), so the scanner is torn down — and CameraX does
    // NOT auto-retry after ERROR_CAMERA_DISCONNECTED (device-observed: willAttemptRetry=false when another app
    // grabbed the camera). So the "recover on the next clean frame" path can never fire: no scanner, no frames.
    // On ON_RESUME we instead flip back to Scanning, which re-mounts CameraPreview with a fresh scanner and a
    // fresh camera-open attempt; if the camera is free now it streams, and if it is still held it re-errors to
    // CameraInUse and the next resume retries. Only from CameraInUse (recoverable) — CameraUnavailable stays.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && uiState is ScannerUiState.CameraInUse) {
                    decodeRouted = false
                    consensus.reset()
                    sawTextEver[0] = false
                    struggleTimeoutElapsed[0] = false
                    uiState = ScannerUiState.Scanning()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Route a picked saved image through the SavedImageMrzReader, entirely on-device, then apply the pure
    // mapSavedImageResult: a single decode → routeDecode (identical to a camera decode); nothing readable →
    // the empty screen. The reader is built, used, and closed per pick (it owns the ML Kit recognizer's
    // lifetime). Saved-image reading is opt-in and off by default — reaching this flow at all means the
    // consumer enabled ScanMethod.SAVED_IMAGE, which IS the acknowledgement (ADR-023), so the
    // SavedImageReadingAcknowledgement is constructed here internally with no separate user screen. Arm the
    // decode latch afresh so a fresh single-decode route fires.
    val readPickedImage = { uri: Uri ->
        uiState = ScannerUiState.SavedImageAnalyzing
        scope.launch {
            val acknowledgement = SavedImageReadingAcknowledgement()
            val result =
                SavedImageMrzReader(
                    acknowledgement = acknowledgement,
                    recognizer = mlKitSavedImageRecognizer(acknowledgement, context),
                    // LENIENT strips whitespace before shape-matching, exactly as the live camera does (TES-86):
                    // ML Kit routinely injects spaces into MRZ lines (device-observed — e.g. a 30-char TD1 line
                    // read as 32 with two stray spaces), and under STRICT those lines fail their fixed width so
                    // the MRZ band is never detected and any real photo reads as "No MRZ found". Whitespace is
                    // never meaningful in an MRZ, so stripping it is safe.
                    mode = ParsingMode.LENIENT,
                    // Single read, like the live camera — no tolerant candidate enumeration and no "Choose the
                    // reading" screen. A photo takes its one best read straight to review; if OCR misread an
                    // ambiguous glyph, the review's check-digit observations surface it (‼) and the user rescans,
                    // the same safety net the camera has. (Headless consumers can still opt into tolerant reading
                    // via SavedImageMrzReader directly.)
                    tolerant = false,
                ).use { reader -> reader.read(uri) }
            decodeRouted = false
            consensus.reset()
            when (val outcome = mapSavedImageResult(result)) {
                is SavedImageOutcome.SingleDecode -> routeDecoded(outcome.decoded, ScanMethod.SAVED_IMAGE)
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

    // TES-102: a saveable latch guarding the auto-launch below. Before this latch, a configuration change
    // (rotation, font-scale, locale) that landed the restored flow back on AwaitingSavedImagePick re-fired
    // this effect from scratch — rotating while sitting on the dismissed-picker PROMPT silently re-opened the
    // system picker with no new user gesture, and rotating while the picker itself was up double-launched it.
    // rememberSaveable so the latch's own true/false survives the SAME recreation the flow state does, and it
    // is checked (not just set) before calling enterSavedImage() below: a restore lands on this effect with
    // the latch already true (it was set true the first time this state was entered, before the recreation),
    // so the re-fire is suppressed and the prompt just re-shows quietly. Every GENUINE in-session transition
    // into the state still auto-launches exactly as before: entering it for the first time flips this from
    // false, and LEAVING the state (the else branch below) resets it back to false, so a later re-entry (the
    // switcher's Photo tab, a rescan back into saved-image) auto-launches again. Direct enterSavedImage()
    // calls elsewhere in the flow — the prompt's own "Choose a photo" button, returnToSource, SavedImageEmpty's
    // re-pick — are a different code path entirely and are unaffected by this latch.
    var savedImagePickAutoLaunched by rememberSaveable { mutableStateOf(false) }

    // The method switcher's Photo tab and the SAVED_IMAGE-only initial state both land on
    // AwaitingSavedImagePick; entering it launches the picker. Keyed on the state object so a re-entry (e.g.
    // switch away and back to Photo) re-fires this effect — AwaitingSavedImagePick is a data object, so its
    // identity is stable across recompositions and the effect fires once per transition into it, not on every
    // recompose — but savedImagePickAutoLaunched (see above) still gates the ACTUAL launch on top of that.
    LaunchedEffect(uiState) {
        if (uiState == ScannerUiState.AwaitingSavedImagePick) {
            if (!savedImagePickAutoLaunched) {
                savedImagePickAutoLaunched = true
                enterSavedImage()
            }
        } else {
            savedImagePickAutoLaunched = false
        }
    }

    // Switching reading method from the switcher: camera → Scanning, photo → the await-pick launcher state,
    // type → ManualRaw (TES-93: prefilled from the hoisted manualDraft, not a fresh empty entry — a
    // Manual→other→Manual round trip must not silently discard what the user already typed). Re-arm the
    // decode latch so a fresh method's first decode routes on (a previous decode may have set it). Pure target
    // selection; the picker for Photo is launched by the LaunchedEffect above.
    val onSelectMethod = { method: ScanMethod ->
        decodeRouted = false
        consensus.reset()
        sawTextEver[0] = false
        struggleTimeoutElapsed[0] = false
        uiState =
            when (method) {
                ScanMethod.CAMERA -> ScannerUiState.Scanning()
                ScanMethod.SAVED_IMAGE -> ScannerUiState.AwaitingSavedImagePick
                ScanMethod.MANUAL_ENTRY -> ScannerUiState.ManualRaw(text = manualDraft)
            }
    }

    // Returns to whichever reading method produced a [ScannerUiState.Review] — the shared decision behind
    // BOTH the review's "Rescan / Try another photo / Edit entry" secondary action AND the system Back key
    // from a non-expanded review (TES-92 / TES-96): camera → the live preview, photo → re-open the picker,
    // manual → the entry screen, PREFILLED with the lines that were actually submitted for this review
    // (TES-93 — editing a manual-provenance reading should not start from a blank field). Arms the decode
    // latch and the struggle gate afresh so the target method starts clean.
    val returnToSource = { review: ScannerUiState.Review ->
        decodeRouted = false
        consensus.reset()
        sawTextEver[0] = false
        struggleTimeoutElapsed[0] = false
        when (review.source) {
            ScanMethod.CAMERA -> {
                uiState = ScannerUiState.Scanning()
            }

            ScanMethod.SAVED_IMAGE -> {
                enterSavedImage()
            }

            ScanMethod.MANUAL_ENTRY -> {
                manualDraft =
                    review.decoded.recognizedText.lines
                        .joinToString(separator = "\n") { it.text }
                uiState = ScannerUiState.ManualRaw(text = manualDraft)
            }
        }
    }

    // TES-97: fired once, after the configured struggleTimeout elapses with no decode (the timer itself lives
    // in CameraPreview, per scanning session). Only actually shows Struggling when OCR has seen text at least
    // once (sawTextEver) — otherwise nothing has ever been in view, so "try more light or move the document
    // farther away" would be misleading advice; the plain framing guide keeps showing instead, and a later
    // qualifying frame (see the StayScanning branch above) can still flip it on retroactively.
    val onStruggling = {
        struggleTimeoutElapsed[0] = true
        val current = uiState
        if (current is ScannerUiState.Scanning && !current.struggling && sawTextEver[0]) {
            uiState = current.copy(struggling = true)
        }
    }

    // TES-92: the system Back key is owned inside the scanner rather than left to the platform default (which
    // would just finish the hosting Activity, an inconsistent escape compared to the top-bar ✕). The decision
    // is the pure, host-testable backEffect(): collapse the expanded review, return a non-expanded review to
    // its source method (identical to the secondary action above), reopen the saved-image capture prompt from
    // read-failed / the saved-image prompts, or — at the root capture screen — cancel exactly like the ✕
    // (reusing the same onCancel invocation, never a different code path).
    BackHandler {
        when (val effect = backEffect(uiState, cameraEnabled = ScanMethod.CAMERA in config.enabledMethods)) {
            BackEffect.Collapse -> {
                val review = uiState as ScannerUiState.Review
                uiState = review.copy(expanded = false)
            }

            is BackEffect.ReturnToSource -> {
                returnToSource(effect.review)
            }

            BackEffect.ReenterSavedImagePick -> {
                enterSavedImage()
            }

            BackEffect.ReturnToCamera -> {
                decodeRouted = false
                consensus.reset()
                sawTextEver[0] = false
                struggleTimeoutElapsed[0] = false
                uiState = ScannerUiState.Scanning()
            }

            BackEffect.Cancel -> {
                onCancel()
            }
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
            onManualDraftChange = { manualDraft = it },
            onRescanFromReview = returnToSource,
            onStruggling = onStruggling,
        )
    }
}

/**
 * The per-state screen dispatch — the exhaustive `when` over [ScannerUiState], rendered inside the
 * [ScannerScaffold] body (so the shared top bar + method switcher sit above it, TES-71). Split out of
 * [ScannerFlow] purely so the flow function stays readable; it holds no state of its own, receiving the
 * flow's callbacks. [setState] mutates the flow's `uiState`; [onManualDraftChange] keeps the flow's hoisted
 * manual-entry draft in sync with every edit (TES-93); [onRescanFromReview] is the flow's shared
 * `returnToSource` decision, reused here for the Review screen's secondary action and in [ScannerFlow]'s
 * `BackHandler` (TES-92 / TES-96) so the two paths can never drift apart; [onStruggling] is the flow's
 * TES-97 struggle-gate decision, fired once the struggle timeout elapses.
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
    onManualDraftChange: (String) -> Unit,
    onRescanFromReview: (ScannerUiState.Review) -> Unit,
    onStruggling: () -> Unit,
) {
    // Route a decode straight to the matching state via routeDecode — the manual-entry success path, which
    // produces a Decoded that follows the identical decode routing (read-failed / review / straight back
    // under INSTANT_RETURN) a camera or saved-image decode does. Kept local rather than inlined so the
    // manual-entry onRead below stays readable.
    val routeThroughDecode = { decoded: MrzScanResult.Decoded, source: ScanMethod ->
        when (val route = routeDecode(decoded, config.reviewMode)) {
            is DecodeRoute.ShowReadFailed -> setState(ScannerUiState.ReadFailed(route.capturedText))
            is DecodeRoute.ReturnConfirmed -> onResult(MrzScannerResult.Confirmed(route.decoded))
            is DecodeRoute.ShowReview -> setState(ScannerUiState.Review(route.decoded, source))
        }
    }

    // The manual-entry escape (the struggling hint, both camera-status notices, and the permission gate all
    // offer "Enter details manually") is shown only when the consumer actually enabled MANUAL_ENTRY — a
    // camera-only config must not route the user into, and strand them on, a screen they cannot reach any
    // other way (the switcher already respects enabledMethods; this closes the same gap on the camera path).
    val showManualEntry = ScanMethod.MANUAL_ENTRY in config.enabledMethods

    when (state) {
        is ScannerUiState.Scanning -> {
            CameraCapture(
                config = config,
                struggling = state.struggling,
                gathering = state.gathering,
                onCameraResult = onCameraResult,
                onStruggling = onStruggling,
                onManualEntry = onManualEntry,
                showManualEntry = showManualEntry,
                // The whole-scan deadline (TES-85): once the configured scanTimeout elapses with no confirmed
                // reading, the flow ends as Cancelled(TIMED_OUT). The default (INFINITE) never fires. The
                // timer itself lives in CameraPreview, per scanning session, so a rescan restarts it.
                onTimeout = { onResult(MrzScannerResult.Cancelled(DismissReason.TIMED_OUT)) },
            )
        }

        is ScannerUiState.CameraInUse -> {
            CameraInUseContent(onManualEntry = onManualEntry, showManualEntry = showManualEntry)
        }

        is ScannerUiState.CameraUnavailable -> {
            CameraUnavailableContent(onManualEntry = onManualEntry, showManualEntry = showManualEntry)
        }

        is ScannerUiState.Review -> {
            ReviewContent(
                decoded = state.decoded,
                expanded = state.expanded,
                onToggleExpanded = { setState(state.copy(expanded = !state.expanded)) },
                onUse = { onResult(MrzScannerResult.Confirmed(state.decoded)) },
                // The secondary action ("Rescan" / "Try another photo" / "Edit entry", per provenance) returns
                // to the METHOD that produced this review — the flow's shared returnToSource decision
                // (onRescanFromReview), the SAME one the BackHandler uses for a non-expanded review (TES-92 /
                // TES-96), so the two paths can never disagree about where "back" goes.
                onRescan = { onRescanFromReview(state) },
            )
        }

        is ScannerUiState.ReadFailed -> {
            ReadFailedContent(
                capturedText = state.capturedText,
                // Read-failed is only ever reached from the saved-image flow (a picked photo that did not
                // parse) — never the live camera (a camera Failure just keeps scanning) and no longer from
                // manual entry (that now stays inline, below). So "try again" re-opens the photo picker, not
                // the camera.
                onTryAgain = enterSavedImage,
                // The read-failed / error escape into manual entry (also reachable from the method switcher).
                onManualEntry = onManualEntry,
                showManualEntry = showManualEntry,
            )
        }

        is ScannerUiState.ManualRaw -> {
            ManualRawContent(
                state = state,
                // Any edit clears a prior parse-failed note (the input the user is fixing is no longer
                // "failed") AND is mirrored into the flow's hoisted manualDraft (TES-93), so the text survives
                // a method switch away and back, or a rescan back into manual entry.
                onTextChange = {
                    onManualDraftChange(it)
                    setState(state.copy(text = it, parseFailed = false))
                },
                // Assemble a Decoded from the typed text (pure, host-tested; format auto-detected, TES-100). A
                // Success / PartialSuccess routes to review (or straight back under INSTANT_RETURN). A parse
                // Failure stays HERE with an inline note — the typed text is preserved and there is no jump to
                // the camera-flavoured read-failed screen (which mislabels typed input "blurred or partial" and
                // offers a confusing "try again → camera"). The manual-entry read method flows through on
                // success, so review shows "Read by manual entry" with no extra wiring.
                onRead = {
                    val decoded = assembleManualDecoded(state.text)
                    if (decoded.parse is ParseResult.Failure) {
                        setState(state.copy(parseFailed = true))
                    } else {
                        routeThroughDecode(decoded, ScanMethod.MANUAL_ENTRY)
                    }
                },
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

        ScannerUiState.SavedImageEmpty -> {
            SavedImageEmptyContent(
                onChooseDifferent = enterSavedImage,
                onManualEntry = onManualEntry,
                showManualEntry = showManualEntry,
            )
        }
        // (The camera-initializing state is not a flow state at all — CameraPreview renders it from a null
        // preview surface, see there.)
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
 * What the system Back key should do from the current [ScannerUiState] — the pure decision behind
 * [ScannerFlow]'s `BackHandler` (TES-92). Extracted Compose-free, mirroring [routeDecode] / [reduceCameraResult],
 * so the mapping is host-unit-testable without triggering a real back-press dispatcher.
 */
internal sealed interface BackEffect {
    /** Collapse the expanded all-fields review back to the summary (mirrors "Show less ▴"). */
    data object Collapse : BackEffect

    /** Return to whichever method produced this (non-expanded) review — identical to its secondary action. */
    data class ReturnToSource(
        val review: ScannerUiState.Review,
    ) : BackEffect

    /** Reopen the saved-image capture prompt (from read-failed, which is only ever reached via saved-image). */
    data object ReenterSavedImagePick : BackEffect

    /** Return to the live camera (from a saved-image prompt reached by switching away from Camera). */
    data object ReturnToCamera : BackEffect

    /** Nothing further to unwind to — cancel exactly like the top-bar ✕. */
    data object Cancel : BackEffect
}

/**
 * The Back decision for [state], decided purely from the state and whether the camera is one of the
 * consumer's enabled methods (TES-92):
 *  * an expanded [`Review`][ScannerUiState.Review] → [BackEffect.Collapse];
 *  * a non-expanded `Review` → [BackEffect.ReturnToSource] (the same target its own secondary action uses);
 *  * [`ReadFailed`][ScannerUiState.ReadFailed] (reached only from the saved-image flow) →
 *    [BackEffect.ReenterSavedImagePick];
 *  * the saved-image prompts ([`AwaitingSavedImagePick`][ScannerUiState.AwaitingSavedImagePick] /
 *    [`SavedImageEmpty`][ScannerUiState.SavedImageEmpty]) → back to the live camera when it is enabled
 *    ([BackEffect.ReturnToCamera] — this is how a user who switched Camera→Photo gets back), otherwise
 *    [BackEffect.Cancel] (saved-image is the actual entry point here, so there is nowhere else to go);
 *  * every other state (the root capture screens — [`Scanning`][ScannerUiState.Scanning],
 *    [`ManualRaw`][ScannerUiState.ManualRaw] — and the notice / gate screens with no back target of their own)
 *    → [BackEffect.Cancel], identical to the top-bar ✕.
 */
internal fun backEffect(
    state: ScannerUiState,
    cameraEnabled: Boolean,
): BackEffect =
    when (state) {
        is ScannerUiState.Review -> {
            if (state.expanded) BackEffect.Collapse else BackEffect.ReturnToSource(state)
        }

        is ScannerUiState.ReadFailed -> {
            BackEffect.ReenterSavedImagePick
        }

        ScannerUiState.AwaitingSavedImagePick, ScannerUiState.SavedImageEmpty -> {
            if (cameraEnabled) BackEffect.ReturnToCamera else BackEffect.Cancel
        }

        else -> {
            BackEffect.Cancel
        }
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

    /**
     * Another app holds the camera (recoverable). Show the in-use notice — which replaces the live preview, so
     * the scanner is torn down under it; recovery is the flow's `ON_RESUME` observer re-mounting a fresh
     * preview, not anything this stream produces (there is no retry button).
     */
    data object GoCameraInUse : CameraFlowEffect

    /** The camera cannot be started (terminal). Show the unavailable notice; no auto-recovery, no retry. */
    data object GoCameraUnavailable : CameraFlowEffect

    /**
     * A transient per-frame miss (`NoMrzFound` / `OcrFailed`) — keep scanning, no routing. [sawText] is TES-97's
     * "did OCR return any text at all on this frame" signal (`quality.recognizedLineCount > 0`): `false` for a
     * frame with nothing recognisable in view, `true` for a frame where OCR saw text that just did not form an
     * MRZ shape. [ScannerFlow] folds this across the session ([struggleGateAdvance]) to decide whether the
     * struggle timeout is allowed to show the Struggling overlay at all.
     */
    data class StayScanning(
        val sawText: Boolean,
    ) : CameraFlowEffect
}

/**
 * The flow-state decision for one [MrzScanResult] off the scanner's stream, decided purely from the result
 * kind:
 *  * a [`Decoded`][MrzScanResult.Decoded] → [CameraFlowEffect.GoDecoded];
 *  * a [`CaptureError`][MrzScanResult.CaptureError] carrying [`CameraInUse`][CameraError.CameraInUse] →
 *    [CameraFlowEffect.GoCameraInUse] (recoverable — but only on the next `ON_RESUME`, not from this stream;
 *    no retry button);
 *  * a `CaptureError` carrying [`CameraUnavailable`][CameraError.CameraUnavailable] →
 *    [CameraFlowEffect.GoCameraUnavailable] (terminal);
 *  * a `CaptureError` carrying [`OcrFailed`][CameraError.OcrFailed] (a transient per-frame OCR miss) and a
 *    [`NoMrzFound`][MrzScanResult.NoMrzFound] → [CameraFlowEffect.StayScanning], carrying whether this frame's
 *    [`ScanQuality.recognizedLineCount`][io.lightine.tessera.mrz.camera.ScanQuality.recognizedLineCount] was
 *    non-zero (TES-97 — "OCR saw text" vs "nothing in view at all").
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
            CameraFlowEffect.StayScanning(sawText = result.quality.recognizedLineCount > 0)
        }

        is MrzScanResult.CaptureError -> {
            val sawText = result.quality.recognizedLineCount > 0
            when (result.error) {
                is CameraError.CameraInUse -> CameraFlowEffect.GoCameraInUse
                is CameraError.CameraUnavailable -> CameraFlowEffect.GoCameraUnavailable
                is CameraError.OcrFailed -> CameraFlowEffect.StayScanning(sawText)
                is CameraError.PermissionDenied -> CameraFlowEffect.StayScanning(sawText)
            }
        }
    }

/**
 * Folds one [CameraFlowEffect.StayScanning] into whether OCR has returned text at least once this scanning
 * session (TES-97) — an OR-fold: [sawTextEver] stays `true` once any frame carried text, regardless of later
 * text-free frames (a document that briefly leaves frame should not un-arm Struggling). This is the gate
 * [ScannerFlow] checks before showing the Struggling overlay: without it, a struggle timeout with nothing ever
 * in view would show "try more light or move the document farther away" — misleading advice when there was
 * never anything to read in the first place. Pure so the "ANY, not the latest frame" semantics is
 * host-testable without a timer or camera.
 */
internal fun struggleGateAdvance(
    sawTextEver: Boolean,
    effect: CameraFlowEffect.StayScanning,
): Boolean = sawTextEver || effect.sawText

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
 * @param gathering whether the "hold steady" gathering cue is overlaid on the preview (a read is being
 *   confirmed across frames); takes render precedence over [struggling].
 * @param onCameraResult every scanner result off the stream, for the flow's continuous reducer.
 * @param onStruggling fired once the struggle timeout elapses with no decode (flips the flow to struggling).
 * @param onTimeout fired once the whole-scan [`scanTimeout`][MrzScannerConfig.scanTimeout] elapses with no
 *   confirmed reading (the flow ends as `Cancelled(TIMED_OUT)`).
 * @param onManualEntry the "type it instead" escape into manual entry.
 * @param showManualEntry whether that escape is offered at all — `false` when the consumer's
 *   [`enabledMethods`][MrzScannerConfig.enabledMethods] excludes [`MANUAL_ENTRY`][ScanMethod.MANUAL_ENTRY], so
 *   a camera-only config never routes the user into a screen they cannot reach any other way.
 */
@Composable
private fun CameraCapture(
    config: MrzScannerConfig,
    struggling: Boolean,
    gathering: Boolean,
    onCameraResult: (MrzScanResult) -> Unit,
    onStruggling: () -> Unit,
    onTimeout: () -> Unit,
    onManualEntry: () -> Unit,
    showManualEntry: Boolean,
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
                scanTimeout = config.scanTimeout,
                struggling = struggling,
                gathering = gathering,
                showTorchButton = config.showTorchButton,
                torchOnByDefault = config.torchOnByDefault,
                onCameraResult = onCameraResult,
                onStruggling = onStruggling,
                onTimeout = onTimeout,
                onManualEntry = onManualEntry,
                showManualEntry = showManualEntry,
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
                showManualEntry = showManualEntry,
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
                showManualEntry = showManualEntry,
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
 * what each one means (route a decode once, surface camera-in-use, surface camera-unavailable). The one-shot
 * decode latch lives in the flow, not here, so the collector stays a plain forwarder. A camera-in-use result
 * REPLACES this whole composable with [CameraInUseContent] (see `ScannerBody`), so this preview — and this
 * collector — stop running the instant that happens; recovery is the flow's `ON_RESUME` observer re-mounting
 * a fresh preview, not anything observed from here.
 *
 * The struggle timeout is a [LaunchedEffect] that waits [struggleTimeout] and then fires [onStruggling]; it
 * is keyed on the scanner so it starts once per preview session, and the camera keeps running underneath —
 * a later decode still routes normally, and the flow drops the hint on any progress. The config default is
 * 10s (finite); a non-finite value ([Duration.INFINITE]) means "never struggle", so the effect does not arm.
 *
 * The scan timeout (TES-85) is a second [LaunchedEffect] on the same key: after [scanTimeout] with no
 * confirmed reading it fires [onTimeout] (the flow ends as `Cancelled(TIMED_OUT)`). It is the whole-scan
 * *deadline* — distinct from the struggle hint, which only nudges — and, like the struggle timer, runs once
 * per scanning session (a rescan re-mounts this preview and restarts it). The default ([Duration.INFINITE])
 * never arms, so the scanner runs indefinitely unless the consumer sets a finite [scanTimeout].
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
    scanTimeout: Duration,
    struggling: Boolean,
    gathering: Boolean,
    showTorchButton: Boolean,
    torchOnByDefault: Boolean,
    onCameraResult: (MrzScanResult) -> Unit,
    onStruggling: () -> Unit,
    onTimeout: () -> Unit,
    onManualEntry: () -> Unit,
    showManualEntry: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Keep the screen awake and at full brightness while the live preview is up — a user lining up a document
    // should not have the screen dim or sleep mid-scan. Both are scoped to this composable, so they clear the
    // moment the preview leaves (review / manual / camera closed / screen destroyed), restoring the system
    // defaults; nothing leaks the elevated brightness or the wake-lock past camera mode.
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(view, activity) {
        view.keepScreenOn = true
        val window = activity?.window
        val previousBrightness = window?.attributes?.screenBrightness
        window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = 1f } }
        onDispose {
            view.keepScreenOn = false
            window?.let { w ->
                w.attributes =
                    w.attributes.apply {
                        screenBrightness = previousBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // WYSIWYG viewport (TES-86): built from the preview area's measured size + the display rotation so
        // CameraX crops the analysis frame to the SAME sensor region the viewfinder shows — this is what makes
        // the MRZ band line up with the on-screen framing guide. Rebuilt if the measured size changes.
        val displayRotation =
            remember(context) {
                // Context.getDisplay() (API 30+, Android 11) is the non-deprecated read of the display rotation.
                // WindowManager.defaultDisplay was deprecated in API 30; it is used only BELOW 30, where
                // getDisplay() does not exist (minSdk 23) — so no deprecated API is ever called on a modern device.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.display.rotation
                } else {
                    @Suppress("DEPRECATION")
                    (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
                }
            }
        val viewport =
            remember(constraints, displayRotation) {
                if (constraints.hasBoundedWidth && constraints.hasBoundedHeight &&
                    constraints.maxWidth > 0 && constraints.maxHeight > 0
                ) {
                    ViewPort
                        .Builder(Rational(constraints.maxWidth, constraints.maxHeight), displayRotation)
                        .build()
                } else {
                    null
                }
            }
        val scanner =
            remember(context, lifecycleOwner) {
                CameraXMrzScanner(
                    appContext = context.applicationContext,
                    lifecycleOwner = lifecycleOwner,
                    // Restrict OCR to the MRZ band the guide marks (TES-86). The viewport above makes the
                    // analysis frame match the viewfinder, so the band lines up with the on-screen guide.
                    recognizer = MlKitMrzTextRecognizer().apply { restrictToMrzBand() },
                    // Lenient candidate extraction for the live camera: ML Kit routinely injects spaces into
                    // and splits MRZ lines, and a space is never meaningful in an MRZ. LENIENT strips all
                    // whitespace before shape-matching, so a line ML Kit read as "…459 AZBPU…" still matches
                    // its fixed width and is detected — device-observed to be the dominant cause of dropped
                    // frames (a steady card was detected on only ~half of frames under STRICT).
                    mode = ParsingMode.LENIENT,
                ).apply { enablePreview(viewport) }
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

        // Scan timeout (TES-85): the whole-scan deadline. After scanTimeout with no confirmed reading, give up and
        // end the flow as Cancelled(TIMED_OUT). Keyed on the scanner so it runs once per scanning session (a
        // rescan re-mounts this preview and restarts it), mirroring the struggle timer above — but where struggle
        // only nudges, this ends the flow. INFINITE (the default) never arms.
        LaunchedEffect(scanner) {
            if (scanTimeout.isFinite()) {
                delay(scanTimeout)
                onTimeout()
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

                // The MRZ framing guide (TES-87/TES-86): a dimmed scrim over the whole preview with the guide
                // window punched out and a dashed frame around it — line the MRZ up inside it. Centre puts the
                // MRZ in the lens's sharpest region and the camera's continuous-autofocus / exposure sweet spot
                // (faster, more reliable reads — device-tuned), and the OCR band is cropped to this same centred
                // region (WYSIWYG). It also hosts the single guidance region just below the window — framing
                // hint / "hold steady" / "still looking" swap in place there, at the focal point. Advisory only.
                MrzGuideOverlay(
                    gathering = gathering,
                    struggling = struggling,
                    onManualEntry = onManualEntry,
                    showManualEntry = showManualEntry,
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

                // Pinned to the bottom of the live preview and ALWAYS visible: the "What's the MRZ?" explainer.
                // Deliberately kept out of the mid-screen guidance region (framing hint / "hold steady" /
                // "still looking", which swap in place there), so the explainer is never replaced and stays
                // reachable in every scanning state. Over the guide overlay's dark scrim, so the text is light.
                // ("Powered by Tessera" is the shared scaffold footer now, present on every screen.)
                MrzExplainerLink(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp),
                )
            } else {
                InitializingContent()
            }
            // The advisory guidance (framing hint / "hold steady" / "still looking") is rendered inside
            // MrzGuideOverlay, in one region just below the guide window — at the focal point, not the screen
            // edge — so it never collides with the torch and does not compete with the framing hint.
        }
    }
}

/**
 * The struggling hint overlaid on the live preview (mockup 02): a neutral advisory line ("still looking — try
 * more light or move farther away") and a "Type it instead" affordance into manual entry. Advisory only — it never
 * states an error or a verdict, and the camera keeps scanning underneath (a decode arriving after the hint
 * still routes normally). [onManualEntry] switches to manual raw entry.
 *
 * `internal` (not `private`): the live-preview host it overlays needs a real camera and cannot run under
 * Robolectric, so the hint's copy and its "Type it instead" affordance are host-tested through this entry
 * point directly (the same composable the flow overlays), per the testing-layers rule.
 *
 * @param showManualEntry whether the "Type it instead" affordance is offered — `false` when the consumer's
 *   `enabledMethods` excludes `MANUAL_ENTRY`, so a camera-only config never dangles an escape into a screen
 *   the user cannot reach any other way. Defaults to `true` (every existing caller keeps its prior behaviour).
 */
@Composable
internal fun StrugglingHint(
    onManualEntry: () -> Unit,
    modifier: Modifier = Modifier,
    showManualEntry: Boolean = true,
) {
    Column(
        modifier = modifier.padding(24.dp).testTag(STRUGGLING_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.tessera_scanner_struggling_hint),
            // White for legibility over the guide overlay's dark scrim (the scrim is a fixed ~70% black
            // regardless of theme, so the message is always light).
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            // The hint overlays the preview via the struggle-timeout auto-transition (mockup 02), so it is a
            // polite live region — announced when it appears without the user having to move focus to it.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (showManualEntry) {
            TextButton(onClick = onManualEntry) {
                Text(text = stringResource(R.string.tessera_scanner_struggling_manual))
            }
        }
    }
}

/**
 * The "hold steady" gathering cue overlaid on the live preview while the frame-agreement gate
 * ([`MrzDecodeConsensus`][io.lightine.tessera.mrz.camera.MrzDecodeConsensus]) confirms a read across several
 * frames. It gives the consensus wait visible feedback — a small activity indicator plus "Hold steady…" — so a
 * multi-frame confirmation reads as active progress rather than lag, and nudges the user to keep the document
 * still so the frames agree. Advisory only — it states no error and no verdict (Principle 1); the camera keeps
 * scanning underneath and a confirmed read routes on normally.
 *
 * `internal` (not `private`): host-tested through this entry point directly, like [StrugglingHint] — the live
 * preview it overlays needs a real camera and cannot run under Robolectric.
 */
@Composable
internal fun GatheringHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp).testTag(GATHERING_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
        Text(
            text = stringResource(R.string.tessera_scanner_gathering_hint),
            // White for legibility over the guide overlay's dark scrim (fixed ~70% black regardless of theme).
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            // Appears on an auto-transition (a read starting to confirm), not a focus move, so it is a polite
            // live region — announced when it appears without the user having to move focus to it.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * The torch (flashlight) toggle overlaid on the live preview (mockup 01, top-end) — [TES-84]. A compact
 * circular control the user taps to turn the device flash on or off while scanning in low light. It carries
 * no trust meaning; it only helps the camera see (Principle 1 is untouched). Styled for legibility over the
 * live camera: a translucent-black pill when off, the theme accent when on — and now (TES-84) the icon itself
 * changes shape between the two states ([FlashlightOnIcon] / [FlashlightOffIcon]), not just the tint, so the
 * on/off distinction never depends on colour alone even before semantics are read. Hand-rolled vectors rather
 * than a Material icon — this module does not depend on `material-icons` (the same reason the top-bar ✕ is a
 * glyph, see [ScannerScaffold]).
 *
 * **A11y (TES-47/TES-58/TES-84).** A [`Switch`][Role.Switch]-role [`toggleable`][toggleable] with an explicit
 * [`stateDescription`][androidx.compose.ui.semantics.stateDescription] ("On"/"Off"), so a screen reader
 * announces the state in those exact words on top of the Switch role's own announced toggled state — never
 * carried by colour or icon shape alone. The control's name comes from the overridable `tessera_scanner_torch`
 * label; the icon is decorative ([clearAndSetSemantics]) so it is not separately announced.
 * [minimumInteractiveComponentSize] guarantees the ≥48dp touch target while the visible pill stays compact.
 *
 * `internal` (not `private`): the real torch wiring lives in [CameraPreview], which drives real CameraX and
 * cannot run under Robolectric, so the button's rendering, label, toggle semantics, and click are host-tested
 * through this entry point directly — the same testing-layers pattern as [StrugglingHint].
 *
 * @param torchOn whether the torch is currently on (drives the icon shape/tint and the toggle state).
 * @param onToggle fired when the user taps the control (the caller flips [torchOn] and drives the flash).
 */
@Composable
internal fun TorchButton(
    torchOn: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.tessera_scanner_torch)
    val stateDesc =
        if (torchOn) {
            stringResource(R.string.tessera_scanner_torch_state_on)
        } else {
            stringResource(R.string.tessera_scanner_torch_state_off)
        }
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
                ).semantics {
                    contentDescription = label
                    stateDescription = stateDesc
                }.padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (torchOn) FlashlightOnIcon else FlashlightOffIcon,
            // Decorative — the label + stateDescription carry the meaning for a screen reader.
            contentDescription = null,
            tint = if (torchOn) MaterialTheme.colorScheme.onPrimary else Color.White,
            modifier = Modifier.size(20.dp),
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
internal fun MrzGuideOverlay(
    modifier: Modifier = Modifier,
    gathering: Boolean = false,
    struggling: Boolean = false,
    onManualEntry: () -> Unit = {},
    showManualEntry: Boolean = true,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // One shared geometry for the cut-out window: horizontally inset, a fixed band height, vertically
        // centred. The scrim, the dashed frame, and the message below all derive from it, so the dimmed
        // surround, the frame, and the guidance can never drift apart.
        val marginH = 24.dp
        val bandHeight = 96.dp
        val corner = 10.dp
        val bandWidth = maxWidth - marginH * 2
        val bandTop = (maxHeight - bandHeight) / 2

        // Scrim + dashed frame, decorative (the message below carries the meaning). A ~70%-black overlay covers
        // the whole preview with the guide window punched out (even-odd fill), so the MRZ target stands out and
        // the message reads against a consistent dark backdrop rather than the live image. The dashed stroke on
        // the same rounded rect matches the iOS RoundedRectangle.strokeBorder(dash: [8, 6]).
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {}
                    .drawBehind {
                        val left = marginH.toPx()
                        val top = bandTop.toPx()
                        val width = bandWidth.toPx()
                        val height = bandHeight.toPx()
                        val radius = CornerRadius(corner.toPx(), corner.toPx())
                        val scrim =
                            Path().apply {
                                addRect(Rect(0f, 0f, size.width, size.height))
                                addRoundRect(RoundRect(Rect(left, top, left + width, top + height), radius))
                                fillType = PathFillType.EvenOdd
                            }
                        drawPath(scrim, color = Color.Black.copy(alpha = 0.7f))
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.9f),
                            topLeft = Offset(left, top),
                            size = Size(width, height),
                            cornerRadius = radius,
                            style =
                                Stroke(
                                    width = 2.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()), 0f),
                                ),
                        )
                    },
        )
        // ONE guidance region, just below the cut-out window — the single place the user looks while framing, so
        // feedback stays at the focal point instead of scattered to the screen edges (where it collides with the
        // torch and competes with the framing hint). It swaps with the scan state: gathering → "hold steady",
        // struggling → the "still looking / type it instead" nudge, otherwise the framing hint. All over the
        // scrim so they read clearly; width-capped + centred (contentMaxWidth, TES-78).
        //
        // TES-95: at a large system font size, StrugglingHint's multi-line advisory + button can grow taller
        // than the fixed gap this region used to assume, reaching down into the ALWAYS-pinned "What's the
        // MRZ?" link at the very bottom (MrzExplainerLink, in the caller) and overlapping its clickable area.
        // Rather than another fixed offset (which just moves the same problem), the region's own height is
        // capped to the space actually measured between the guide window and a reserved band for that pinned
        // link, and scrolls internally if its content still does not fit — it can grow, but it can never
        // silently overlap the link below it.
        val guidanceMaxHeight = (maxHeight - bandTop - bandHeight - GuidanceBottomReserved).coerceAtLeast(0.dp)
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = bandTop + bandHeight)
                    .heightIn(max = guidanceMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .contentMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                gathering -> {
                    GatheringHint()
                }

                struggling -> {
                    StrugglingHint(onManualEntry = onManualEntry, showManualEntry = showManualEntry)
                }

                else -> {
                    Text(
                        text = stringResource(R.string.tessera_scanner_camera_guide),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .padding(top = 16.dp, start = marginH, end = marginH)
                                .testTag(GUIDE_HINT_TEST_TAG),
                    )
                }
            }
        }
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
