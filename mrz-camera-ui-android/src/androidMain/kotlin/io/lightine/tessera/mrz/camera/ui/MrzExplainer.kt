// @Composable functions are PascalCase by Compose convention — see the note in MrzScannerScreen.kt.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The MRZ explainer (TES-89). A first-time user meets the term "MRZ" across the flow ("Enter the MRZ manually",
// "Couldn't read this MRZ") without necessarily knowing what it is. This is the plain-language answer: a
// "What's the MRZ?" link on the camera screen opens an illustrated dialog — a stylised document with the
// machine-readable zone highlighted, the zone magnified so its characters are legible, and a short "where to
// find it" note. Purely informational; it makes no trust judgement about any document (Principle 1).
//
// The illustration is drawn from theme colours (Compose, not a baked image) so it adapts to light / dark and
// scales with the system font, and the specimen text is the ICAO Doc 9303 published example — never real data.

/** Semantics anchor for the "What's the MRZ?" link on the camera screen. Not user-facing. */
internal const val EXPLAINER_ACTION_TEST_TAG: String = "tessera-mrz-explainer-action"

/** Semantics anchor for the MRZ explainer dialog. Not user-facing. */
internal const val EXPLAINER_DIALOG_TEST_TAG: String = "tessera-mrz-explainer-dialog"

// The ICAO Doc 9303 published TD3 specimen (fictional UTOPIA / ERIKSSON ANNA MARIA). Synthetic by definition —
// this is the standards body's own example, never a real document. Hard-coded in the drawing, not overridable
// copy: it illustrates the MRZ's *shape*, so translating it would make no sense.
private const val SPECIMEN_LINE_1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
private const val SPECIMEN_LINE_2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10"

/**
 * The "What's the MRZ?" affordance shown on the camera screen (inside [MrzGuideOverlay]'s framing state). Owns
 * the dialog's open/closed state locally, so it can be dropped into the overlay with no plumbing. Rendered over
 * the guide overlay's dark scrim, so its text is white for legibility (like [StrugglingHint] / the guide hint)
 * rather than a theme colour that could vanish against the scrim.
 */
@Composable
internal fun MrzExplainerLink(modifier: Modifier = Modifier) {
    var showDialog by remember { mutableStateOf(false) }

    TextButton(
        onClick = { showDialog = true },
        modifier = modifier.testTag(EXPLAINER_ACTION_TEST_TAG),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Decorative glyph (like the scaffold's ✕ / the privacy ⓘ): cleared from semantics so the screen
            // reader announces only the question. White to read over the dark scrim.
            Text(text = "ⓘ", color = Color.White, modifier = Modifier.clearAndSetSemantics {})
            Text(text = stringResource(R.string.tessera_scanner_explainer_action), color = Color.White)
        }
    }

    if (showDialog) {
        MrzExplainerDialog(onDismiss = { showDialog = false })
    }
}

/**
 * The explainer dialog itself: a plain intro, the illustrated document with the MRZ highlighted, the zone
 * magnified so its characters are legible, and a "where to find it" note. Scrollable so it fits small screens
 * and large system font sizes. Split from [MrzExplainerLink] so it is host-testable directly.
 */
@Composable
internal fun MrzExplainerDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(EXPLAINER_DIALOG_TEST_TAG),
        title = { Text(text = stringResource(R.string.tessera_scanner_explainer_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.tessera_scanner_explainer_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                DocumentGraphic()
                // A quiet "zoom to the detail below" cue between the document and its magnified band.
                Text(
                    text = "▾",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
                )
                MrzZoomDetail()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.tessera_scanner_explainer_where_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.tessera_scanner_explainer_where_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.tessera_scanner_explainer_dismiss))
            }
        },
    )
}

/**
 * A stylised document (a passport data page): a type label, a photo placeholder with a simple silhouette, a
 * couple of faint detail lines, and — highlighted — the MRZ band along the bottom. It conveys *where* the zone
 * sits; [MrzZoomDetail] magnifies *what* it says. Drawn from theme colours so it adapts to light / dark.
 */
@Composable
private fun DocumentGraphic() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.tessera_scanner_explainer_doc_label),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PhotoPlaceholder()
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DetailLine(0.85f)
                DetailLine(0.6f)
                DetailLine(0.7f)
            }
        }
        // The MRZ band on the document — highlighted, small (the magnified, legible copy is below).
        MrzBand(fontSize = 6.5.sp, cornerRadius = 7.dp)
    }
}

/** The photo box with a simple, theme-coloured head-and-shoulders silhouette (drawn, not an image asset). */
@Composable
private fun PhotoPlaceholder() {
    val silhouette = MaterialTheme.colorScheme.outline
    Canvas(
        modifier =
            Modifier
                .size(width = 56.dp, height = 72.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // Head, then shoulders — sized in fractions of the box so it scales with the font.
        drawCircle(
            color = silhouette,
            radius = size.width * 0.22f,
            center = Offset(size.width / 2f, size.height * 0.34f),
        )
        drawOval(
            color = silhouette,
            topLeft = Offset(size.width * 0.16f, size.height * 0.6f),
            size = Size(size.width * 0.68f, size.height * 0.6f),
        )
    }
}

/** One faint placeholder detail line, [fraction] of the available width. */
@Composable
private fun DetailLine(fraction: Float) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth(fraction)
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
    ) {}
}

/**
 * The magnified MRZ: the same specimen lines at a legible size, in a highlighted panel, with a one-line
 * caption. The lines share a single horizontal scroll so a long MRZ stays readable at full size on a narrow
 * phone (rather than wrapping mid-line or shrinking to fit).
 */
@Composable
private fun MrzZoomDetail() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MrzBand(fontSize = 13.sp, cornerRadius = 0.dp, framed = false)
        Text(
            text = stringResource(R.string.tessera_scanner_explainer_zone_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * The two specimen MRZ lines in a monospace panel. Used twice: small on the document ([DocumentGraphic]) and
 * magnified in the detail ([MrzZoomDetail]). When [framed] it is a self-contained highlighted band (the
 * on-document use); otherwise it renders bare inside an already-highlighted parent (the zoom detail). The lines
 * never wrap — they share a horizontal scroll so the fixed-width MRZ stays intact at any font scale.
 */
@Composable
private fun MrzBand(
    fontSize: androidx.compose.ui.unit.TextUnit,
    cornerRadius: androidx.compose.ui.unit.Dp,
    framed: Boolean = true,
) {
    val lines: @Composable () -> Unit = {
        Column(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            MrzLine(SPECIMEN_LINE_1, fontSize)
            MrzLine(SPECIMEN_LINE_2, fontSize)
        }
    }
    if (framed) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            lines()
        }
    } else {
        lines()
    }
}

/** One monospace MRZ line: fixed-width, never wrapped, tinted for the highlighted panel. */
@Composable
private fun MrzLine(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
) {
    Text(
        text = text,
        maxLines = 1,
        softWrap = false,
        // The specimen MRZ is part of the illustration — decorative. Cleared from semantics so a screen reader
        // does not read the 44 filler-and-glyph characters aloud (twice) between the meaningful explanatory
        // copy; the intro / zone caption / "where to find it" text already carry the meaning.
        modifier = Modifier.clearAndSetSemantics {},
        style =
            TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
    )
}
