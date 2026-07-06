// @Composable functions are PascalCase by Compose convention, which ktlint's standard function-naming
// rule flags. Suppressed at file scope (the idiomatic per-Compose-file exemption) rather than repo-wide:
// Spotless's ktlint step does not reliably pass the editorconfig `ignore_when_annotated_with` value
// through, and forcing it via editorConfigOverride perturbs the code-style baseline for other modules.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.compose.CameraXViewfinder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.lightine.tessera.mrz.camera.CameraXMrzScanner
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.parsing.ParseResult

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
 * **Slice status:** this slice adds the review path on top of the live camera preview — a decoded MRZ
 * routes to a review screen (parsed fields + honest observations) the user accepts or rescans, a parse
 * failure routes to a "couldn't read" screen, and [`INSTANT_RETURN`][ReviewMode.INSTANT_RETURN] returns a
 * decode with no review step. The saved-image and manual-entry screens land in the following 0.5.0 slices
 * (their states are declared in [ScannerUiState] but not yet wired). The signature (one config in, one
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

/** Semantics anchor for the camera-permission prompt shown when the permission is not held. Not user-facing. */
internal const val PERMISSION_PROMPT_TEST_TAG: String = "tessera-mrz-permission-prompt"

/** Semantics anchor for the review screen (parsed fields + observations). Not user-facing. */
internal const val REVIEW_TEST_TAG: String = "tessera-mrz-review"

/** Semantics anchor for the review screen's expanded all-fields + raw-MRZ view. Not user-facing. */
internal const val REVIEW_EXPANDED_TEST_TAG: String = "tessera-mrz-review-expanded"

/** Semantics anchor for the "couldn't read this MRZ" screen. Not user-facing. */
internal const val READ_FAILED_TEST_TAG: String = "tessera-mrz-read-failed"

/**
 * Holds the current [ScannerUiState] and dispatches the matching screen. The screen is a one-shot flow: the
 * camera capture decodes an MRZ, which routes here to the review or read-failed screen (or straight back to
 * the host under [`INSTANT_RETURN`][ReviewMode.INSTANT_RETURN]); a cancel anywhere reports
 * [`Cancelled(USER_DISMISSED)`][DismissReason.USER_DISMISSED]. The state starts at [ScannerUiState.Scanning]
 * — the live-camera path is the only entry wired in this slice; the saved-image / manual-entry / error
 * states in [ScannerUiState] are declared for the later slices but not yet reachable here.
 */
@Composable
private fun ScannerFlow(
    config: MrzScannerConfig,
    onResult: (MrzScannerResult) -> Unit,
) {
    var uiState: ScannerUiState by remember { mutableStateOf(ScannerUiState.Scanning()) }

    val onCancel = { onResult(MrzScannerResult.Cancelled(DismissReason.USER_DISMISSED)) }

    // Routing the moment the reader decodes an MRZ, delegated to the pure decision in routeDecode: a parse
    // failure shows the "couldn't read" screen, instant-return hands the decode straight back, and review
    // mode parks it on the review screen. Kept pure (no camera, no Compose) so it is host-unit-testable.
    val onDecoded = { decoded: MrzScanResult.Decoded ->
        when (val route = routeDecode(decoded, config.reviewMode)) {
            is DecodeRoute.ShowReadFailed -> uiState = ScannerUiState.ReadFailed(route.capturedText)
            is DecodeRoute.ReturnConfirmed -> onResult(MrzScannerResult.Confirmed(route.decoded))
            is DecodeRoute.ShowReview -> uiState = ScannerUiState.Review(route.decoded)
        }
    }

    when (val state = uiState) {
        is ScannerUiState.Scanning -> {
            CameraCapture(config = config, onDecoded = onDecoded, onCancel = onCancel)
        }

        is ScannerUiState.Review -> {
            ReviewContent(
                decoded = state.decoded,
                expanded = state.expanded,
                onToggleExpanded = { uiState = state.copy(expanded = !state.expanded) },
                onUse = { onResult(MrzScannerResult.Confirmed(state.decoded)) },
                onRescan = { uiState = ScannerUiState.Scanning() },
            )
        }

        is ScannerUiState.ReadFailed -> {
            ReadFailedContent(
                capturedText = state.capturedText,
                onTryAgain = { uiState = ScannerUiState.Scanning() },
                // The read-failed / error escape into manual entry (TES-63). The switcher entry from the
                // camera is a later slice; for now this is how the user reaches manual entry.
                onManualEntry = { uiState = ScannerUiState.ManualRaw() },
            )
        }

        is ScannerUiState.ManualRaw -> {
            ManualRawContent(
                state = state,
                onTextChange = { uiState = state.copy(text = it) },
                // Assemble a Decoded from the typed text (pure, host-tested), then route it exactly as a
                // camera decode: a parse Failure shows the read-failed screen, a Success / PartialSuccess
                // goes to review (or straight back under INSTANT_RETURN). The manual-entry read method flows
                // through, so the review screen shows "Read by manual entry" with no extra wiring.
                onRead = { hint ->
                    val decoded = assembleManualDecoded(state.text, hint)
                    when (val route = routeDecode(decoded, config.reviewMode)) {
                        is DecodeRoute.ShowReadFailed -> uiState = ScannerUiState.ReadFailed(route.capturedText)
                        is DecodeRoute.ReturnConfirmed -> onResult(MrzScannerResult.Confirmed(route.decoded))
                        is DecodeRoute.ShowReview -> uiState = ScannerUiState.Review(route.decoded)
                    }
                },
                onBack = onCancel,
            )
        }

        // The remaining ScannerUiState variants (Initializing, permission/camera errors, saved image,
        // manual field-by-field entry) are the declared contract for the later 0.5.0 slices — not reachable
        // in this one.
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
 * The camera-permission gate and, once the permission is held, the live preview with a results collector.
 * Reads whether `CAMERA` is held and re-reads it whenever the host returns to the foreground, so a grant
 * made outside this screen takes effect without the consumer re-launching it. When held, the live preview
 * shows and its decoded readings are surfaced through [onDecoded]; otherwise the permission prompt hands
 * the request to the host. The SDK only *reads* the permission — it never requests it.
 */
@Composable
private fun CameraCapture(
    config: MrzScannerConfig,
    onDecoded: (MrzScanResult.Decoded) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember { mutableStateOf(context.hasCameraPermission()) }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasCameraPermission = context.hasCameraPermission()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (hasCameraPermission) {
        CameraPreview(onDecoded = onDecoded, onCancel = onCancel)
    } else {
        PermissionPrompt(onRequestPermission = config.onRequestPermission, onCancel = onCancel)
    }
}

/**
 * The live preview. The scanner ([CameraXMrzScanner]) owns the camera session and lifecycle; this
 * composable only draws and observes. One scanner is tied to this composition with its opt-in preview
 * armed, run while the preview is shown, and closed when it leaves. Only the `SurfaceRequest` surface
 * handle is hoisted into Compose state — never an `ImageProxy`, frame, or decoded field held in state (the
 * module's memory-hygiene commitment): a decoded reading is handed straight to [onDecoded] and not retained
 * here.
 *
 * The results collector consumes the scanner's per-frame [`results`][CameraXMrzScanner.results] flow and
 * fires [onDecoded] on the **first** [`Decoded`][MrzScanResult.Decoded] only — a scanning stream emits many
 * frames, but the review flow is one-shot, so a local latch stops after the first decode. Non-decoded
 * frames (`NoMrzFound`, `CaptureError`) are the normal per-frame churn and are ignored here; the
 * error/quality states in [ScannerUiState] handle capture errors in a later slice.
 */
@Composable
private fun CameraPreview(
    onDecoded: (MrzScanResult.Decoded) -> Unit,
    onCancel: () -> Unit,
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
    DisposableEffect(scanner) {
        scanner.start()
        onDispose { scanner.close() }
    }

    LaunchedEffect(scanner) {
        var consumed = false
        scanner.results.collect { result ->
            if (!consumed && result is MrzScanResult.Decoded) {
                consumed = true
                onDecoded(result)
            }
        }
    }

    val surfaceRequest by scanner.surfaceRequest.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        surfaceRequest?.let { request ->
            CameraXViewfinder(
                surfaceRequest = request,
                modifier = Modifier.fillMaxSize().testTag(VIEWFINDER_TEST_TAG),
            )
        }
        // User-facing copy comes from the module's overridable tessera_* string resources (TES-46): a
        // consumer translates/rebrands by redefining the same key. See res/values/strings.xml.
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = stringResource(R.string.tessera_scanner_camera_hint))
            Button(onClick = onCancel) {
                Text(text = stringResource(R.string.tessera_scanner_cancel))
            }
        }
    }
}

/**
 * Shown when the `CAMERA` permission is not held. Offers to hand the request to the host (only when the
 * consumer supplied [MrzScannerConfig.onRequestPermission]) and a cancel path. The SDK never requests the
 * permission itself.
 */
@Composable
private fun PermissionPrompt(
    onRequestPermission: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(PERMISSION_PROMPT_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = stringResource(R.string.tessera_scanner_permission_rationale))
        if (onRequestPermission != null) {
            Button(onClick = onRequestPermission) {
                Text(text = stringResource(R.string.tessera_scanner_grant_permission))
            }
        }
        Button(onClick = onCancel) {
            Text(text = stringResource(R.string.tessera_scanner_cancel))
        }
    }
}

private fun Context.hasCameraPermission(): Boolean = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
