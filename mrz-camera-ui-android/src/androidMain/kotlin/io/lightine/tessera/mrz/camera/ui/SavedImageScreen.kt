// @Composable functions are PascalCase by Compose convention — see the note in MrzScannerScreen.kt.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import io.lightine.tessera.mrz.camera.MrzCandidate
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.RecognizedLine
import io.lightine.tessera.mrz.camera.RecognizedText
import io.lightine.tessera.mrz.camera.SavedImageScanResult
import io.lightine.tessera.mrz.camera.ScanQuality

// The saved-image (photo) reading screens (mockups 07c → 07 / 07b). A picked photo is read by the tolerant
// SavedImageMrzReader; because tolerant reading surfaces *every* parseable disambiguation of an ambiguous
// glyph without ranking or picking one (ADR-023 / Principle 1), the candidates screen lists them in the
// exact order returned — no "best", no highlight of a "match" — and every candidate is pickable regardless
// of its own check-digit verdict. The UI reports each candidate's honest per-candidate verdict (via the
// shared parseObservations) and lets the user choose; it never chooses for them.
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
     * Tolerant reading surfaced one or more candidate reconstructions — show them all for the user to choose
     * among (mockup 07). The UI never picks one (Principle 1 / ADR-023).
     */
    data class Candidates(
        val candidates: List<MrzCandidate>,
    ) : SavedImageOutcome

    /**
     * No candidates, but the primary read decoded an MRZ — route it exactly as a camera decode would, through
     * [routeDecode] (so a parse failure still shows the read-failed screen, a success goes to review, etc.).
     */
    data class SingleDecode(
        val decoded: MrzScanResult.Decoded,
    ) : SavedImageOutcome

    /** No MRZ was found in the photo (or the capture step failed) — the empty screen (mockup 07b). */
    data object Empty : SavedImageOutcome
}

/**
 * Maps a [SavedImageScanResult] to the flow outcome:
 *  * any [`candidates`][SavedImageScanResult.candidates] (tolerant reading found ambiguous glyphs to resolve)
 *    → [SavedImageOutcome.Candidates] — surfaced all together, unranked (the consumer chooses);
 *  * else a [`Decoded`][MrzScanResult.Decoded] primary scan → [SavedImageOutcome.SingleDecode] (the caller
 *    runs [routeDecode] on it, so a parse failure / success routes identically to the camera path);
 *  * else ([`NoMrzFound`][MrzScanResult.NoMrzFound] / [`CaptureError`][MrzScanResult.CaptureError])
 *    → [SavedImageOutcome.Empty].
 *
 * The candidates-first order is deliberate: when tolerant reading resolved a genuinely ambiguous glyph, the
 * honest thing is to show every reading and let the user pick — never silently collapse to the primary
 * decode (which is just one arbitrary resolution). Pure and Compose-free so it is unit-testable off-device.
 */
internal fun mapSavedImageResult(result: SavedImageScanResult): SavedImageOutcome =
    when {
        result.candidates.isNotEmpty() -> SavedImageOutcome.Candidates(result.candidates)
        result.scan is MrzScanResult.Decoded -> SavedImageOutcome.SingleDecode(result.scan as MrzScanResult.Decoded)
        else -> SavedImageOutcome.Empty
    }

/**
 * Wraps a chosen [MrzCandidate] into an [`MrzScanResult.Decoded`][MrzScanResult.Decoded] so the picked
 * candidate routes through [routeDecode] exactly as a camera or manual decode does — its own [parse] verdict
 * carried through unchanged (the SDK adds no judgement; the user already chose). The reconstructed
 * [`mrzLines`][MrzCandidate.mrzLines] become the recognized text verbatim (Principle 5); quality reflects
 * that the MRZ region was found with an unknown OCR confidence (a candidate carries no per-line score).
 * Pure and Compose-free so the pick→Decoded assembly is host-testable, mirroring [assembleManualDecoded].
 */
internal fun candidateDecoded(candidate: MrzCandidate): MrzScanResult.Decoded =
    MrzScanResult.Decoded(
        parse = candidate.parse,
        recognizedText = RecognizedText(candidate.mrzLines.map { RecognizedLine(it, null) }),
        quality =
            ScanQuality(
                mrzRegionFound = true,
                ocrConfidence = null,
                recognizedLineCount = candidate.mrzLines.size,
            ),
    )

// ---------------------------------------------------------------------------------------------------------
// Screens
// ---------------------------------------------------------------------------------------------------------

/**
 * The await-saved-image-pick prompt (TES-71). Shown when the saved-image method is the entry point (only
 * [`SAVED_IMAGE`][ScanMethod.SAVED_IMAGE] is enabled, or the user tapped the Photo tab) and the picker was
 * dismissed with no photo — so the screen is never left blank. Neutral copy ("Choose a photo" + on-device
 * privacy) and a single [onChoosePhoto] action that re-opens the picker. Not a mockup state of its own; the
 * flow launches the picker on entering this state, and this content only shows if that pick was cancelled.
 *
 * @param onChoosePhoto re-launch the system photo picker.
 */
@Composable
internal fun AwaitingSavedImagePickContent(onChoosePhoto: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(AWAITING_SAVED_IMAGE_PICK_TEST_TAG),
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
 * indicator over a title and neutral sub-text ("Reading the machine-readable zone" / "On-device — the photo
 * is not uploaded"), stating the privacy fact plainly rather than implying anything about the image.
 *
 * **A11y (TES-47).** The screen appears via an auto-transition (entered the moment a photo is picked), so the
 * "Analyzing photo…" title is a **polite** live region — a screen reader announces it on arrival without the
 * user moving focus. The spinner is decorative (the title carries the meaning), so it is removed from the
 * semantics tree; its own animation already honours the platform animation scale (reduce-motion).
 */
@Composable
internal fun SavedImageAnalyzingContent() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(SAVED_IMAGE_ANALYZING_TEST_TAG),
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
        Text(
            text = stringResource(R.string.tessera_scanner_saved_image_analyzing_on_device),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The saved-image candidates screen (mockup 07). Tolerant reading resolved a genuinely ambiguous glyph more
 * than one way; this lists **every** parseable reconstruction, **in the order returned** — never re-ranked,
 * never with a "best" or "match" highlight (Principle 1 / ADR-023). Each card shows the candidate's reading
 * monospace with the differing glyph(s) emphasised ([MonoLineHighlighted]), its own honest per-candidate
 * verdict (from [parseObservations] over the candidate's [`parse`][MrzCandidate.parse]), and a "Use this ›"
 * action — enabled for every candidate regardless of its verdict, because the user, not the SDK, decides
 * which reading is theirs (reader, not oracle). A secondary action re-picks a different photo.
 *
 * @param candidates the SDK's candidate set, rendered as-is (order preserved, none dropped or promoted).
 * @param onPick the chosen candidate — the caller wraps it ([candidateDecoded]) and routes it.
 * @param onChooseDifferent re-launch the photo picker for a different image.
 */
@Composable
internal fun SavedImageCandidatesContent(
    candidates: List<MrzCandidate>,
    onPick: (MrzCandidate) -> Unit,
    onChooseDifferent: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(SAVED_IMAGE_CANDIDATES_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tessera_scanner_saved_image_candidates_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.tessera_scanner_saved_image_candidates_intro, candidates.size),
            style = MaterialTheme.typography.bodyMedium,
        )

        // The candidate cards scroll; the "choose different photo" action stays pinned below and reachable.
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // In the order returned — the index maps a candidate to its own disambiguations. NEVER sorted.
            candidates.forEach { candidate -> CandidateCard(candidate = candidate, onPick = { onPick(candidate) }) }
        }

        OutlinedButton(onClick = onChooseDifferent, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.tessera_scanner_saved_image_choose_different))
        }
    }
}

/**
 * One candidate card (mockup 07): the reconstructed MRZ lines with the resolved (differing) columns
 * emphasised, the candidate's own honest verdict observations, and a "Use this ›" action. The differing
 * columns come from the candidate's [`disambiguations`][MrzCandidate.disambiguations] — grouped per line so
 * a multi-line MRZ highlights the right glyph on the right line. No card is styled as preferred.
 */
@Composable
private fun CandidateCard(
    candidate: MrzCandidate,
    onPick: () -> Unit,
) {
    // Which columns differ, per line — the positions this candidate resolved. Defensive against an
    // out-of-range index; MonoLineHighlighted also clamps to the line's own indices.
    val highlightsByLine: Map<Int, Set<Int>> =
        candidate.disambiguations
            .groupBy { it.lineIndex }
            .mapValues { (_, entries) -> entries.map { it.columnIndex }.toSet() }

    OutlinedCard(shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The reconstructed reading, each line monospace with its differing glyph(s) emphasised.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                candidate.mrzLines.forEachIndexed { lineIndex, line ->
                    MonoLineHighlighted(text = line, highlightColumns = highlightsByLine[lineIndex] ?: emptySet())
                }
            }

            // The candidate's own per-candidate verdict — the SAME honest reporting the review screen uses,
            // over this candidate's parse. Not a decision; just what its check digits say.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                parseObservations(candidate.parse).forEach { ReviewObservationRow(it) }
            }

            // Pickable regardless of the verdict — the user decides which reading is theirs (reader, not oracle).
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.tessera_scanner_saved_image_use_this))
            }
        }
    }
}

/**
 * The "no MRZ found in this photo" screen (mockup 07b). Stated honestly — the MRZ *couldn't be located*, not
 * that the document is "invalid" — with a neutral hint about why (screens / low-contrast copies), a privacy
 * note that the photo stays on-device, and two escapes: pick a different photo (primary) or type the details
 * by hand (secondary → [ScannerUiState.ManualRaw]).
 *
 * @param onChooseDifferent re-launch the photo picker.
 * @param onManualEntry switch to manual raw-MRZ entry.
 */
@Composable
internal fun SavedImageEmptyContent(
    onChooseDifferent: () -> Unit,
    onManualEntry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(SAVED_IMAGE_EMPTY_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tessera_scanner_saved_image_empty_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.tessera_scanner_saved_image_empty_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.tessera_scanner_saved_image_empty_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        ) {
            Button(onClick = onChooseDifferent, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.tessera_scanner_saved_image_choose_different))
            }
            TextButton(onClick = onManualEntry, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.tessera_scanner_saved_image_manual))
            }
        }
    }
}
