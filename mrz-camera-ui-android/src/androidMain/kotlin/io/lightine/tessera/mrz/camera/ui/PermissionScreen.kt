// @Composable functions are PascalCase by Compose convention — see the note in MrzScannerScreen.kt.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// The adaptive camera-permission screen (mockups 04 / 04b). ONE screen whose primary action tracks the
// permission status:
//
//  * Grant mode (04, NEEDS_GRANT): the permission is not held and a request can still succeed. The primary
//    action hands the request to the host (config.onRequestPermission) — the SDK NEVER requests a permission
//    itself (scope: "The SDK does not request runtime permissions itself. Consumers handle permission
//    flows."). If the host supplied no handler, no dead Grant button is drawn — only the rationale and the
//    manual-entry escape (as the earlier single prompt did).
//  * Settings mode (04b, PERMANENTLY_DENIED): the permission is denied with "don't ask again", so a request
//    would show no dialog and return denied immediately. The primary action opens this app's OS settings
//    page by Intent — that is NAVIGATION, not a permission request, so it stays within the boundary and the
//    UI owns it (it is always available in this mode).
//
// Both modes share the secondary "Enter details manually" escape. The screen only ever READS the permission
// status (checkSelfPermission + shouldShowRequestPermissionRationale, both read-only) and NAVIGATES (the
// Settings intent) — it never calls requestPermissions / ActivityResultContracts.RequestPermission. The
// "ask" stays with the host. The mode decision is the pure, host-testable permissionScreenState below.

/** Semantics anchor for the permission screen in Grant mode (mockup 04). Not user-facing. */
internal const val PERMISSION_GRANT_TEST_TAG: String = "tessera-mrz-permission-grant"

/** Semantics anchor for the permission screen in Settings mode (mockup 04b). Not user-facing. */
internal const val PERMISSION_DENIED_TEST_TAG: String = "tessera-mrz-permission-denied"

/**
 * Which face of the adaptive permission screen to show — the single decision the gate ([CameraCapture])
 * dispatches on. Derived purely by [permissionScreenState] from the three read-only signals the gate holds,
 * so the whole decision is host-unit-testable without a device or an Activity.
 */
internal enum class PermissionScreenState {
    /** The `CAMERA` permission is held — the gate shows the live preview, not this screen. */
    GRANTED,

    /** The permission is not held and a request can still succeed (never asked, or denied once) — mockup 04. */
    NEEDS_GRANT,

    /** The permission is permanently denied ("don't ask again") — only the OS settings can grant it (mockup 04b). */
    PERMANENTLY_DENIED,
}

/**
 * The adaptive permission decision, decided purely from the three read-only signals the gate holds:
 *  * [granted] — `checkSelfPermission(CAMERA) == GRANTED` (already held → [PermissionScreenState.GRANTED]);
 *  * [hasAsked] — whether the host has been handed a request in this flow (the user tapped Grant at least
 *    once), tracked because [showRationale] alone cannot disambiguate never-asked from permanently-denied;
 *  * [showRationale] — `shouldShowRequestPermissionRationale(activity, CAMERA)`, which returns `false` BOTH
 *    when the permission was never requested AND when it is permanently denied, and `true` only after a
 *    single denial. Verified against the current Android docs (2026-07-06).
 *
 * So once the host has been asked and the platform still won't show a rationale, the system will no longer
 * show the request dialog — that is [PermissionScreenState.PERMANENTLY_DENIED] (where "Grant" would be dead,
 * so the screen offers "Open Settings" instead). Otherwise (never asked, or denied once with the rationale
 * still available) a request can still succeed → [PermissionScreenState.NEEDS_GRANT].
 *
 * Pure and Compose-free so every combination is host-unit-testable off-device.
 */
internal fun permissionScreenState(
    granted: Boolean,
    hasAsked: Boolean,
    showRationale: Boolean,
): PermissionScreenState =
    when {
        granted -> PermissionScreenState.GRANTED

        // Asked at least once and the system won't show a rationale → the dialog won't appear again.
        hasAsked && !showRationale -> PermissionScreenState.PERMANENTLY_DENIED

        // Never asked, or denied once (rationale true) — a request can still succeed.
        else -> PermissionScreenState.NEEDS_GRANT
    }

/**
 * The adaptive camera-permission screen (mockups 04 / 04b). Renders one of two faces from [state]:
 *
 *  * [PermissionScreenState.NEEDS_GRANT] → mockup 04: title "Camera permission needed", the on-device
 *    privacy body, a primary "Grant access" that fires [onGrant] (which hands the request to the host) — but
 *    ONLY when [hasRequestHandler] is true; if the host supplied no `onRequestPermission`, no dead Grant
 *    button is drawn, leaving just the rationale and the manual-entry escape.
 *  * [PermissionScreenState.PERMANENTLY_DENIED] → mockup 04b: title "Camera access is off", the Settings +
 *    privacy body, a primary "Open Settings" that fires [onOpenSettings] (the UI owns this navigation; it is
 *    always available in this mode).
 *
 * Both faces share the secondary "Enter details manually" ([onManualEntry], when [showManualEntry]).
 * [PermissionScreenState.GRANTED] is never passed here (the gate shows the preview instead); guarded
 * defensively as a no-op.
 *
 * `internal` (not `private`) so the two faces and their actions are host-tested through this entry point
 * directly (the same composable the gate dispatches), per the testing-layers rule. The screen only reads and
 * navigates — it never requests a permission (scope permission boundary).
 *
 * @param showManualEntry whether the manual-entry escape is offered — `false` when the consumer's
 *   `enabledMethods` excludes `MANUAL_ENTRY`, so a camera-only config never dangles an escape into a screen
 *   the user cannot reach any other way. Defaults to `true`.
 */
@Composable
internal fun PermissionContent(
    state: PermissionScreenState,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    onManualEntry: () -> Unit,
    hasRequestHandler: Boolean,
    showManualEntry: Boolean = true,
) {
    when (state) {
        PermissionScreenState.NEEDS_GRANT -> {
            PermissionScaffold(
                testTag = PERMISSION_GRANT_TEST_TAG,
                title = stringResource(R.string.tessera_scanner_permission_grant_title),
                body = stringResource(R.string.tessera_scanner_permission_grant_body),
                // Grant hands the request to the host; without a handler there is nothing to call, so no
                // dead button is drawn (only the rationale + the manual-entry escape remain).
                primaryLabel = if (hasRequestHandler) stringResource(R.string.tessera_scanner_permission_grant_action) else null,
                onPrimary = onGrant,
                onManualEntry = onManualEntry,
                showManualEntry = showManualEntry,
            )
        }

        PermissionScreenState.PERMANENTLY_DENIED -> {
            PermissionScaffold(
                testTag = PERMISSION_DENIED_TEST_TAG,
                title = stringResource(R.string.tessera_scanner_permission_denied_title),
                body = stringResource(R.string.tessera_scanner_permission_denied_body),
                // Open Settings is the UI's own navigation — always available in this mode.
                primaryLabel = stringResource(R.string.tessera_scanner_permission_open_settings),
                onPrimary = onOpenSettings,
                onManualEntry = onManualEntry,
                showManualEntry = showManualEntry,
            )
        }

        // The gate never dispatches GRANTED here (it shows the live preview instead); defensively a no-op.
        PermissionScreenState.GRANTED -> {}
    }
}

/**
 * The shared layout of both permission faces (mockups 04 / 04b): a centred icon-free title + body, a primary
 * action pinned at the bottom (omitted when [primaryLabel] is null — the no-handler Grant case), and the
 * secondary "Enter details manually" escape beneath it. The two faces differ only in their [testTag], copy,
 * and which callback the primary action fires — everything structural is shared here.
 */
@Composable
private fun PermissionScaffold(
    testTag: String,
    title: String,
    body: String,
    primaryLabel: String?,
    onPrimary: () -> Unit,
    onManualEntry: () -> Unit,
    showManualEntry: Boolean,
) {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .contentMaxWidth()
                .padding(24.dp)
                .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Title + body sit centred in the available space; the actions stay pinned below.
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        if (primaryLabel != null) {
            Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) {
                Text(text = primaryLabel)
            }
        }
        if (showManualEntry) {
            OutlinedButton(onClick = onManualEntry, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.tessera_scanner_camera_manual))
            }
        }
    }
}
