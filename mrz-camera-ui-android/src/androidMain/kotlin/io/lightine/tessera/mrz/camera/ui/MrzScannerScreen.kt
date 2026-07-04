// @Composable functions are PascalCase by Compose convention, which ktlint's standard function-naming
// rule flags. Suppressed at file scope (the idiomatic per-Compose-file exemption) rather than repo-wide:
// Spotless's ktlint step does not reliably pass the editorconfig `ignore_when_annotated_with` value
// through, and forcing it via editorConfigOverride perturbs the code-style baseline for other modules.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

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
 * **Scaffold status:** this slice renders the module's theme and a minimal placeholder with a working
 * cancel path. The live camera preview, review, and manual-entry screens land in the following 0.5.0
 * slices; the signature here (one config in, one result out) is the shape that freezes at the 0.5.0 tag.
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
        ScannerPlaceholder(onCancel = { onResult(MrzScannerResult.Cancelled) })
    }
}

/**
 * A stable semantics anchor for the scaffold's root, used by the host-side Compose test to assert the
 * screen renders. Not user-facing, so it is unaffected by later string-resource work.
 */
internal const val SCANNER_ROOT_TEST_TAG: String = "tessera-mrz-scanner-root"

@Composable
private fun ScannerPlaceholder(onCancel: () -> Unit) {
    // Placeholder chrome for the scaffold slice — real, minimal, and testable. It renders inside the
    // module theme and wires the cancel affordance to the result callback so the whole path (compose →
    // theme → callback) is exercised end to end. Copy is hardcoded here and moves to tessera_* string
    // resources in the resource-overlay slice.
    Surface(modifier = Modifier.fillMaxSize().testTag(SCANNER_ROOT_TEST_TAG)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "Tessera MRZ scanner")
            Button(onClick = onCancel) {
                Text(text = "Cancel")
            }
        }
    }
}
