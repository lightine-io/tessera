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

/**
 * The guide window ([MrzGuideOverlay]) plus a 12dp margin above and below: the guide itself is [GuideBandHeight],
 * so the limit is [GuideBandHeight] + 24dp. Below it, the guide cannot fit with those margins and the notice is
 * shown instead of the camera.
 *
 * From this limit up to but not including 224dp, the torch button and the "What's the MRZ?" link can overlap the
 * guide box. This is accepted until the layout is redesigned. Reading still works at these heights because the text
 * reader's area covers the guide box at every height.
 */
internal val MinScanAreaHeight: Dp = GuideBandHeight + 24.dp

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
