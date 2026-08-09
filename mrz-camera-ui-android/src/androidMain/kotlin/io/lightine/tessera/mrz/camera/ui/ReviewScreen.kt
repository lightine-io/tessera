// @Composable functions are PascalCase by Compose convention — see the note in MrzScannerScreen.kt.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.model.MrvA
import io.lightine.tessera.mrz.model.MrvB
import io.lightine.tessera.mrz.model.MrzDate
import io.lightine.tessera.mrz.model.MrzDocument
import io.lightine.tessera.mrz.model.TD1
import io.lightine.tessera.mrz.model.TD2
import io.lightine.tessera.mrz.model.TD3
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.mrz.recognition.CountryCode
import io.lightine.tessera.mrz.recognition.DocumentType
import io.lightine.tessera.types.errors.MrzCheckDigitMismatch
import io.lightine.tessera.types.vocabulary.DocumentCategory
import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.ReadMethod

// The review screen (mockups 03 / 03b / 03c) and the read-failed screen (mockup 08).
//
// Reader, not oracle (Principle 1). These screens state *observations* — never a "valid" / "invalid"
// verdict. A check digit "matches" or is shown as "recorded X, computed Y"; a date "is a well-formed date";
// provenance says how it was read. The primary action stays enabled even when a check digit does not match,
// because reporting the mismatch and letting the consumer decide is exactly the SDK's job — it never
// blocks, disables, or auto-corrects. Every value is monospace and shown verbatim (Principle 5); the raw
// MRZ and captured OCR lines are never wrapped or truncated, only horizontally scrolled.

// ---------------------------------------------------------------------------------------------------------
// Observation model — the honest-reporting vocabulary shared across the review screens.
// ---------------------------------------------------------------------------------------------------------

/** The visual/semantic tone of an [Observation]: whether it reports a match, a mismatch, or neutral info. */
internal enum class ObservationTone {
    /** A recorded value agrees with what the SDK computes (rendered ✓, green). */
    MATCHES,

    /** A recorded value disagrees with what the SDK computes (rendered ‼, amber). Not "wrong" — just stated. */
    MISMATCH,

    /** Neutral, informational (rendered ⓘ, muted): provenance, advisories, quality. */
    INFO,
}

/**
 * One stated observation about a reading — a symbol, its text, and its tone. The meaning lives in [text]
 * and in the tone's content-description prefix (see [ReviewObservationRow]), never in colour alone, so it
 * survives for a screen-reader user (non-colour a11y).
 */
internal data class Observation(
    val symbol: String,
    val text: String,
    val tone: ObservationTone,
)

// ---------------------------------------------------------------------------------------------------------
// Field mappers — display data from the parsed model. Shared with the sibling screens (saved image, manual
// entry) that also render parsed fields; kept here as internal helpers rather than duplicated per screen.
// ---------------------------------------------------------------------------------------------------------

/** One key/value summary row: [label] on the start, monospace [value] on the end. */
internal data class FieldRow(
    val label: String,
    val value: String,
)

/**
 * The display value for the name row: "PRIMARY, SECONDARY" when there is a secondary identifier, or just
 * `primary` alone when it is blank — a mononym (the documented no-`<<` case, [`secondaryIdentifier`] parses
 * to `""`) would otherwise render with a dangling trailing comma ("SURNAME, "). Display-only formatting;
 * the raw MRZ section still shows every character verbatim (Principle 5). Shared by [reviewSummaryRows] and
 * [reviewAllFieldRows] so the two mappers can never drift apart on this.
 */
@Composable
private fun nameDisplay(
    primary: String,
    secondary: String,
): String = if (secondary.isBlank()) primary else stringResource(R.string.tessera_scanner_name_format, primary, secondary)

/** The four summary rows shown at the top of the review screen (mockup 03): document, name, number, expiry. */
@Composable
internal fun reviewSummaryRows(document: MrzDocument): List<FieldRow> {
    val fields = document.commonFields
    return listOf(
        FieldRow(stringResource(R.string.tessera_scanner_field_document), documentDisplay(fields.documentType)),
        FieldRow(
            stringResource(R.string.tessera_scanner_field_name),
            nameDisplay(fields.primaryIdentifier, fields.secondaryIdentifier),
        ),
        FieldRow(stringResource(R.string.tessera_scanner_field_number), fields.documentNumber.withoutTrailingFiller()),
        FieldRow(stringResource(R.string.tessera_scanner_field_expiry), dateDisplay(fields.dateOfExpiry)),
    )
}

/** The full parsed field set shown in the expanded view (mockup 03c) — common fields plus format-specific extras. */
@Composable
internal fun reviewAllFieldRows(document: MrzDocument): List<FieldRow> {
    val fields = document.commonFields
    val rows =
        mutableListOf(
            FieldRow(stringResource(R.string.tessera_scanner_field_document_type), documentDisplay(fields.documentType)),
            FieldRow(stringResource(R.string.tessera_scanner_field_issuing_state), countryDisplay(fields.issuingState)),
            FieldRow(
                stringResource(R.string.tessera_scanner_field_name),
                nameDisplay(fields.primaryIdentifier, fields.secondaryIdentifier),
            ),
            FieldRow(stringResource(R.string.tessera_scanner_field_nationality), countryDisplay(fields.nationality)),
            FieldRow(stringResource(R.string.tessera_scanner_field_date_of_birth), dateDisplay(fields.dateOfBirth)),
            // The actual character on the document (rawSex), per the transparency stance — not the derived enum.
            FieldRow(stringResource(R.string.tessera_scanner_field_sex), fields.rawSex.toString()),
            FieldRow(stringResource(R.string.tessera_scanner_field_number), fields.documentNumber.withoutTrailingFiller()),
            FieldRow(stringResource(R.string.tessera_scanner_field_expiry), dateDisplay(fields.dateOfExpiry)),
        )
    // Format-specific optional / personal fields, only when the format has one and it is not blank. TD3's is
    // specifically the "personal number" field (a real ICAO concept); the generic MRZ optional-data field on
    // every other format has no such meaning, so it gets a neutral label — asserting "personal number" for it
    // would be a mild oracle-style claim the field's own spec does not make (Principle 1).
    val optional =
        when (document) {
            is TD3 -> document.personalNumber
            is TD2 -> document.optionalData
            is MrvA -> document.optionalData
            is MrvB -> document.optionalData
            is TD1 -> listOf(document.optionalData1, document.optionalData2).firstOrNull { it.isNotBlank() }
        }?.withoutTrailingFiller()
    // Strip fillers BEFORE the blank check, so an all-filler optional field (e.g. "<<<<<") shows no row at all
    // rather than an empty one.
    if (!optional.isNullOrBlank()) {
        val label = if (document is TD3) R.string.tessera_scanner_field_optional else R.string.tessera_scanner_field_optional_data
        rows += FieldRow(stringResource(label), optional)
    }
    return rows
}

/**
 * The observation set for a decoded reading (mockups 03 / 03b) — the check-digit / expiry / advisory
 * observations for its [`parse`][MrzScanResult.Decoded.parse] verdict (via [parseObservations]) plus a
 * **provenance** INFO line naming the read method (live camera / photo / manual). A parse failure carries no
 * document, so it yields no observations.
 *
 * The parse-derived part is factored out into [parseObservations] — a bare-[ParseResult] verdict with no
 * provenance to render — kept as its own pure step so this function stays a thin wrapper: the shared
 * check-digit / expiry verdict logic plus the one thing only a decoded reading has (provenance).
 */
@Composable
internal fun reviewObservations(decoded: MrzScanResult.Decoded): List<Observation> {
    val parse = decoded.parse
    val observations = parseObservations(parse).toMutableList()
    if (parse is ParseResult.Failure) return observations

    // Provenance is decode-specific (a bare parse verdict has none) — appended here, after the shared parse
    // verdict, and slotted before the advisory so the "some checks did not match" note stays the last line.
    val provenance =
        Observation(
            symbol = SYMBOL_INFO,
            text = stringResource(R.string.tessera_scanner_obs_read_by, readMethodLabel(parse.metadata.readMethod)),
            tone = ObservationTone.INFO,
        )
    val advisoryIndex = observations.indexOfFirst { it.tone == ObservationTone.INFO }
    if (advisoryIndex >= 0) observations.add(advisoryIndex, provenance) else observations += provenance
    return observations
}

/**
 * The review **summary**'s observation set (TES-96) — a deliberately short list, not the full
 * [reviewObservations]: only what needs the user's attention. A passing check (✓) or the expiry-well-formed
 * note is unsurprising and, on a fully clean read, would be the ONLY thing on the screen besides the fields —
 * noise that trains the user to stop reading. So the summary keeps just the MISMATCH (‼) rows, the
 * "some checks did not match" advisory, and the provenance line ("Read by …") — everything [ObservationTone.MATCHES]
 * is dropped. On a clean read (no mismatch) that leaves only provenance, which is exactly the "just fields +
 * provenance" summary the redesign calls for. Every dropped row is still shown, unfiltered, in the expanded
 * view ([reviewObservations] directly) — nothing here is hidden, only deferred past a disclosure (Principle 5).
 */
@Composable
internal fun reviewSummaryObservations(decoded: MrzScanResult.Decoded): List<Observation> =
    reviewObservations(decoded).filter { it.tone != ObservationTone.MATCHES }

/**
 * The observation set for a single parse verdict — the honest per-check reporting the review screen uses for
 * a decoded reading. Derived purely from the [ParseResult] and its metadata; carries **no** provenance line
 * (the caller adds one where it has a read method — see [reviewObservations]).
 *
 * Logic, in order:
 * 1. **Check digits** — one observation per check digit that is present on the format (document number,
 *    date of birth, date of expiry always; optional-data and composite only when their [MrzCheckDigits]
 *    value is non-null). A digit is a MISMATCH when `metadata.validationFailures` holds a
 *    [MrzCheckDigitMismatch] for its [MrzField]; otherwise it is a MATCH. The mismatch text states both the
 *    recorded and computed digits — neither is "the right one"; the consumer decides (Principle 1).
 * 2. **Expiry well-formed** — a MATCH observation only when the expiry components form a real calendar date
 *    (`componentsFormCalendarDate == true`).
 * 3. **Advisory** — a single neutral INFO observation "some checks did not match — verify against the
 *    document" appended when any check-digit mismatch was reported.
 *
 * A [`ParseResult.Failure`][ParseResult.Failure] carries no parsed document, so it yields an empty list.
 */
@Composable
internal fun parseObservations(parse: ParseResult): List<Observation> {
    val document =
        when (parse) {
            is ParseResult.Success -> parse.document
            is ParseResult.PartialSuccess -> parse.document
            is ParseResult.Failure -> return emptyList()
        }
    val fields = document.commonFields
    val mismatches = parse.metadata.validationFailures.filterIsInstance<MrzCheckDigitMismatch>()

    val observations = mutableListOf<Observation>()

    // 1. Check digits present on this format. Document number, date of birth, and date of expiry always
    // carry a per-field check digit; optional-data and composite only when the format has one (their
    // MrzCheckDigits value is non-null). Each is a MATCH unless validationFailures reports a mismatch for it.
    // The optional-data label mirrors reviewAllFieldRows()'s field label: TD3's is the "personal number"
    // field, everything else is generic MRZ optional data with no such meaning (reader, not oracle).
    val checkedFields =
        buildList {
            add(MrzField.DOCUMENT_NUMBER to stringResource(R.string.tessera_scanner_check_label_document_number))
            add(MrzField.DATE_OF_BIRTH to stringResource(R.string.tessera_scanner_check_label_date_of_birth))
            add(MrzField.DATE_OF_EXPIRY to stringResource(R.string.tessera_scanner_check_label_date_of_expiry))
            if (fields.checkDigits.optionalData != null) {
                val optionalLabel =
                    if (document is TD3) R.string.tessera_scanner_field_optional else R.string.tessera_scanner_check_label_optional_data
                add(MrzField.OPTIONAL_DATA to stringResource(optionalLabel))
            }
            if (fields.checkDigits.composite != null) {
                add(MrzField.COMPOSITE to stringResource(R.string.tessera_scanner_check_label_composite))
            }
        }
    for ((field, label) in checkedFields) {
        val mismatch = mismatches.firstOrNull { it.field == field }
        observations +=
            if (mismatch == null) {
                Observation(
                    symbol = SYMBOL_MATCH,
                    text = stringResource(R.string.tessera_scanner_obs_check_match, label),
                    tone = ObservationTone.MATCHES,
                )
            } else {
                Observation(
                    symbol = SYMBOL_MISMATCH,
                    text =
                        stringResource(
                            R.string.tessera_scanner_obs_check_mismatch,
                            label,
                            mismatch.observed.toString(),
                            mismatch.expected.toString(),
                        ),
                    tone = ObservationTone.MISMATCH,
                )
            }
    }

    // 2. Expiry well-formed (only when the components actually form a calendar date).
    if (fields.dateOfExpiry.componentsFormCalendarDate == true) {
        observations +=
            Observation(
                symbol = SYMBOL_MATCH,
                text =
                    stringResource(
                        R.string.tessera_scanner_obs_expiry_well_formed,
                        dateDisplay(fields.dateOfExpiry),
                    ),
                tone = ObservationTone.MATCHES,
            )
    }

    // 3. Neutral advisory when anything did not match.
    if (mismatches.isNotEmpty()) {
        observations +=
            Observation(
                symbol = SYMBOL_INFO,
                text = stringResource(R.string.tessera_scanner_obs_some_mismatch),
                tone = ObservationTone.INFO,
            )
    }

    return observations
}

// ---------------------------------------------------------------------------------------------------------
// Screens
// ---------------------------------------------------------------------------------------------------------

/**
 * The review screen (mockups 03 / 03b, and 03c when [expanded]). Shows the curated summary and the honest
 * observations for a decoded reading, with a disclosure into the full field set and raw MRZ. [onUse]
 * accepts the reading (returns `Confirmed`) — enabled on a mismatch too; [onRescan] discards it and goes
 * back to the reading method that produced this review; [onToggleExpanded] flips the all-fields view.
 *
 * **Summary vs expanded (TES-96).** The collapsed summary is deliberately short: fields, only the
 * *mismatched* checks (plus the "some checks did not match" advisory when any exist) via
 * [reviewSummaryObservations], and the provenance line — a clean read shows no per-check rows at all, just
 * fields and "Read by …". Every passing check (and the expiry-well-formed note) is not hidden, only deferred:
 * it appears, unfiltered, in the expanded view via [reviewObservations]. The secondary action's label tracks
 * how this reading was produced ([secondaryActionLabel]) — "Rescan" for the live camera, "Try another photo"
 * for a picked image, "Edit entry" for typed input — since "Rescan" reads oddly for a photo pick or a typed
 * MRZ.
 */
@Composable
internal fun ReviewContent(
    decoded: MrzScanResult.Decoded,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onUse: () -> Unit,
    onRescan: () -> Unit,
) {
    if (expanded) {
        ReviewExpandedContent(decoded = decoded, onUse = onUse, onRescan = onRescan, onCollapse = onToggleExpanded)
        return
    }

    val secondaryLabel = secondaryActionLabel(decoded.parse.metadata.readMethod)
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .contentMaxWidth()
                .padding(24.dp)
                .testTag(REVIEW_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tessera_scanner_review_title),
            style = MaterialTheme.typography.headlineSmall,
            // The review screen is the decode-landing: it appears the moment an MRZ is read. Assertive so a
            // screen reader announces the outcome ("MRZ read") on arrival — the result deserves attention.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )

        // The summary + observations + disclosure scroll; the two action buttons stay pinned below so they
        // are always reachable however long the observation list grows.
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                reviewSummaryRows(reviewDocument(decoded)).forEach { SummaryRow(it) }
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.tessera_scanner_review_observations_header),
                style = MaterialTheme.typography.titleSmall,
            )
            // Mismatches + advisory + provenance ONLY — every passing check moves to the expanded view
            // (reviewObservations, unfiltered) rather than repeating a wall of ✓ rows here (TES-96).
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                reviewSummaryObservations(decoded).forEach { ReviewObservationRow(it) }
            }

            TextButton(onClick = onToggleExpanded) {
                Text(text = stringResource(R.string.tessera_scanner_review_show_all))
            }
        }

        Button(onClick = onUse, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.tessera_scanner_review_use))
        }
        OutlinedButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
            Text(text = secondaryLabel)
        }
    }
}

/**
 * The expanded all-fields + raw-MRZ view (mockup 03c). Scrollable: the full parsed field set, the FULL
 * observation set (every ✓ and ‼ row, plus provenance — [reviewObservations], unfiltered, TES-96), and every
 * raw MRZ line (monospace, horizontally scrollable, never wrapped or truncated). A "Show less ▴" collapse
 * control ([onCollapse], mirroring the summary's "Show all fields ▾") returns to the review summary — closing
 * the earlier gap where the expanded view had no way back (TES-71). The pinned actions mirror the collapsed
 * view's — [onUse] and the provenance-aware [onRescan] — so Rescan / Try another photo / Edit entry stays
 * reachable here too (TES-96 closed the earlier gap where the expanded view dropped it entirely). The
 * scan-quality telemetry line (region / OCR confidence / recognized lines) is deliberately NOT shown here —
 * it is internal signal for the host via the result API, not user-facing copy (TES-96).
 */
@Composable
private fun ReviewExpandedContent(
    decoded: MrzScanResult.Decoded,
    onUse: () -> Unit,
    onRescan: () -> Unit,
    onCollapse: () -> Unit,
) {
    val document = reviewDocument(decoded)
    val secondaryLabel = secondaryActionLabel(decoded.parse.metadata.readMethod)
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .contentMaxWidth()
                .padding(24.dp)
                .testTag(REVIEW_EXPANDED_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tessera_scanner_review_all_fields_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            reviewAllFieldRows(document).forEach { SummaryRow(it) }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.tessera_scanner_review_observations_header),
                style = MaterialTheme.typography.titleSmall,
            )
            // The FULL set — every ✓ match and ‼ mismatch, plus provenance — unlike the summary's
            // mismatches-only view (TES-96). Nothing is hidden here, only deferred past the disclosure.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                reviewObservations(decoded).forEach { ReviewObservationRow(it) }
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.tessera_scanner_review_raw_mrz_header),
                style = MaterialTheme.typography.titleSmall,
            )
            document.rawLines.forEach { line -> MonoLine(line) }

            // The collapse affordance back to the summary — mirrors the summary's "Show all fields ▾"
            // disclosure so the expanded view is not a one-way street (TES-71).
            TextButton(onClick = onCollapse) {
                Text(text = stringResource(R.string.tessera_scanner_review_show_less))
            }
        }

        Button(onClick = onUse, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.tessera_scanner_review_use))
        }
        // Rescan / Try another photo / Edit entry, matching the collapsed view's provenance-aware label
        // (TES-96 — the expanded view previously dropped this action entirely).
        OutlinedButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
            Text(text = secondaryLabel)
        }
    }
}

/**
 * The "couldn't read this MRZ" screen (mockup 08). Shown when OCR produced text that did not parse as any
 * known MRZ format. States that honestly — never "invalid" — shows the captured text verbatim (garbles
 * preserved, monospace, per-line horizontal scroll), and offers a retry or a switch to manual entry.
 */
@Composable
internal fun ReadFailedContent(
    capturedText: io.lightine.tessera.mrz.camera.RecognizedText,
    onTryAgain: () -> Unit,
    onManualEntry: () -> Unit,
    // False when the consumer's enabledMethods excludes MANUAL_ENTRY — then the manual escape is hidden so the
    // user is never routed into a method that was deliberately disabled (mirrors the camera-status notices).
    showManualEntry: Boolean = true,
) {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .contentMaxWidth()
                .padding(24.dp)
                .testTag(READ_FAILED_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tessera_scanner_read_failed_title),
            style = MaterialTheme.typography.headlineSmall,
            // The read-failed screen is a decode-landing too (OCR text that didn't parse). Assertive so the
            // outcome ("Couldn't read this MRZ") is announced on arrival — the user needs to know to retry.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
        Text(
            text = stringResource(R.string.tessera_scanner_read_failed_body),
            style = MaterialTheme.typography.bodyMedium,
        )

        // The body + captured text scroll; the two actions stay pinned below and always reachable.
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.tessera_scanner_read_failed_captured_header),
                style = MaterialTheme.typography.titleSmall,
            )
            capturedText.lines.forEach { line -> MonoLine(line.text) }
        }

        Button(onClick = onTryAgain, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.tessera_scanner_read_failed_try_again))
        }
        if (showManualEntry) {
            OutlinedButton(onClick = onManualEntry, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.tessera_scanner_read_failed_manual))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Reusable components — shared with the sibling screens.
// ---------------------------------------------------------------------------------------------------------

/** The gap between a [SummaryRow]'s label and value on one line, and between the two lines when it wraps. */
private val SummaryRowSpacing = 16.dp

/**
 * A key/value summary row: label on the start, monospace value on the end — normally one line
 * (TES-95). At a large system font size (`fontScale`), a long label plus a long monospace value can no
 * longer both fit one line; measured naively that would squeeze the value into an unreadable sliver instead
 * of actually wrapping. This measures both texts' natural (unconstrained) widths with [rememberTextMeasurer]
 * and, only when they would not both fit side by side, falls back to stacking the value on its own full-width
 * line under the label — never a squeezed single line. On a phone at default font scale the two almost always
 * fit, so this is a no-op there; it only changes anything once the available width is actually exceeded.
 *
 * `internal` (not `private`): host-tested directly with a controlled width and font scale (the same
 * testing-layers reasoning behind exposing other visual-only composables directly) rather than only indirectly
 * through a real MRZ fixture's field lengths at Robolectric's default window width, neither of which a host
 * test can pin exactly.
 */
@Composable
internal fun SummaryRow(row: FieldRow) {
    val labelStyle = MaterialTheme.typography.bodyMedium
    val valueStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Measured once per (text, style) rather than on every recomposition (TES-83) — the row's strings
        // are fixed for a given review, so re-measuring them each pass was pure waste.
        val labelWidthPx = remember(row.label, labelStyle, measurer) { measurer.measure(row.label, style = labelStyle).size.width }
        val valueWidthPx = remember(row.value, valueStyle, measurer) { measurer.measure(row.value, style = valueStyle).size.width }
        val spacingPx = with(density) { SummaryRowSpacing.roundToPx() }
        val fitsOneLine = labelWidthPx + spacingPx + valueWidthPx <= constraints.maxWidth

        if (fitsOneLine) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = row.label, style = labelStyle)
                Text(text = row.value, style = valueStyle)
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = row.label, style = labelStyle)
                Text(text = row.value, style = valueStyle, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * One MRZ / captured-OCR line: monospace, on its own horizontally-scrollable row so a long line is never
 * wrapped or truncated (transparency — the consumer sees exactly what was read). Reusable by any screen
 * that shows raw MRZ text (the review's raw-MRZ section, the read-failed captured text, manual raw entry).
 *
 * Forced left-to-right regardless of the ambient locale (a hardware/system RTL setting, e.g. Arabic or
 * Hebrew): an MRZ is always printed left-to-right per ICAO 9303 — under RTL the layout direction would
 * otherwise mirror both the text order and which edge the horizontal scroll starts from, garbling a string
 * that must stay verbatim (Principle 5). [CompositionLocalProvider] scopes the override to just this line, so
 * the rest of the screen keeps the ambient direction.
 */
@Composable
internal fun MonoLine(text: String) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
        )
    }
}

/**
 * One observation line: its symbol and text, coloured by tone. The tone is carried non-visually two ways so
 * it never depends on colour alone (non-colour a11y): the literal ✓ / ‼ / ⓘ symbol is part of the text, and
 * the row merges its descendants into one semantics node whose [stateDescription] names the tone in words
 * (Match / Attention / Note) for a screen reader — merging (rather than clearing) keeps the observation's
 * visible text findable in the semantics tree.
 *
 * `internal` (not `private`): the manual-entry screen ([ManualRawContent]) reuses this same row to render its
 * live observations, so the non-colour-a11y approach stays in one place rather than being duplicated.
 */
@Composable
internal fun ReviewObservationRow(observation: Observation) {
    val color =
        when (observation.tone) {
            // Not raw `primary`: when a consumer sets MrzScannerTheme.brandColor, primary is a brand tint with
            // no contrast guarantee against the plain `surface` this text sits on (TesseraScannerTheme only
            // derives a matching onPrimary for use ON a primary-coloured surface, e.g. a button label — not for
            // primary-as-foreground here). onPrimaryContainer is untouched by brandColor and stays the theme's
            // own tuned tone, so it stays legible regardless of the tint.
            ObservationTone.MATCHES -> MaterialTheme.colorScheme.onPrimaryContainer

            ObservationTone.MISMATCH -> MaterialTheme.colorScheme.tertiary

            ObservationTone.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val tonePrefix =
        when (observation.tone) {
            ObservationTone.MATCHES -> stringResource(R.string.tessera_scanner_obs_tone_match)
            ObservationTone.MISMATCH -> stringResource(R.string.tessera_scanner_obs_tone_mismatch)
            ObservationTone.INFO -> stringResource(R.string.tessera_scanner_obs_tone_info)
        }
    Row(
        modifier =
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                stateDescription = tonePrefix
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(text = observation.symbol, color = color)
        Text(text = observation.text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------------------------------------------------
// Pure display helpers (no Compose) — the value-formatting layer, shared with the sibling screens.
// ---------------------------------------------------------------------------------------------------------

/** The ✓ / ‼ / ⓘ marks used across the observation lines. */
private const val SYMBOL_MATCH: String = "✓"
private const val SYMBOL_MISMATCH: String = "‼"
private const val SYMBOL_INFO: String = "ⓘ"

/** The document that a non-failure decode carries. Only called where the caller has already excluded Failure. */
private fun reviewDocument(decoded: MrzScanResult.Decoded): MrzDocument =
    when (val parse = decoded.parse) {
        is ParseResult.Success -> parse.document
        is ParseResult.PartialSuccess -> parse.document
        is ParseResult.Failure -> error("ReviewContent must not be shown for a parse failure")
    }

/**
 * "P - passport" from the raw type code and its category. Uses mrz-core's
 * [`DocumentType.broadCategory`][io.lightine.tessera.mrz.recognition.DocumentType.broadCategory] (TES-99),
 * which already widens the exact-match [`DocumentTypeCodeTable`][io.lightine.tessera.mrz.recognition.DocumentTypeCodeTable]
 * lookup with the ICAO reserved-leading-character fallback this screen used to keep as a private copy
 * (TES-98) — now a single source of truth in core. Raw code alone only when even the first letter is not
 * one ICAO reserves for a document category.
 */
@Composable
private fun documentDisplay(documentType: DocumentType): String {
    val category = documentType.broadCategory ?: return documentType.rawCode
    return stringResource(R.string.tessera_scanner_document_format, documentType.rawCode, categoryLabel(category))
}

@Composable
private fun categoryLabel(category: DocumentCategory): String =
    stringResource(
        when (category) {
            DocumentCategory.PASSPORT -> R.string.tessera_scanner_category_passport
            DocumentCategory.IDENTITY_CARD -> R.string.tessera_scanner_category_identity_card
            DocumentCategory.RESIDENCE_PERMIT -> R.string.tessera_scanner_category_residence_permit
            DocumentCategory.VISA -> R.string.tessera_scanner_category_visa
            DocumentCategory.OTHER -> R.string.tessera_scanner_category_other
        },
    )

/** The country's display name when the code is recognized, else its raw three-letter code. */
private fun countryDisplay(country: CountryCode): String = country.displayName ?: country.rawCode

@Composable
private fun readMethodLabel(readMethod: ReadMethod): String =
    stringResource(
        when (readMethod) {
            ReadMethod.PRE_CAPTURED_IMAGE -> R.string.tessera_scanner_read_method_photo

            ReadMethod.MANUAL_ENTRY -> R.string.tessera_scanner_read_method_manual

            // LIVE_CAMERA, and any not-yet-surfaced provenance, present as the live-camera label — this
            // review path is reached from the live camera.
            else -> R.string.tessera_scanner_read_method_live_camera
        },
    )

/**
 * The review screens' secondary-action label (TES-96): "Rescan" reads oddly for a reading that did not come
 * from the live camera — a picked photo isn't "scanned again", and typed text isn't either. Tracks the same
 * provenance [readMethodLabel] states in the "Read by …" line, but with its own copy: "Rescan" for the live
 * camera, "Try another photo" for a picked image, "Edit entry" for typed input. The action's behaviour is
 * unchanged (it still routes back to whichever method produced this review) — only the label follows
 * provenance.
 */
@Composable
private fun secondaryActionLabel(readMethod: ReadMethod): String =
    stringResource(
        when (readMethod) {
            ReadMethod.PRE_CAPTURED_IMAGE -> R.string.tessera_scanner_review_try_another_photo

            ReadMethod.MANUAL_ENTRY -> R.string.tessera_scanner_review_edit_entry

            // LIVE_CAMERA, and any not-yet-surfaced provenance, present as "Rescan" — matching readMethodLabel's
            // same default.
            else -> R.string.tessera_scanner_review_rescan
        },
    )

/**
 * The computed calendar date when mrz-core resolved one (`1969-08-06`), else the raw two-digit components
 * labeled explicitly as unresolved (`94-06-23 (year not resolved)`) — TES-94. mrz-core resolves a century via
 * a sliding plausibility window relative to the parse-time reference clock (see `MrzDate` / `MrzDateInferenceMethod`
 * in mrz-core); a date of birth almost always fits (any century up to ~130 years in the past), but an expiry
 * far outside its narrower window (10 years past / 50 years future of "now") — as any sufficiently old ICAO
 * test specimen or long-expired real document eventually will be — leaves `computedDate` null. This UI never
 * re-derives a century itself (that would duplicate mrz-core's inference and could disagree with it); it only
 * renders what core already resolved, and labels the fallback honestly rather than showing bare two-digit
 * components that could pass for a confident date (Principle 4).
 */
@Composable
private fun dateDisplay(date: MrzDate): String =
    date.computedDate?.toString()
        ?: stringResource(R.string.tessera_scanner_date_unresolved_format, "${date.rawYear}-${date.rawMonth}-${date.rawDay}")

/**
 * Strips trailing MRZ filler (`<`) from a parsed field VALUE for display — the fillers are fixed-width padding,
 * not data, so `L898902C<` reads as `L898902C` (matching how the name is already shown). Display-only: the Raw
 * MRZ section still renders every character verbatim, so transparency is preserved (Principle 5).
 */
private fun String.withoutTrailingFiller(): String = trimEnd('<')
