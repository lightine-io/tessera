// @Composable functions are PascalCase by Compose convention — see the note in MrzScannerScreen.kt.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// The camera-status screens (mockups 05 / 05b) — the two ways the live camera cannot deliver a preview:
//
//  * CameraInUse (05, recoverable): another app holds the camera. This notice REPLACES the live preview, so
//    the scanner is torn down while it shows — CameraX does not auto-retry after that error, so there is no
//    "wait and it reconnects itself" path. Recovery happens only when the app returns to the foreground: the
//    flow's ON_RESUME observer re-mounts a fresh preview and tries again (see ScannerFlow). This screen
//    offers NO retry button (returning to the app IS the retry) — only the manual-entry escape, for a user
//    who would rather not wait.
//  * CameraUnavailable (05b, terminal): the camera cannot be started at all. No auto-recovery, no retry —
//    the only forward path is manual entry.
//
// Both are honest statements of a capture condition, never a verdict about a document (Principle 1). The
// meaning lives in the text, not in colour or motion: the pulse beside the status label is decorative (an
// a11y-neutral affordance), and the heading + body carry the state on their own.

/** Semantics anchor for the camera-in-use (recoverable) screen (mockup 05). Not user-facing. */
internal const val CAMERA_IN_USE_TEST_TAG: String = "tessera-mrz-camera-in-use"

/** Semantics anchor for the camera-unavailable (terminal) screen (mockup 05b). Not user-facing. */
internal const val CAMERA_UNAVAILABLE_TEST_TAG: String = "tessera-mrz-camera-unavailable"

/**
 * The camera-in-use screen (mockup 05). Shown when another app holds the camera. This is **recoverable**, but
 * not automatically: the notice replaces the live preview, so the scanner is torn down while it shows, and
 * CameraX does not retry the camera open on its own after this error. Recovery happens the next time the app
 * returns to the foreground — the flow's `ON_RESUME` observer re-mounts a fresh preview and tries again (see
 * [ScannerFlow]). There is therefore **no retry button** here (coming back to the screen already is the
 * retry) — only [onManualEntry] (when [showManualEntry]), the escape into manual entry for a user who would
 * rather not wait. The status indicator states this in words; its pulse is decorative and never the sole
 * carrier of meaning (non-colour / non-motion a11y).
 *
 * @param showManualEntry whether the manual-entry action is offered — `false` when the consumer's
 *   `enabledMethods` excludes `MANUAL_ENTRY`. Defaults to `true`.
 */
@Composable
internal fun CameraInUseContent(
    onManualEntry: () -> Unit,
    showManualEntry: Boolean = true,
) {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .contentMaxWidth()
                .padding(24.dp)
                .testTag(CAMERA_IN_USE_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Heading + body + the reconnecting indicator sit centred in the available space; the single action
        // stays pinned below.
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.tessera_scanner_camera_in_use_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.tessera_scanner_camera_in_use_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            ReconnectingIndicator()
        }

        if (showManualEntry) {
            Button(onClick = onManualEntry, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.tessera_scanner_camera_manual))
            }
        }
    }
}

/**
 * The camera-unavailable screen (mockup 05b). Shown when the camera cannot be started for a non-recoverable
 * reason. This is **terminal**: there is no auto-recovery and no retry — the only forward path is
 * [onManualEntry] (when [showManualEntry]), typing the details by hand.
 *
 * @param showManualEntry whether the manual-entry action is offered — `false` when the consumer's
 *   `enabledMethods` excludes `MANUAL_ENTRY`. Defaults to `true`.
 */
@Composable
internal fun CameraUnavailableContent(
    onManualEntry: () -> Unit,
    showManualEntry: Boolean = true,
) {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .contentMaxWidth()
                .padding(24.dp)
                .testTag(CAMERA_UNAVAILABLE_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.tessera_scanner_camera_unavailable_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                // Terminal, auto-transition landing (no focus move brought the user here) — assertive so a
                // screen reader announces the outcome on arrival, matching ReadFailed / SavedImageEmpty.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Text(
                text = stringResource(R.string.tessera_scanner_camera_unavailable_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        if (showManualEntry) {
            Button(onClick = onManualEntry, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.tessera_scanner_camera_manual))
            }
        }
    }
}

/**
 * The status indicator on the camera-in-use screen: a pulsing dot beside a short status label
 * (`tessera_scanner_camera_reconnecting`). The label carries the meaning; the pulse is purely decorative (it
 * conveys nothing a screen reader or a reduced-motion user would miss — the status is stated in the text). The
 * dot is the literal bullet "●" whose alpha animates, so no drawing API or icon dependency is introduced.
 *
 * **A11y (TES-47).**
 *  * *Motion is never the sole signal.* The pulse is gated on [animationsEnabled] — with the user's
 *    reduce-motion / "Remove animations" setting on, the dot renders static (full alpha) and only the text
 *    conveys the status. Meaning survives with motion off.
 *  * *The dot is not announced.* The bare bullet "●" is decorative, so it is removed from the semantics tree
 *    ([clearAndSetSemantics] with an empty block) — a screen reader would otherwise read a meaningless glyph.
 *  * *The status is announced on arrival.* The camera-in-use screen appears via an auto-transition (the flow
 *    reducer flips to it when another app grabs the camera), so the status label is a **polite** [liveRegion]:
 *    a screen reader speaks it when it appears without the user having to move focus to it.
 */
@Composable
private fun ReconnectingIndicator() {
    val animate = animationsEnabled()
    val transition = rememberInfiniteTransition(label = "reconnecting")
    val animatedAlpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1200),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "reconnecting-dot-alpha",
    )
    // Reduce-motion: keep the dot static (full alpha) so the pulse never runs; the text still carries meaning.
    val alpha = if (animate) animatedAlpha else 1f
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "●",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            // Decorative — cleared from the semantics tree so a screen reader does not read the bare glyph.
            modifier = Modifier.alpha(alpha).clearAndSetSemantics {},
        )
        Text(
            text = stringResource(R.string.tessera_scanner_camera_reconnecting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            // Polite live region: the camera-in-use screen arrives via an auto-transition, so the recoverable
            // status is announced on appearance without the user needing to find it.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
