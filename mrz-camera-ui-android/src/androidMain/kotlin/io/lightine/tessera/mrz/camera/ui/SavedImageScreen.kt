// @Composable functions are PascalCase by Compose convention — see the note in MrzScannerScreen.kt.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.SavedImageScanResult

// The saved-image (photo) reading screens (mockups 07c → 07 / 07b). A picked photo is read by the
// SavedImageMrzReader in single-read mode — the same one-best-read shape the live camera uses (TES-86): if
// OCR misread an ambiguous glyph, the review screen's check-digit observations surface it and the user
// rescans, the same safety net the camera has. (Headless consumers can still opt into the reader's tolerant
// candidate-enumeration mode directly — that capability is untouched; there is just no UI built on it here.)
//
// Saved-image reading is opt-in and off by default (ScanMethod.SAVED_IMAGE); enabling it in enabledMethods
// IS the consumer's acknowledgement, so the acknowledgement is constructed internally here — there is no
// separate user acknowledgement screen. The read happens entirely on-device; the photo is never uploaded.

// ---------------------------------------------------------------------------------------------------------
// Pure state mapping — mirrors routeDecode: Compose-free and camera-free so the flow's reaction to a
// SavedImageScanResult is host-unit-testable without a device or ML Kit.
// ---------------------------------------------------------------------------------------------------------

/**
 * What a [SavedImageScanResult] means for the flow, decided purely from the result — the saved-image sibling
 * of [DecodeRoute]. Extracted (Compose-free, camera-free) so the mapping is host-testable off-device.
 */
internal sealed interface SavedImageOutcome {
    /**
     * The primary read decoded an MRZ — route it exactly as a camera decode would, through [routeDecode] (so
     * a parse failure still shows the read-failed screen, a success goes to review, etc.).
     */
    data class SingleDecode(
        val decoded: MrzScanResult.Decoded,
    ) : SavedImageOutcome

    /** No MRZ was found in the photo (or the capture step failed) — the empty screen (mockup 07b). */
    data object Empty : SavedImageOutcome
}

/**
 * Maps a [SavedImageScanResult] to the flow outcome:
 *  * a [`Decoded`][MrzScanResult.Decoded] primary scan → [SavedImageOutcome.SingleDecode] (the caller runs
 *    [routeDecode] on it, so a parse failure / success routes identically to the camera path);
 *  * else ([`NoMrzFound`][MrzScanResult.NoMrzFound] / [`CaptureError`][MrzScanResult.CaptureError])
 *    → [SavedImageOutcome.Empty].
 *
 * The reader is used in single-read mode (`tolerant = false`, see `MrzScannerScreen.readPickedImage`), so
 * [`SavedImageScanResult.candidates`][SavedImageScanResult.candidates] is always empty here — this mapping
 * only ever sees the primary scan. Pure and Compose-free so it is unit-testable off-device.
 */
internal fun mapSavedImageResult(result: SavedImageScanResult): SavedImageOutcome =
    when (val scan = result.scan) {
        is MrzScanResult.Decoded -> SavedImageOutcome.SingleDecode(scan)
        else -> SavedImageOutcome.Empty
    }

// ---------------------------------------------------------------------------------------------------------
// Screens
// ---------------------------------------------------------------------------------------------------------

/**
 * The await-saved-image-pick prompt (TES-71). Shown when the saved-image method is the entry point (only
 * [`SAVED_IMAGE`][ScanMethod.SAVED_IMAGE] is enabled, or the user tapped the Photo tab) and the picker was
 * dismissed with no photo — so the screen is never left blank. Neutral copy (a title + a prompt to choose a
 * photo) and a single [onChoosePhoto] action that re-opens the picker; the on-device privacy fact is stated
 * once, globally, via the shared top bar's privacy notice (TES-88) rather than repeated here. Not a mockup
 * state of its own; the flow launches the picker on entering this state, and this content only shows if that
 * pick was cancelled.
 *
 * @param onChoosePhoto re-launch the system photo picker.
 */
@Composable
internal fun AwaitingSavedImagePickContent(onChoosePhoto: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .contentMaxWidth()
                .padding(24.dp)
                .testTag(AWAITING_SAVED_IMAGE_PICK_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.tessera_scanner_saved_image_prompt_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.tessera_scanner_saved_image_prompt_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(onClick = onChoosePhoto, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.tessera_scanner_saved_image_prompt_action))
        }
    }
}

/**
 * The "analyzing photo" screen (mockup 07c) — shown while the picked photo is being read on-device. A loading
 * indicator over a title and a neutral "Reading the document…" sub-text; the on-device privacy fact is stated
 * once, globally, via the shared top bar's privacy notice (TES-88) rather than repeated on this screen.
 *
 * **A11y (TES-47).** The screen appears via an auto-transition (entered the moment a photo is picked), so the
 * "Analyzing photo…" title is a **polite** live region — a screen reader announces it on arrival without the
 * user moving focus. The spinner is decorative (the title carries the meaning), so it is removed from the
 * semantics tree; its own animation already honours the platform animation scale (reduce-motion).
 */
@Composable
internal fun SavedImageAnalyzingContent() {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .contentMaxWidth()
                .padding(24.dp)
                .testTag(SAVED_IMAGE_ANALYZING_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.tessera_scanner_saved_image_analyzing_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        // Decorative spinner — the title states what is happening; cleared so it is not an unlabeled node.
        CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics {})
        Text(
            text = stringResource(R.string.tessera_scanner_saved_image_analyzing_reading),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * The "no MRZ found in this photo" screen (mockup 07b). Stated honestly — the MRZ *couldn't be located*, not
 * that the document is "invalid" — with a neutral hint about why (screens / low-contrast copies) and two
 * escapes: pick a different photo (primary) or type the details by hand (secondary, when [showManualEntry] →
 * [ScannerUiState.ManualRaw]). The on-device privacy fact is stated once, globally, via the shared top bar's
 * privacy notice (TES-88) rather than repeated on this screen.
 *
 * **A11y (TES-47).** This is a terminal outcome reached purely by auto-transition (a picked photo yielded no
 * MRZ) with no focus move, so the title is an **assertive** live region — a screen reader announces the
 * outcome on arrival, mirroring [ReadFailedContent] / [CameraUnavailableContent].
 *
 * @param onChooseDifferent re-launch the photo picker.
 * @param onManualEntry switch to manual raw-MRZ entry.
 * @param showManualEntry whether the manual-entry escape is offered — `false` when the consumer's
 *   `enabledMethods` excludes `MANUAL_ENTRY`. Defaults to `true`.
 */
@Composable
internal fun SavedImageEmptyContent(
    onChooseDifferent: () -> Unit,
    onManualEntry: () -> Unit,
    showManualEntry: Boolean = true,
) {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .contentMaxWidth()
                .padding(24.dp)
                .testTag(SAVED_IMAGE_EMPTY_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tessera_scanner_saved_image_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
        Text(
            text = stringResource(R.string.tessera_scanner_saved_image_empty_body),
            style = MaterialTheme.typography.bodyMedium,
        )

        Column(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        ) {
            Button(onClick = onChooseDifferent, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.tessera_scanner_saved_image_choose_different))
            }
            if (showManualEntry) {
                TextButton(onClick = onManualEntry, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.tessera_scanner_saved_image_manual))
                }
            }
        }
    }
}
