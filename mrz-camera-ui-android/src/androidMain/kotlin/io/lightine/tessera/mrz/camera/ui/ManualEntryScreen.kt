// @Composable functions are PascalCase by Compose convention — see the note in MrzScannerScreen.kt.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.RecognizedLine
import io.lightine.tessera.mrz.camera.RecognizedText
import io.lightine.tessera.mrz.camera.ScanQuality
import io.lightine.tessera.mrz.formats.Td1FormatSpec
import io.lightine.tessera.mrz.formats.Td3FormatSpec
import io.lightine.tessera.mrz.parsing.ManualMrzReader
import io.lightine.tessera.mrz.parsing.ParseResult
import kotlin.time.Clock
import kotlin.time.Instant

// The manual raw-MRZ entry screen (mockup 06). The user types the MRZ lines by hand; the SDK parses exactly
// what they typed and stamps the manual-entry read method as provenance (ManualMrzReader). Reader, not
// oracle (Principle 1): the format chips are parser *hints* only — they choose which parser to run, they
// never rewrite the input; the live observations state neutral length notes, never "invalid" and never an
// auto-correction. Everything the user typed is shown back verbatim (Principle 5).
//
// SCOPE (TES-63 raw-entry slice): this builds mockup 06 (raw MRZ entry) only. The field-by-field mode
// (mockup 06b, ScannerUiState.ManualFields) is declared but not wired — so the screen shows the primary
// "Read what I typed" action only, not the "Enter field by field" secondary button.

/** Semantics anchor for the manual raw-MRZ entry screen. Not user-facing. */
internal const val MANUAL_RAW_TEST_TAG: String = "tessera-mrz-manual-raw"

/** Semantics anchor for the manual-entry MRZ text field. Not user-facing. */
internal const val MANUAL_RAW_FIELD_TEST_TAG: String = "tessera-mrz-manual-raw-field"

/**
 * Which parser [ManualRawContent] runs against the typed lines — a *hint*, not a transformation. The chip
 * chooses the [ManualMrzReader] entry point; it never touches the input the user typed (reader, not oracle,
 * Principle 1). [expectedLineLength] is the per-line character count the format expects, or `null` for
 * [AUTO] where the SDK detects the format and no single expected length is known up front — in that case the
 * length observation states only the char count, still neutral.
 */
internal enum class ManualFormatHint(
    val expectedLineLength: Int?,
) {
    /** Auto-detect the format from the line count and per-line lengths ([ManualMrzReader.read]). */
    AUTO(expectedLineLength = null),

    /** Parse as TD3 (passport): 2 lines × 44 ([ManualMrzReader.readTD3]). */
    PASSPORT(expectedLineLength = Td3FormatSpec.lineLength),

    /** Parse as TD1 (identity card): 3 lines × 30 ([ManualMrzReader.readTD1]). */
    ID_CARD(expectedLineLength = Td1FormatSpec.lineLength),
}

/**
 * Splits raw typed text into the MRZ lines the reader parses: newline-separated, blank lines dropped, each
 * line trimmed of surrounding whitespace. Kept pure and Compose-free so the text→lines→Decoded pipeline is
 * host-unit-testable without the camera (mirrors how [routeDecode] was extracted).
 */
internal fun manualLinesOf(text: String): List<String> = text.split('\n').map { it.trim() }.filter { it.isNotBlank() }

/**
 * Assembles a [`MrzScanResult.Decoded`][MrzScanResult.Decoded] from the raw text a user typed, running the
 * [hint]'s [ManualMrzReader] entry point over the split lines and wrapping the parser's verdict together
 * with the typed lines as the recognized text. Pure and Compose-free (no camera, no UI) so the whole
 * text→Decoded assembly is host-unit-testable — the same seam [routeDecode] uses to make the camera path's
 * routing testable.
 *
 * The [ManualMrzReader] stamps [`ReadMethod.MANUAL_ENTRY`][io.lightine.tessera.types.vocabulary.ReadMethod.MANUAL_ENTRY]
 * as provenance, so the resulting `Decoded` carries manual-entry provenance through unchanged — the review
 * screen then shows "Read by manual entry" with no extra wiring. A garbage or malformed input yields a
 * `Decoded` whose [`parse`][MrzScanResult.Decoded.parse] is a [`ParseResult.Failure`][ParseResult.Failure],
 * which routes to the read-failed screen exactly as a failed camera decode does (reader, not oracle: manual
 * entry adds no judgement of its own — it reports the parser's verdict verbatim).
 */
internal fun assembleManualDecoded(
    text: String,
    hint: ManualFormatHint,
    referenceTime: Instant = Clock.System.now(),
): MrzScanResult.Decoded {
    val lines = manualLinesOf(text)
    val parse: ParseResult =
        when (hint) {
            ManualFormatHint.AUTO -> ManualMrzReader.read(lines, referenceTime)
            ManualFormatHint.PASSPORT -> ManualMrzReader.readTD3(lines, referenceTime)
            ManualFormatHint.ID_CARD -> ManualMrzReader.readTD1(lines, referenceTime)
        }
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
 * The live, neutral observations for the manual-entry screen — a length note per typed line plus the
 * standing hint-not-fixes note. Pure (Compose-free, resource-free) so the *content* of the observations is
 * unit-testable off-device; [ManualRawContent] resolves the [Observation.text] strings from these parts.
 *
 * Honesty rules (reader, not oracle, Principle 1): every observation is [ObservationTone.INFO] — never
 * MISMATCH, never "invalid", never an auto-correction. When the [hint] fixes an expected line length
 * ([ManualFormatHint.expectedLineLength] non-null, i.e. Passport / ID card), a line that differs gets a
 * neutral delta note ("Line 2 is 3 characters short" / "… 1 character over") stated against that expected
 * length; a line that matches is silent (no noise). Under [ManualFormatHint.AUTO] no single expected length
 * is known, so each non-blank line gets only its char count ("Line 1: 44 chars") — still neutral, no verdict.
 */
internal fun manualObservationParts(
    text: String,
    hint: ManualFormatHint,
): List<ManualLengthNote> {
    val lines = manualLinesOf(text)
    val expected = hint.expectedLineLength
    return lines.mapIndexedNotNull { index, line ->
        val lineNumber = index + 1
        val length = line.length
        if (expected == null) {
            // AUTO: only the char count, never a delta (no single expected length is known up front).
            ManualLengthNote.CharCount(lineNumber, length)
        } else {
            when {
                length < expected -> ManualLengthNote.Short(lineNumber, expected - length)

                length > expected -> ManualLengthNote.Over(lineNumber, length - expected)

                // Exact match: no observation — silence rather than an "OK" verdict.
                else -> null
            }
        }
    }
}

/**
 * One neutral length note for a typed line. Carries only the raw numbers; [ManualRawContent] renders each to
 * a `tessera_*` string. Never a judgement — a short/over line is stated, not called wrong (Principle 1).
 */
internal sealed interface ManualLengthNote {
    /** Auto mode: line [lineNumber] is [chars] characters long (no expected length to compare against). */
    data class CharCount(
        val lineNumber: Int,
        val chars: Int,
    ) : ManualLengthNote

    /** Line [lineNumber] is [by] characters short of the selected format's expected length. */
    data class Short(
        val lineNumber: Int,
        val by: Int,
    ) : ManualLengthNote

    /** Line [lineNumber] is [by] characters over the selected format's expected length. */
    data class Over(
        val lineNumber: Int,
        val by: Int,
    ) : ManualLengthNote
}

/**
 * The manual raw-MRZ entry screen (mockup 06). The user types the 1–3 MRZ lines (newline-separated) into a
 * monospace field; format chips pick which parser [onRead] runs (a hint only — the input is never rewritten);
 * live neutral observations state each line's length; a privacy line states the read stays on-device; and
 * the primary action hands the typed text back through [onRead].
 *
 * @param state the in-progress input ([ScannerUiState.ManualRaw.text]).
 * @param onTextChange called with the new text on every edit.
 * @param onRead called with the selected [ManualFormatHint] when the user taps "Read what I typed".
 * @param onBack cancels manual entry (the flow reports Cancelled).
 */
@Composable
internal fun ManualRawContent(
    state: ScannerUiState.ManualRaw,
    onTextChange: (String) -> Unit,
    onRead: (ManualFormatHint) -> Unit,
    onBack: () -> Unit,
) {
    // The chip is a local UI concern (which parser to run), not part of the flow's persisted ManualRaw
    // state — it never changes the typed text, only how it is read.
    var hint by remember { mutableStateOf(ManualFormatHint.AUTO) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(MANUAL_RAW_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tessera_scanner_manual_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        // The field + chips + observations scroll; the two actions stay pinned below and always reachable.
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Monospace, multi-line text field bound to the typed text. Uppercase + no autocorrect: an MRZ is
            // upper-case A–Z / 0–9 / '<', and autocorrect would fight the user — but this only shapes the
            // soft keyboard, it does not rewrite what is already typed (reader, not oracle).
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

            // Format-hint chips: Auto (default) / Passport / ID card. Parser hints only. A FilterChip is
            // ~32dp tall (below the 48dp touch-target minimum); minimumInteractiveComponentSize expands the
            // hit target to 48dp without enlarging the chip. `selected` carries the non-colour cue.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManualFormatHint.entries.forEach { option ->
                    FilterChip(
                        selected = hint == option,
                        onClick = { hint = option },
                        label = { Text(text = manualHintLabel(option)) },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    )
                }
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.tessera_scanner_review_observations_header),
                style = MaterialTheme.typography.titleSmall,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                manualObservations(state.text, hint).forEach { ReviewObservationRow(it) }
            }

            Text(
                text = stringResource(R.string.tessera_scanner_manual_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(onClick = { onRead(hint) }, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.tessera_scanner_manual_read))
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.tessera_scanner_cancel))
        }
    }
}

/** The visible label for a format-hint chip. */
@Composable
private fun manualHintLabel(hint: ManualFormatHint): String =
    stringResource(
        when (hint) {
            ManualFormatHint.AUTO -> R.string.tessera_scanner_manual_hint_auto
            ManualFormatHint.PASSPORT -> R.string.tessera_scanner_manual_hint_passport
            ManualFormatHint.ID_CARD -> R.string.tessera_scanner_manual_hint_id_card
        },
    )

/**
 * The live observation list rendered on the manual-entry screen: each neutral length note from
 * [manualObservationParts] resolved to its `tessera_*` string, followed by the standing "chips are hints,
 * not fixes" note. All [ObservationTone.INFO] — nothing here is a verdict (Principle 1).
 */
@Composable
private fun manualObservations(
    text: String,
    hint: ManualFormatHint,
): List<Observation> {
    val notes = manualObservationParts(text, hint)
    val observations =
        notes.map { note ->
            val message =
                when (note) {
                    is ManualLengthNote.CharCount -> {
                        stringResource(R.string.tessera_scanner_manual_obs_char_count, note.lineNumber.toString(), note.chars.toString())
                    }

                    is ManualLengthNote.Short -> {
                        stringResource(R.string.tessera_scanner_manual_obs_short, note.lineNumber.toString(), note.by.toString())
                    }

                    is ManualLengthNote.Over -> {
                        stringResource(R.string.tessera_scanner_manual_obs_over, note.lineNumber.toString(), note.by.toString())
                    }
                }
            Observation(symbol = MANUAL_SYMBOL_INFO, text = message, tone = ObservationTone.INFO)
        }
    // The standing hint-not-fixes note, always present so the honesty of the chips is never implicit.
    return observations +
        Observation(
            symbol = MANUAL_SYMBOL_INFO,
            text = stringResource(R.string.tessera_scanner_manual_obs_hint_note),
            tone = ObservationTone.INFO,
        )
}

/** The ⓘ mark used on the manual-entry info observations (matches the review screens' info symbol). */
private const val MANUAL_SYMBOL_INFO: String = "ⓘ"
