// @Composable functions are PascalCase by Compose convention — see the note in MrzScannerScreen.kt.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.RecognizedLine
import io.lightine.tessera.mrz.camera.RecognizedText
import io.lightine.tessera.mrz.camera.ScanQuality
import io.lightine.tessera.mrz.parsing.ManualMrzReader
import io.lightine.tessera.mrz.parsing.ParseResult
import kotlin.time.Clock
import kotlin.time.Instant

// The manual raw-MRZ entry screen (mockup 06). The user types the MRZ lines by hand; the SDK parses exactly
// what they typed and stamps the manual-entry read method as provenance (ManualMrzReader). Reader, not
// oracle (Principle 1): the live observations state neutral length notes, never "invalid" and never an
// auto-correction. Everything the user typed is shown back verbatim (Principle 5).
//
// SCOPE (TES-63 raw-entry slice): this builds mockup 06 (raw MRZ entry) only. Field-by-field entry (mockup
// 06b) was dropped for 0.5.0 (TES-79) as a separate future feature, so the screen shows the primary
// "Read what I typed" action only, with no "Enter field by field" secondary button.
//
// TES-100: the Auto / Passport / ID card format-hint chip row (plus its "the buttons above just say which
// document type to expect" note) is REMOVED. It read as a meaningful choice ("tell us the type") when it was
// really only a parser hint that never touched what the user typed — confusing for a benefit that mostly did
// not matter (mrz-core's own format auto-detection is reliable). Reading always runs
// [ManualMrzReader.read] (auto-detect), and the per-line observation is always the plain character count —
// the behaviour AUTO already had, now the only behaviour.

/** Semantics anchor for the manual raw-MRZ entry screen. Not user-facing. */
internal const val MANUAL_RAW_TEST_TAG: String = "tessera-mrz-manual-raw"

/** Semantics anchor for the manual-entry MRZ text field. Not user-facing. */
internal const val MANUAL_RAW_FIELD_TEST_TAG: String = "tessera-mrz-manual-raw-field"

/**
 * Splits raw typed text into the MRZ lines the reader parses: newline-separated, blank lines dropped, each
 * line trimmed of surrounding whitespace. Kept pure and Compose-free so the text→lines→Decoded pipeline is
 * host-unit-testable without the camera (mirrors how [routeDecode] was extracted).
 */
internal fun manualLinesOf(text: String): List<String> = text.split('\n').map { it.trim() }.filter { it.isNotBlank() }

/**
 * Assembles a [`MrzScanResult.Decoded`][MrzScanResult.Decoded] from the raw text a user typed, auto-detecting
 * the format from the split lines via [ManualMrzReader.read] (TES-100 — the format-hint chips that used to
 * pick a specific reader are gone; auto-detection was already the default and is now the only path) and
 * wrapping the parser's verdict together with the typed lines as the recognized text. Pure and Compose-free
 * (no camera, no UI) so the whole text→Decoded assembly is host-unit-testable — the same seam [routeDecode]
 * uses to make the camera path's routing testable.
 *
 * [ManualMrzReader] stamps [`ReadMethod.MANUAL_ENTRY`][io.lightine.tessera.types.vocabulary.ReadMethod.MANUAL_ENTRY]
 * as provenance, so the resulting `Decoded` carries manual-entry provenance through unchanged — the review
 * screen then shows "Read by manual entry" with no extra wiring. A garbage or malformed input yields a
 * `Decoded` whose [`parse`][MrzScanResult.Decoded.parse] is a [`ParseResult.Failure`][ParseResult.Failure],
 * which routes to the read-failed screen exactly as a failed camera decode does (reader, not oracle: manual
 * entry adds no judgement of its own — it reports the parser's verdict verbatim).
 */
internal fun assembleManualDecoded(
    text: String,
    referenceTime: Instant = Clock.System.now(),
): MrzScanResult.Decoded {
    val lines = manualLinesOf(text)
    val parse: ParseResult = ManualMrzReader.read(lines, referenceTime)
    return MrzScanResult.Decoded(
        parse = parse,
        recognizedText = RecognizedText(lines.map { RecognizedLine(it, null) }),
        quality =
            ScanQuality(
                mrzRegionFound = true,
                ocrConfidence = null,
                recognizedLineCount = lines.size,
            ),
    )
}

/**
 * One neutral length note for a typed line — [lineNumber] is [chars] characters long. Stated, not judged
 * (reader, not oracle, Principle 1): there is no expected length to compare against since the format-hint
 * chips that used to fix one are gone (TES-100), so every line only ever gets its plain character count.
 */
internal data class ManualLengthNote(
    val lineNumber: Int,
    val chars: Int,
)

/**
 * The live, neutral observations for the manual-entry screen — a character-count note per typed line
 * (TES-100: always the count, never a delta against an expected length — the format-hint chips that used to
 * fix one are gone). Pure (Compose-free, resource-free) so the *content* of the observations is unit-testable
 * off-device; [ManualRawContent] resolves the [Observation.text] strings from these parts.
 */
internal fun manualObservationParts(text: String): List<ManualLengthNote> =
    manualLinesOf(text).mapIndexed { index, line -> ManualLengthNote(lineNumber = index + 1, chars = line.length) }

/**
 * The manual raw-MRZ entry screen (mockup 06). The user types the 1–3 MRZ lines (newline-separated) into a
 * monospace field; live neutral observations state each line's character count; and the primary action hands
 * the typed text back through [onRead]. TES-100: no format-hint chip row and no "buttons are hints" note —
 * reading always auto-detects the format.
 *
 * @param state the in-progress input ([ScannerUiState.ManualRaw.text]).
 * @param onTextChange called with the new text on every edit.
 * @param onRead called when the user taps "Read this".
 */
@Composable
internal fun ManualRawContent(
    state: ScannerUiState.ManualRaw,
    onTextChange: (String) -> Unit,
    onRead: () -> Unit,
) {
    // The whole screen is ONE scroll: title → field → observations → action. imePadding shrinks the scroll
    // viewport when the keyboard opens, so the focused field scrolls into view and the user scrolls down to
    // the button — nothing is crammed or hidden. (The earlier pinned-button + weight(1f) middle collapsed
    // under the keyboard: the tall field ate the space and the observations vanished.)
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .imePadding()
                .contentMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .testTag(MANUAL_RAW_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tessera_scanner_manual_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        // Monospace, multi-line text field bound to the typed text. Uppercase + no autocorrect: an MRZ is
        // upper-case A–Z / 0–9 / '<', and autocorrect would fight the user — but this only shapes the
        // soft keyboard, it does not rewrite what is already typed (reader, not oracle). Forced left-to-right
        // regardless of the ambient locale: an MRZ is always printed left-to-right per ICAO 9303, and under an
        // RTL system locale the field would otherwise mirror the typed characters' visual order, garbling
        // input that must stay verbatim (Principle 5) — mirrors MonoLine's same override for the raw MRZ text
        // shown elsewhere.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            OutlinedTextField(
                value = state.text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth().testTag(MANUAL_RAW_FIELD_TEST_TAG),
                label = { Text(text = stringResource(R.string.tessera_scanner_manual_field_label)) },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                singleLine = false,
                minLines = 3,
            )
        }

        HorizontalDivider()

        Text(
            text = stringResource(R.string.tessera_scanner_review_observations_header),
            style = MaterialTheme.typography.titleSmall,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            manualObservations(state.text).forEach { ReviewObservationRow(it) }
        }

        // Inline parse-failed note. A polite live region so it is announced when it appears (a11y). Never an
        // "invalid" verdict — the SDK could not read it (Principle 1).
        if (state.parseFailed) {
            Text(
                text = stringResource(R.string.tessera_scanner_manual_parse_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        // Disabled while the field is blank — there is nothing to read, and attempting an empty parse would
        // otherwise report a "couldn't read" as if the input were malformed rather than simply absent.
        Button(
            onClick = onRead,
            enabled = state.text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.tessera_scanner_manual_read))
        }
    }
}

/**
 * The live observation list rendered on the manual-entry screen: each neutral character-count note from
 * [manualObservationParts] resolved to its `tessera_*` string. All [ObservationTone.INFO] — nothing here is a
 * verdict (Principle 1).
 */
@Composable
private fun manualObservations(text: String): List<Observation> =
    manualObservationParts(text).map { note ->
        // A <plurals> quantity string (no hard-coded plural noun, and "character"/"characters" spelled out in
        // full — never the "chars" abbreviation) so a one-off count reads grammatically ("1 character", not
        // "1 characters").
        val message =
            pluralStringResource(
                R.plurals.tessera_scanner_manual_obs_char_count,
                note.chars,
                note.lineNumber.toString(),
                note.chars.toString(),
            )
        Observation(symbol = MANUAL_SYMBOL_INFO, text = message, tone = ObservationTone.INFO)
    }

/** The ⓘ mark used on the manual-entry info observations (matches the review screens' info symbol). */
private const val MANUAL_SYMBOL_INFO: String = "ⓘ"
