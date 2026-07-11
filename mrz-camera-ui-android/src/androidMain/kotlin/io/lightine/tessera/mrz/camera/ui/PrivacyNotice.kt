// @Composable functions are PascalCase by Compose convention — see the note in MrzScannerScreen.kt.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

// The privacy / transparency notice (TES-88). One coherent, always-reachable explanation of what the scanner
// does with the document: it reads on-device, keeps nothing, and hands the result back to the host app, which
// then decides what happens. Surfaced as a compact "ⓘ Privacy" affordance in the shared top bar
// (ScannerScaffold), so it rides along on every screen without being a banner that nags on each one — the user
// taps it when they want to know, and a dialog states the full plain-language explanation.
//
// Reader, not oracle (Principle 1): this only *describes* the SDK's own data handling; it makes no claim
// about the document. It is honest about the boundary — Tessera is a pass-through reader, the host is the
// controller of whatever it returns.

/** Semantics anchor for the top-bar "Privacy" affordance that opens the notice. Not user-facing. */
internal const val PRIVACY_ACTION_TEST_TAG: String = "tessera-mrz-privacy-action"

/** Semantics anchor for the privacy notice dialog. Not user-facing. */
internal const val PRIVACY_DIALOG_TEST_TAG: String = "tessera-mrz-privacy-dialog"

/**
 * The top-bar privacy affordance: a compact "ⓘ Privacy" button that opens the [PrivacyNoticeDialog]. Owns the
 * dialog's open/closed state locally (it is a self-contained disclosure — no flow state involved), so the
 * scaffold can drop it into the [androidx.compose.material3.TopAppBar] `actions` slot with no plumbing.
 *
 * The ⓘ glyph is a plain text character (like the scaffold's ✕) so a single decorative mark does not pull in
 * the material-icons dependency; it is marked decorative for accessibility, and the visible "Privacy" word
 * carries the control's spoken name.
 */
@Composable
internal fun PrivacyNoticeAction(modifier: Modifier = Modifier) {
    var showDialog by remember { mutableStateOf(false) }

    TextButton(
        onClick = { showDialog = true },
        modifier = modifier.testTag(PRIVACY_ACTION_TEST_TAG),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Decorative glyph: cleared from semantics so the screen reader announces only "Privacy", not the
            // circled-i character. Same approach as the scaffold's ✕ (a text glyph, not a Material icon).
            Text(text = "ⓘ", modifier = Modifier.clearAndSetSemantics {})
            Text(text = stringResource(R.string.tessera_scanner_privacy_action))
        }
    }

    if (showDialog) {
        PrivacyNoticeDialog(onDismiss = { showDialog = false })
    }
}

/**
 * The privacy notice itself: a plain-language dialog stating that the read happens on-device, nothing is
 * uploaded or stored, and the result is handed back to the host app. A single "Got it" dismiss — it informs,
 * it asks nothing. Split from [PrivacyNoticeAction] so it can be host-tested directly.
 */
@Composable
internal fun PrivacyNoticeDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(PRIVACY_DIALOG_TEST_TAG),
        title = { Text(text = stringResource(R.string.tessera_scanner_privacy_dialog_title)) },
        text = {
            Text(
                text = stringResource(R.string.tessera_scanner_privacy_dialog_body),
                style = MaterialTheme.typography.bodyMedium,
                // Scrollable so the body is readable at large accessibility font sizes on short / landscape
                // screens (Material3's AlertDialog does not auto-scroll its text slot) — mirrors the explainer.
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.tessera_scanner_privacy_dialog_dismiss))
            }
        },
    )
}
