// @Composable functions are PascalCase by Compose convention — see the note in MrzScannerScreen.kt.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// TES-342: a scanner window too short to scan. In split screen dragged to its smallest height, the live preview
// shrank to a strip: the torch was clipped, the "What's the MRZ?" link and the footer were drawn over the guide
// window, and an MRZ held in the window was not read. Below the height the guide window and its controls need,
// the camera area shows a plain notice instead of the preview, and the camera does not run. When the window
// grows back, the preview returns by itself. Not a layout rework: the preview's own layout is unchanged.

/** Semantics anchor for the "window too small to scan" notice. Not user-facing. */
internal const val WINDOW_TOO_SMALL_TEST_TAG: String = "tessera-mrz-window-too-small"

/** The minimum touch target Material 3 gives the torch toggle ([TorchButton], `minimumInteractiveComponentSize`). */
private val MinTouchTarget: Dp = 48.dp

/**
 * The smallest camera-area height (the space under the top bar and method switcher, above the footer) in which
 * the live preview is usable. Derived from the preview's own layout, not tuned:
 *
 * - The guide window ([MrzGuideOverlay]) is [GuideBandHeight] (96dp) tall and vertically centred, so the space
 *   above it equals the space below it: (height - 96dp) / 2.
 * - Above it sits the torch toggle: [TorchEdgePadding] (16dp) from the top plus its 48dp touch target = 64dp.
 * - Below it the preview reserves [GuidanceBottomReserved] (64dp) for the pinned "What's the MRZ?" link.
 * - Each side therefore needs max(64dp, 64dp) = 64dp, so the height is 96dp + 2 × 64dp = 224dp.
 *
 * Below this height the torch overlaps the guide window and the "What's the MRZ?" link is drawn over it. The
 * framing hint under the window is not counted: its region already scrolls inside the space left between the
 * window and the link, so it never overlaps anything. Width is not gated: the guide window is the area's width
 * minus fixed margins and the controls sit beside or below it, so a narrow window does not overlap them.
 */
internal val MinScanAreaHeight: Dp = GuideBandHeight + maxOf(TorchEdgePadding + MinTouchTarget, GuidanceBottomReserved) * 2

/** Whether a camera area of [height] is too short for the live preview ([MinScanAreaHeight]). Pure, so host-testable. */
internal fun scanAreaTooSmall(height: Dp): Boolean = height < MinScanAreaHeight

/**
 * Shows [camera] when the available height is at least [MinScanAreaHeight], and the [WindowTooSmallContent] notice
 * otherwise. The notice REPLACES [camera], so whatever [camera] holds leaves composition: the live preview's scanner
 * is closed and the camera unbound while the notice shows, and a fresh one starts when the area is tall enough
 * again. An unbounded height (a scrolling parent) never counts as too small.
 *
 * `internal` so the host test drives it with a fake camera: the real preview needs CameraX.
 */
@Composable
internal fun ScanAreaSizeGate(
    onManualEntry: () -> Unit,
    showManualEntry: Boolean,
    camera: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (scanAreaTooSmall(maxHeight)) {
            WindowTooSmallContent(onManualEntry = onManualEntry, showManualEntry = showManualEntry)
        } else {
            camera()
        }
    }
}

/**
 * The notice shown in place of the live preview when the window is too short to scan (TES-342): a title, a body
 * asking for a larger window, and the manual-entry action when [showManualEntry]. Close stays in the top bar, and
 * the method switcher stays above. Scrolls, because it only appears when there is little room.
 *
 * **A11y.** The notice appears on a resize, not on a tap, so the title is a **polite** live region: a screen reader
 * announces it without the user moving focus, the same as the other recoverable camera notices.
 */
@Composable
internal fun WindowTooSmallContent(
    onManualEntry: () -> Unit,
    showManualEntry: Boolean = true,
) {
    Box(
        modifier = Modifier.fillMaxSize().testTag(WINDOW_TOO_SMALL_TEST_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .contentMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.tessera_scanner_window_too_small_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.liveRegionOf(LiveRegionMode.Polite),
            )
            Text(
                text = stringResource(R.string.tessera_scanner_window_too_small_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (showManualEntry) {
                Button(onClick = onManualEntry, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.tessera_scanner_camera_manual))
                }
            }
        }
    }
}
