// @Composable functions are PascalCase by Compose convention — see the note in MrzScannerScreen.kt.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.lightine.tessera.mrz.camera.ConsensusVerdict
import io.lightine.tessera.mrz.camera.MrzDecodeConsensus
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.vocabulary.DocumentCategory

// TES-103: the app's intake rule (MrzScannerConfig.acceptedDocumentTypes). A reading whose category is not in
// the accepted set does not go to review and is not returned. The screen states two facts - what kind was read
// and what kinds the app accepts - and lets the person go on. The SDK does not judge the document: the copy
// never says invalid, wrong or rejected. A reading whose kind the SDK cannot tell
// (DocumentType.broadCategory is null) is refused the same way when the app limits the kinds it accepts; with
// the default (every category) it is not filtered and goes on as before.

/** Semantics anchor for the not-accepted notice over the live preview. Not user-facing. */
internal const val NOT_ACCEPTED_HINT_TEST_TAG: String = "tessera-mrz-not-accepted-hint"

/** Semantics anchor for the not-accepted screen after a picked photo. Not user-facing. */
internal const val SAVED_IMAGE_NOT_ACCEPTED_TEST_TAG: String = "tessera-mrz-saved-image-not-accepted"

/** Semantics anchor for the not-accepted note on the manual-entry screen. Not user-facing. */
internal const val MANUAL_NOT_ACCEPTED_TEST_TAG: String = "tessera-mrz-manual-not-accepted"

/**
 * How long the live notice stays after the last frame that read a not-accepted document, so it does not
 * strobe between frames but drops once that document has left the view.
 */
internal const val NOT_ACCEPTED_NOTICE_LINGER_MS: Long = 3_000L

// ---------------------------------------------------------------------------------------------------------
// Pure decisions
// ---------------------------------------------------------------------------------------------------------

/**
 * A reading refused under the app's intake rule ([MrzScannerConfig.acceptedDocumentTypes]): either the rule
 * names the kind the app does not take, or the reading's kind could not be told at all. Carries no document
 * value - only which of the two refusals happened, so the notice can state it. `null` (wherever this type
 * appears) always means "no refusal": the reading is accepted and goes on.
 *
 * TES-341 widened the model from a bare `DocumentCategory?`: the camera can misread the first letter of a
 * document code as a digit, and such a reading has no category, which used to read as "no refusal" even
 * under a passports-only rule.
 */
internal sealed interface NotAcceptedKind {
    /** The reading is of [category], a kind the accepted set does not include. */
    data class OfCategory(
        val category: DocumentCategory,
    ) : NotAcceptedKind

    /**
     * The SDK could not tell what kind of document this is (`broadCategory` is `null`), and the app limits
     * the kinds it accepts, so the reading is refused exactly like a kind outside the set. Never produced
     * when every category is accepted (the default): such a reading goes on as before.
     */
    data object Unknown : NotAcceptedKind
}

/**
 * The refusal of [decoded] under [acceptedDocumentTypes], otherwise `null`. `null` also when the reading did
 * not parse: that routes to the read-failed path instead. A category the set does not include refuses the
 * reading outright. A reading whose kind cannot be told (`broadCategory` is `null`) is refused only when the
 * app limits the kinds it takes; with the default (every category) it is not filtered - the SDK filters only
 * on a kind it can name.
 */
internal fun notAcceptedKind(
    decoded: MrzScanResult.Decoded,
    acceptedDocumentTypes: Set<DocumentCategory>,
): NotAcceptedKind? {
    val document =
        when (val parse = decoded.parse) {
            is ParseResult.Success -> parse.document
            is ParseResult.PartialSuccess -> parse.document
            is ParseResult.Failure -> return null
        }
    val category = document.commonFields.documentType.broadCategory
    if (category != null) {
        return category.takeUnless { it in acceptedDocumentTypes }?.let { NotAcceptedKind.OfCategory(it) }
    }
    // The kind could not be told: refused only when the app limits what it takes. With the default (every
    // category) the reading goes on as before - there is no kind to compare against the set.
    return if (acceptedDocumentTypes == DocumentCategory.entries.toSet()) null else NotAcceptedKind.Unknown
}

/** What one parseable live-camera frame means for the flow, decided by [stepLiveDecode]. */
internal sealed interface LiveDecodeStep {
    /** Nothing to do: the session already routed a reading, or the frame did not parse. */
    data object Ignore : LiveDecodeStep

    /** The frames agreed on an accepted reading: latch the session and route it. */
    data class Route(
        val decoded: MrzScanResult.Decoded,
    ) : LiveDecodeStep

    /** A reading the app accepts (including one whose kind could not be told, under the default set) is gathering agreement: show the "hold steady" cue. */
    data object Gathering : LiveDecodeStep

    /** A not-accepted frame, agreement not reached yet: keep any notice alive, show no cue. */
    data class NotAcceptedGathering(
        val read: NotAcceptedKind,
    ) : LiveDecodeStep

    /**
     * The frames agreed on a reading the app does not accept. The consensus has been reset so a later document
     * can still be agreed on; the session is NOT latched.
     */
    data class NotAccepted(
        val read: NotAcceptedKind,
    ) : LiveDecodeStep
}

/**
 * Offers one live frame to [consensus] and decides what it means under [acceptedDocumentTypes]. A frame that
 * did not parse, or any frame after the session routed ([alreadyRouted]), is ignored without touching the
 * consensus, exactly as before TES-103. A confirmed reading the app does not accept resets [consensus] and
 * returns [LiveDecodeStep.NotAccepted]: it never latches the session and never counts as the confirmed
 * reading. With every category accepted (the default) the result is [LiveDecodeStep.Route] or
 * [LiveDecodeStep.Gathering], the same as before - a reading whose kind could not be told is refused only
 * when the app limits the kinds it takes.
 */
internal fun stepLiveDecode(
    decoded: MrzScanResult.Decoded,
    alreadyRouted: Boolean,
    consensus: MrzDecodeConsensus,
    acceptedDocumentTypes: Set<DocumentCategory>,
): LiveDecodeStep {
    if (alreadyRouted || decoded.parse is ParseResult.Failure) return LiveDecodeStep.Ignore
    return when (val verdict = consensus.offer(decoded)) {
        is ConsensusVerdict.Confirmed -> {
            val read = notAcceptedKind(verdict.decoded, acceptedDocumentTypes)
            if (read == null) {
                LiveDecodeStep.Route(verdict.decoded)
            } else {
                consensus.reset()
                LiveDecodeStep.NotAccepted(read)
            }
        }

        is ConsensusVerdict.Gathering -> {
            val read = notAcceptedKind(decoded, acceptedDocumentTypes)
            if (read == null) LiveDecodeStep.Gathering else LiveDecodeStep.NotAcceptedGathering(read)
        }
    }
}

/**
 * The live state after the frames agreed on a not-accepted reading: a [ScannerUiState.Scanning] shows the
 * notice for [read] (and drops the gathering cue, which described that reading); any other state is unchanged.
 */
internal fun showNotAcceptedNotice(
    current: ScannerUiState,
    read: NotAcceptedKind,
): ScannerUiState = if (current is ScannerUiState.Scanning) current.copy(notAccepted = read, gathering = false) else current

/**
 * Where a reading the app does not accept lands, per reading method: the camera keeps its live preview with the
 * notice over it ([current] when it is already scanning, so its struggling flag survives), a photo gets its own
 * screen with "Choose different photo", and manual entry stays on the entry screen with [manualDraft] kept and
 * an inline note.
 */
internal fun notAcceptedStateFor(
    source: ScanMethod,
    read: NotAcceptedKind,
    current: ScannerUiState,
    manualDraft: String,
): ScannerUiState =
    when (source) {
        ScanMethod.CAMERA -> showNotAcceptedNotice(current as? ScannerUiState.Scanning ?: ScannerUiState.Scanning(), read)
        ScanMethod.SAVED_IMAGE -> ScannerUiState.SavedImageNotAccepted(read)
        ScanMethod.MANUAL_ENTRY -> ScannerUiState.ManualRaw(text = manualDraft, notAccepted = read)
    }

// ---------------------------------------------------------------------------------------------------------
// Copy
// ---------------------------------------------------------------------------------------------------------

/** The notice title for a refused reading of kind [read]: "This looks like an ID card", or "We could not tell what kind of document this is" when the kind could not be told. */
@StringRes
internal fun notAcceptedTitleRes(read: NotAcceptedKind): Int =
    when (read) {
        is NotAcceptedKind.OfCategory -> {
            when (read.category) {
                DocumentCategory.PASSPORT -> R.string.tessera_scanner_not_accepted_title_passport
                DocumentCategory.IDENTITY_CARD -> R.string.tessera_scanner_not_accepted_title_identity_card
                DocumentCategory.RESIDENCE_PERMIT -> R.string.tessera_scanner_not_accepted_title_residence_permit
                DocumentCategory.VISA -> R.string.tessera_scanner_not_accepted_title_visa
                DocumentCategory.OTHER -> R.string.tessera_scanner_not_accepted_title_other
            }
        }

        NotAcceptedKind.Unknown -> {
            R.string.tessera_scanner_not_accepted_title_unknown
        }
    }

/** The category name used in the accepted list, for example "ID card" (the same keys the review screen uses). */
@StringRes
internal fun categoryNameRes(category: DocumentCategory): Int =
    when (category) {
        DocumentCategory.PASSPORT -> R.string.tessera_scanner_category_passport
        DocumentCategory.IDENTITY_CARD -> R.string.tessera_scanner_category_identity_card
        DocumentCategory.RESIDENCE_PERMIT -> R.string.tessera_scanner_category_residence_permit
        DocumentCategory.VISA -> R.string.tessera_scanner_category_visa
        DocumentCategory.OTHER -> R.string.tessera_scanner_category_other
    }

/** "This app accepts: passport, visa." - the accepted categories in [DocumentCategory] order. */
@Composable
private fun acceptedBody(acceptedDocumentTypes: Set<DocumentCategory>): String {
    val separator = stringResource(R.string.tessera_scanner_not_accepted_separator)
    val names =
        DocumentCategory.entries
            .filter { it in acceptedDocumentTypes }
            .map { stringResource(categoryNameRes(it)) }
    return stringResource(R.string.tessera_scanner_not_accepted_body, names.joinToString(separator))
}

// ---------------------------------------------------------------------------------------------------------
// Screens
// ---------------------------------------------------------------------------------------------------------

/**
 * The notice over the live preview after the frames agreed on a reading of kind [read] that the app does not
 * accept - a category outside the set, or a kind that could not be told. Shown in the guide overlay's guidance
 * region, white over the dark scrim like the other cues. The camera keeps scanning under it. A polite live
 * region, like the "hold steady" and "still looking" cues: it appears on its own, so a screen reader announces
 * both lines without a focus move.
 *
 * `internal`: the live preview needs a real camera, so this is host-tested directly (like [StrugglingHint]).
 */
@Composable
internal fun NotAcceptedHint(
    read: NotAcceptedKind,
    acceptedDocumentTypes: Set<DocumentCategory>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .padding(24.dp)
                .testTag(NOT_ACCEPTED_HINT_TEST_TAG)
                .semantics(mergeDescendants = true) {}
                .liveRegionOf(LiveRegionMode.Polite),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(notAcceptedTitleRes(read)),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = acceptedBody(acceptedDocumentTypes),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The screen after a picked photo read as [read], a kind the app does not accept - a category outside the
 * set, or a kind that could not be told. States the two facts and offers another photo (and manual entry
 * when enabled), like the "no MRZ found" screen. The title is an assertive live region, matching that
 * screen: the person did not move focus here.
 */
@Composable
internal fun SavedImageNotAcceptedContent(
    read: NotAcceptedKind,
    acceptedDocumentTypes: Set<DocumentCategory>,
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
                .testTag(SAVED_IMAGE_NOT_ACCEPTED_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(notAcceptedTitleRes(read)),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.liveRegionOf(LiveRegionMode.Assertive),
        )
        Text(
            text = acceptedBody(acceptedDocumentTypes),
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

/**
 * The inline note on the manual-entry screen after the typed lines read as [read], a kind the app does not
 * accept - a category outside the set, or a kind that could not be told. The typed text stays. A polite live
 * region, like the parse-failed note on the same screen.
 */
@Composable
internal fun ManualNotAcceptedNote(
    read: NotAcceptedKind,
    acceptedDocumentTypes: Set<DocumentCategory>,
) {
    Column(
        modifier =
            Modifier
                .testTag(MANUAL_NOT_ACCEPTED_TEST_TAG)
                .semantics(mergeDescendants = true) {}
                .liveRegionOf(LiveRegionMode.Polite),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(notAcceptedTitleRes(read)),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = acceptedBody(acceptedDocumentTypes),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
