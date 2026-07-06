// @Composable functions are PascalCase by Compose convention — see the note in MrzScannerScreen.kt.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.model.MrvA
import io.lightine.tessera.mrz.model.MrvB
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

/** The four summary rows shown at the top of the review screen (mockup 03): document, name, number, expiry. */
@Composable
internal fun reviewSummaryRows(document: MrzDocument): List<FieldRow> {
    val fields = document.commonFields
    return listOf(
        FieldRow(stringResource(R.string.tessera_scanner_field_document), documentDisplay(fields.documentType)),
        FieldRow(
            stringResource(R.string.tessera_scanner_field_name),
            stringResource(
                R.string.tessera_scanner_name_format,
                fields.primaryIdentifier,
                fields.secondaryIdentifier,
            ),
        ),
        FieldRow(stringResource(R.string.tessera_scanner_field_number), fields.documentNumber),
        FieldRow(stringResource(R.string.tessera_scanner_field_expiry), dateDisplay(fields.dateOfExpiry.computedDateOrRaw())),
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
                stringResource(
                    R.string.tessera_scanner_name_format,
                    fields.primaryIdentifier,
                    fields.secondaryIdentifier,
                ),
            ),
            FieldRow(stringResource(R.string.tessera_scanner_field_nationality), countryDisplay(fields.nationality)),
            FieldRow(stringResource(R.string.tessera_scanner_field_date_of_birth), dateDisplay(fields.dateOfBirth.computedDateOrRaw())),
            // The actual character on the document (rawSex), per the transparency stance — not the derived enum.
            FieldRow(stringResource(R.string.tessera_scanner_field_sex), fields.rawSex.toString()),
            FieldRow(stringResource(R.string.tessera_scanner_field_number), fields.documentNumber),
            FieldRow(stringResource(R.string.tessera_scanner_field_expiry), dateDisplay(fields.dateOfExpiry.computedDateOrRaw())),
        )
    // Format-specific optional / personal fields, only when the format has one and it is not blank.
    val optional =
        when (document) {
            is TD3 -> document.personalNumber
            is TD2 -> document.optionalData
            is MrvA -> document.optionalData
            is MrvB -> document.optionalData
            is TD1 -> listOf(document.optionalData1, document.optionalData2).firstOrNull { it.isNotBlank() }
        }
    if (!optional.isNullOrBlank()) {
        rows += FieldRow(stringResource(R.string.tessera_scanner_field_optional), optional)
    }
    return rows
}

/**
 * The observation set for a reading (mockups 03 / 03b), derived from the parse verdict and metadata.
 *
 * Logic, in order:
 * 1. **Check digits** — one observation per check digit that is present on the format (document number,
 *    date of birth, date of expiry always; optional-data and composite only when their [MrzCheckDigits]
 *    value is non-null). A digit is a MISMATCH when `metadata.validationFailures` holds a
 *    [MrzCheckDigitMismatch] for its [MrzField]; otherwise it is a MATCH. The mismatch text states both the
 *    recorded and computed digits — neither is "the right one"; the consumer decides (Principle 1).
 * 2. **Expiry well-formed** — a MATCH observation only when the expiry components form a real calendar date
 *    (`componentsFormCalendarDate == true`).
 * 3. **Provenance** — an INFO observation naming the read method (live camera / photo / manual).
 * 4. **Advisory** — a single neutral INFO observation "some checks did not match — verify against the
 *    document" appended when any check-digit mismatch was reported.
 */
@Composable
internal fun reviewObservations(decoded: MrzScanResult.Decoded): List<Observation> {
    val parse = decoded.parse
    if (parse is ParseResult.Failure) return emptyList()
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
    val checkedFields =
        buildList {
            add(MrzField.DOCUMENT_NUMBER to stringResource(R.string.tessera_scanner_check_label_document_number))
            add(MrzField.DATE_OF_BIRTH to stringResource(R.string.tessera_scanner_check_label_date_of_birth))
            add(MrzField.DATE_OF_EXPIRY to stringResource(R.string.tessera_scanner_check_label_date_of_expiry))
            if (fields.checkDigits.optionalData != null) {
                add(MrzField.OPTIONAL_DATA to stringResource(R.string.tessera_scanner_check_label_optional_data))
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
                        dateDisplay(fields.dateOfExpiry.computedDateOrRaw()),
                    ),
                tone = ObservationTone.MATCHES,
            )
    }

    // 3. Provenance.
    observations +=
        Observation(
            symbol = SYMBOL_INFO,
            text = stringResource(R.string.tessera_scanner_obs_read_by, readMethodLabel(parse.metadata.readMethod)),
            tone = ObservationTone.INFO,
        )

    // 4. Neutral advisory when anything did not match.
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
 * back to scanning; [onToggleExpanded] flips the all-fields view.
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
        ReviewExpandedContent(decoded = decoded, onUse = onUse)
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(REVIEW_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tessera_scanner_review_title),
            style = MaterialTheme.typography.headlineSmall,
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                reviewObservations(decoded).forEach { ReviewObservationRow(it) }
            }

            TextButton(onClick = onToggleExpanded) {
                Text(text = stringResource(R.string.tessera_scanner_review_show_all))
            }
        }

        Button(onClick = onUse, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.tessera_scanner_review_use))
        }
        OutlinedButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.tessera_scanner_review_rescan))
        }
    }
}

/**
 * The expanded all-fields + raw-MRZ view (mockup 03c). Scrollable: the full parsed field set, every raw
 * MRZ line (monospace, horizontally scrollable, never wrapped or truncated), and a scan-quality line. The
 * only action here is [onUse]; there is no rescan on the expanded view (mockup 03c).
 */
@Composable
private fun ReviewExpandedContent(
    decoded: MrzScanResult.Decoded,
    onUse: () -> Unit,
) {
    val document = reviewDocument(decoded)
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(REVIEW_EXPANDED_TEST_TAG),
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
                text = stringResource(R.string.tessera_scanner_review_raw_mrz_header),
                style = MaterialTheme.typography.titleSmall,
            )
            document.rawLines.forEach { line -> MonoLine(line) }

            ReviewObservationRow(
                Observation(
                    symbol = SYMBOL_INFO,
                    text = scanQualityText(decoded),
                    tone = ObservationTone.INFO,
                ),
            )
        }

        Button(onClick = onUse, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.tessera_scanner_review_use))
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
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(READ_FAILED_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tessera_scanner_read_failed_title),
            style = MaterialTheme.typography.headlineSmall,
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
        OutlinedButton(onClick = onManualEntry, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.tessera_scanner_read_failed_manual))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Reusable components — shared with the sibling screens.
// ---------------------------------------------------------------------------------------------------------

/** A key/value summary row: label on the start, monospace value on the end. */
@Composable
private fun SummaryRow(row: FieldRow) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = row.label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = row.value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * One MRZ / captured-OCR line: monospace, on its own horizontally-scrollable row so a long line is never
 * wrapped or truncated (transparency — the consumer sees exactly what was read). Reusable by any screen
 * that shows raw MRZ text (saved-image candidates, manual raw entry).
 */
@Composable
internal fun MonoLine(text: String) {
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

/**
 * One observation line: its symbol and text, coloured by tone. The tone is carried non-visually two ways so
 * it never depends on colour alone (non-colour a11y): the literal ✓ / ‼ / ⓘ symbol is part of the text, and
 * the row merges its descendants into one semantics node whose [stateDescription] names the tone in words
 * (Match / Attention / Note) for a screen reader — merging (rather than clearing) keeps the observation's
 * visible text findable in the semantics tree.
 */
@Composable
private fun ReviewObservationRow(observation: Observation) {
    val color =
        when (observation.tone) {
            ObservationTone.MATCHES -> MaterialTheme.colorScheme.primary
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

/** "P — passport" from the raw type code and the recognized category; raw code alone when unrecognized. */
@Composable
private fun documentDisplay(documentType: DocumentType): String {
    val category = documentType.category ?: return documentType.rawCode
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

/** The scan-quality line for the expanded view: region status, OCR confidence, recognized line count. */
@Composable
private fun scanQualityText(decoded: MrzScanResult.Decoded): String {
    val quality = decoded.quality
    val region =
        if (quality.mrzRegionFound) {
            stringResource(R.string.tessera_scanner_quality_region_found)
        } else {
            stringResource(R.string.tessera_scanner_quality_region_not_found)
        }
    val confidence =
        quality.ocrConfidence?.let { formatConfidence(it) }
            ?: stringResource(R.string.tessera_scanner_quality_confidence_unknown)
    return stringResource(
        R.string.tessera_scanner_quality_format,
        region,
        confidence,
        quality.recognizedLineCount.toString(),
    )
}

/** Two-decimal OCR confidence (e.g. 0.94), locale-independent so the value reads the same everywhere. */
private fun formatConfidence(value: Float): String {
    val hundredths = (value * 100).toInt().coerceIn(0, 100)
    val whole = hundredths / 100
    val frac = hundredths % 100
    return "$whole.${frac.toString().padStart(2, '0')}"
}

/** The computed calendar date when the SDK inferred one, else the raw YYMMDD components exactly as recorded. */
private fun io.lightine.tessera.mrz.model.MrzDate.computedDateOrRaw(): String = computedDate?.toString() ?: "$rawYear$rawMonth$rawDay"

/** Non-null wrapper so the summary/all-field mappers can format a date string in one place. */
private fun dateDisplay(value: String): String = value
