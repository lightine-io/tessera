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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.lightine.tessera.mrz.camera.CameraXMrzScanner

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
 * **Slice status:** this slice renders the live camera preview — a [CameraXViewfinder] fed by the
 * scanner's opt-in `SurfaceRequest` seam — behind the module theme, with a camera-permission gate and a
 * working cancel path. The review (decode → confirm) and manual-entry screens land in the following
 * 0.5.0 slices, and the user-facing copy here moves to `tessera_*` string resources in the
 * resource-overlay slice. The signature (one config in, one result out) is the shape that freezes at the
 * 0.5.0 tag.
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
    TesseraScannerTheme(useDynamicColor = config.useDynamicColor) {
        Surface(modifier = Modifier.fillMaxSize().testTag(SCANNER_ROOT_TEST_TAG)) {
            ScanningContent(
                config = config,
                onCancel = { onResult(MrzScannerResult.Cancelled) },
            )
        }
    }
}

/** A stable semantics anchor for the screen's root, used by the host-side Compose tests. Not user-facing. */
internal const val SCANNER_ROOT_TEST_TAG: String = "tessera-mrz-scanner-root"

/** Semantics anchor for the live camera viewfinder. Not user-facing. */
internal const val VIEWFINDER_TEST_TAG: String = "tessera-mrz-viewfinder"

/** Semantics anchor for the camera-permission prompt shown when the permission is not held. Not user-facing. */
internal const val PERMISSION_PROMPT_TEST_TAG: String = "tessera-mrz-permission-prompt"

/**
 * Camera-permission gate. Reads whether `CAMERA` is held and re-reads it whenever the host returns to the
 * foreground, so a grant made outside this screen takes effect without the consumer re-launching it. When
 * held, the live preview shows; otherwise the permission prompt hands the request to the host. The SDK
 * only *reads* the permission — it never requests it.
 */
@Composable
private fun ScanningContent(
    config: MrzScannerConfig,
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
        CameraPreview(onCancel = onCancel)
    } else {
        PermissionPrompt(onRequestPermission = config.onRequestPermission, onCancel = onCancel)
    }
}

/**
 * The live preview. The scanner ([CameraXMrzScanner]) owns the camera session and lifecycle; this
 * composable only draws. One scanner is tied to this composition with its opt-in preview armed, run while
 * the preview is shown, and closed when it leaves. Only the `SurfaceRequest` surface handle is hoisted
 * into Compose state — never an `ImageProxy`, frame, or decoded field (the module's memory-hygiene
 * commitment): no document PII is ever held in UI state.
 */
@Composable
private fun CameraPreview(onCancel: () -> Unit) {
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
    val surfaceRequest by scanner.surfaceRequest.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        surfaceRequest?.let { request ->
            CameraXViewfinder(
                surfaceRequest = request,
                modifier = Modifier.fillMaxSize().testTag(VIEWFINDER_TEST_TAG),
            )
        }
        // Hardcoded copy for the live-preview slice; moves to tessera_* string resources in the
        // resource-overlay slice (TES-46), as the scaffold's placeholder copy always intended to.
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "Point the camera at the document's data page")
            Button(onClick = onCancel) {
                Text(text = "Cancel")
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
        Text(text = "Camera permission is needed to scan the document")
        if (onRequestPermission != null) {
            Button(onClick = onRequestPermission) {
                Text(text = "Grant camera access")
            }
        }
        Button(onClick = onCancel) {
            Text(text = "Cancel")
        }
    }
}

private fun Context.hasCameraPermission(): Boolean = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
