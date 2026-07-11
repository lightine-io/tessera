package io.lightine.tessera.mrz.camera.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.RecognizedLine
import io.lightine.tessera.mrz.camera.RecognizedText
import io.lightine.tessera.mrz.camera.ScanQuality
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.mrz.recognition.DocumentType
import io.lightine.tessera.types.vocabulary.DocumentCategory
import io.lightine.tessera.types.vocabulary.ReadMethod
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Host-side Compose UI test for the review screens ([ReviewContent] / [ReadFailedContent]) — the review
 * (decode → confirm) path of [MrzScannerScreen], run on the JVM via Robolectric (no device), same shape as
 * [MrzScannerScreenTest].
 *
 * These tests drive the review composables directly with a decoded fixture rather than through the camera:
 * the granted → live-preview branch drives real CameraX and cannot run under Robolectric (see
 * [MrzScannerScreenTest]), but the review screen is pure UI over an [`MrzScanResult.Decoded`], so it is
 * fully host-testable by handing it a decode built from a synthetic MRZ.
 *
 * The fixtures use the ICAO TD3 specimen the SDK's own parser tests use — synthetic data, generated to the
 * spec — parsed at a fixed [referenceTime] so date inference is deterministic and independent of the wall
 * clock. The clean specimen parses to [`ParseResult.Success`][ParseResult.Success]; the same specimen with
 * a deliberately wrong date-of-birth check digit parses to
 * [`ParseResult.PartialSuccess`][ParseResult.PartialSuccess], which drives the check-digit-mismatch
 * assertions (the reader-not-oracle behaviour: the mismatch is surfaced and "Use this result" stays
 * enabled).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    // Fixed so 2-digit year inference (DOB / expiry) is deterministic. At 1994 the specimen's expiry
    // (940623) resolves to 1994-06-23 and it parses cleanly to Success.
    private val referenceTime = Instant.parse("1994-01-01T00:00:00Z")

    // The clean ICAO TD3 specimen used across the SDK's parser tests (synthetic; UTO = Utopia, a reserved
    // ICAO test issuer). Parses to Success under referenceTime.
    private val specimenLine1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    private val specimenLine2 = "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"

    // Same specimen with the date-of-birth check digit corrupted from 1 to 9 → PartialSuccess carrying an
    // MrzCheckDigitMismatch on DATE_OF_BIRTH (observed 9, computed 1).
    private val mismatchLine2 = "L898902C<3UTO6908069F9406236ZE184226B<<<<<14"

    private fun decoded(
        line1: String,
        line2: String,
    ): MrzScanResult.Decoded =
        MrzScanResult.Decoded(
            parse = MrzParser.parse(listOf(line1, line2), referenceTime = referenceTime),
            recognizedText =
                RecognizedText(
                    lines = listOf(RecognizedLine(line1, 0.95f), RecognizedLine(line2, 0.93f)),
                ),
            quality = ScanQuality(mrzRegionFound = true, ocrConfidence = 0.94f, recognizedLineCount = 2),
        )

    /** [decoded] with its provenance swapped, so the same fixture can drive every read-method branch. */
    private fun decoded(
        line1: String,
        line2: String,
        readMethod: ReadMethod,
    ): MrzScanResult.Decoded {
        val base = decoded(line1, line2)
        val parse =
            when (val p = base.parse) {
                is ParseResult.Success -> p.copy(metadata = p.metadata.copy(readMethod = readMethod))
                is ParseResult.PartialSuccess -> p.copy(metadata = p.metadata.copy(readMethod = readMethod))
                is ParseResult.Failure -> p.copy(metadata = p.metadata.copy(readMethod = readMethod))
            }
        return base.copy(parse = parse)
    }

    @Test
    fun review_renders_the_decoded_summary_fields() {
        val decoded = decoded(specimenLine1, specimenLine2)
        assertTrue(decoded.parse is ParseResult.Success, "clean specimen must parse to Success")

        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ReviewContent(
                        decoded = decoded,
                        expanded = false,
                        onToggleExpanded = {},
                        onUse = {},
                        onRescan = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(REVIEW_TEST_TAG).assertIsDisplayed()
        // Summary rows: document (raw code + category), name (SURNAME, GIVEN), number. The summary +
        // observations live in a scrollable column, so scroll each into view before asserting it is displayed
        // (the whole set may exceed the test viewport height).
        composeRule.onNodeWithText("P - passport").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("ERIKSSON, ANNA MARIA").performScrollTo().assertIsDisplayed()
        // The document number is shown WITHOUT its trailing MRZ filler ("L898902C<" → "L898902C") — the
        // fillers are padding, not data; the Raw MRZ section (expanded view) still shows them verbatim.
        composeRule.onNodeWithText("L898902C").performScrollTo().assertIsDisplayed()
    }

    // ----------------------------------------------------------------------------------------------------
    // TES-96: the summary shows only what needs attention (mismatches + advisory + provenance) — a clean
    // read shows no per-check ✓ rows at all; the full set (every ✓ and ‼) only appears in the expanded view.
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun clean_read_summary_shows_no_check_rows_only_fields_and_provenance() {
        val decoded = decoded(specimenLine1, specimenLine2)
        assertTrue(decoded.parse is ParseResult.Success)

        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ReviewContent(decoded = decoded, expanded = false, onToggleExpanded = {}, onUse = {}, onRescan = {})
                }
            }
        }

        // Provenance is the only observation on a clean read.
        composeRule.onNodeWithText("Read by live camera").performScrollTo().assertIsDisplayed()
        // No passing check-digit rows and no expiry-well-formed row — those moved to the expanded view.
        composeRule.onAllNodesWithText("check number agrees", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("is a real date", substring = true).assertCountEquals(0)
    }

    @Test
    fun mismatch_summary_shows_only_the_mismatch_and_advisory_and_provenance() {
        val decoded = decoded(specimenLine1, mismatchLine2)
        assertTrue(decoded.parse is ParseResult.PartialSuccess)

        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ReviewContent(decoded = decoded, expanded = false, onToggleExpanded = {}, onUse = {}, onRescan = {})
                }
            }
        }

        // The mismatch is stated with both recorded and computed digits — never called "invalid".
        composeRule
            .onNodeWithText(
                "Date of birth - check number doesn't agree (document shows 9, we get 1)",
            ).performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Some checks didn't agree. Please compare with your document.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Read by live camera").performScrollTo().assertIsDisplayed()
        // The OTHER check digits on this specimen still match, but a passing ✓ row must NOT appear in the
        // summary — only the one that actually needs attention does.
        composeRule.onAllNodesWithText("check number agrees", substring = true).assertCountEquals(0)
    }

    @Test
    fun partial_success_use_stays_enabled_and_fires_confirmed() {
        val decoded = decoded(specimenLine1, mismatchLine2)
        assertTrue(decoded.parse is ParseResult.PartialSuccess, "corrupt-check-digit specimen must parse to PartialSuccess")

        var confirmed: MrzScanResult.Decoded? = null
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ReviewContent(
                        decoded = decoded,
                        expanded = false,
                        onToggleExpanded = {},
                        onUse = { confirmed = decoded },
                        onRescan = {},
                    )
                }
            }
        }

        // Reader-not-oracle: the primary action is NOT disabled on a mismatch. It stays enabled and returns
        // the reading verbatim — the consumer decides what the mismatch means (Principle 1). The action
        // buttons are pinned below the scroll area, so they are directly reachable.
        composeRule.onNode(hasText("Use this result")).assertIsEnabled()
        composeRule.onNodeWithText("Use this result").performClick()
        assertEquals(decoded, confirmed, "Use this result must fire onUse even when a check digit mismatched")
    }

    @Test
    fun a_mononym_renders_the_primary_identifier_alone_with_no_dangling_comma() {
        // A name field with no "<<" separator (a mononym) parses secondaryIdentifier to "" — the name row
        // must render just "MADONNA", never "MADONNA, " (a dangling trailing comma + space).
        val mononymLine1 = "P<UTOMADONNA<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<"
        val decoded = decoded(mononymLine1, specimenLine2)
        assertTrue(decoded.parse is ParseResult.Success, "the mononym specimen must still parse to Success")

        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ReviewContent(
                        decoded = decoded,
                        expanded = false,
                        onToggleExpanded = {},
                        onUse = {},
                        onRescan = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("MADONNA").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("MADONNA,", substring = true).assertCountEquals(0)
    }

    @Test
    fun rescan_invokes_the_rescan_callback() {
        var rescanned = false
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ReviewContent(
                        decoded = decoded(specimenLine1, specimenLine2),
                        expanded = false,
                        onToggleExpanded = {},
                        onUse = {},
                        onRescan = { rescanned = true },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Rescan").performClick()

        assertTrue(rescanned, "Rescan must invoke onRescan (the flow routes this back to scanning)")
    }

    // ----------------------------------------------------------------------------------------------------
    // TES-96: the secondary action's label tracks provenance — "Rescan" only for the live camera.
    // ----------------------------------------------------------------------------------------------------

    private fun assertSecondaryActionLabel(
        readMethod: ReadMethod,
        expectedLabel: String,
    ) {
        val fixture = decoded(specimenLine1, specimenLine2, readMethod)
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ReviewContent(decoded = fixture, expanded = false, onToggleExpanded = {}, onUse = {}, onRescan = {})
                }
            }
        }
        composeRule.onNodeWithText(expectedLabel).assertIsDisplayed()
    }

    @Test
    fun secondary_action_label_is_rescan_for_camera_provenance() {
        assertSecondaryActionLabel(ReadMethod.BACKEND_STRING_INPUT, "Rescan")
    }

    @Test
    fun secondary_action_label_is_try_another_photo_for_photo_provenance() {
        assertSecondaryActionLabel(ReadMethod.PRE_CAPTURED_IMAGE, "Try another photo")
    }

    @Test
    fun secondary_action_label_is_edit_entry_for_manual_provenance() {
        assertSecondaryActionLabel(ReadMethod.MANUAL_ENTRY, "Edit entry")
    }

    @Test
    fun expanded_view_shows_the_raw_mrz_lines_verbatim() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ReviewContent(
                        decoded = decoded(specimenLine1, specimenLine2),
                        expanded = true,
                        onToggleExpanded = {},
                        onUse = {},
                        onRescan = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(REVIEW_EXPANDED_TEST_TAG).assertIsDisplayed()
        // The raw MRZ lines are shown exactly as parsed (transparency). They are in a scrollable column, so
        // scroll to each before asserting it is displayed.
        composeRule.onNodeWithText(specimenLine1).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(specimenLine2).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun expanded_view_shows_every_check_row_matches_and_mismatches_and_the_rescan_action() {
        // TES-96: the expanded view is where every ✓ AND ‼ row lives (unfiltered), plus provenance, plus the
        // provenance-aware secondary action the earlier design dropped from this view entirely.
        val decoded = decoded(specimenLine1, mismatchLine2)
        assertTrue(decoded.parse is ParseResult.PartialSuccess)
        var rescanned = false

        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ReviewContent(
                        decoded = decoded,
                        expanded = true,
                        onToggleExpanded = {},
                        onUse = {},
                        onRescan = { rescanned = true },
                    )
                }
            }
        }

        // A passing check (document number) — ✓ tone rows now only shown here, in the expanded view.
        composeRule.onNodeWithText("Document number - check number agrees").performScrollTo().assertIsDisplayed()
        // The mismatch, the advisory, and provenance are all present too.
        composeRule
            .onNodeWithText(
                "Date of birth - check number doesn't agree (document shows 9, we get 1)",
            ).performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Some checks didn't agree. Please compare with your document.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Read by live camera").performScrollTo().assertIsDisplayed()

        // Rescan is reachable here too (TES-96 closed the earlier gap where the expanded view dropped it) —
        // pinned below the scroll area, like "Use this result", so no scroll is needed to reach it.
        composeRule.onNodeWithText("Rescan").performClick()
        assertTrue(rescanned)
    }

    @Test
    fun non_td3_optional_data_is_labeled_generically_not_personal_number() {
        // An MRV-A (visa) carries generic MRZ optional data, not TD3's "personal number" field — labelling it
        // "Personal number (optional)" would assert a meaning the field's own spec does not give it. Derived
        // from the SDK's own MrvA specimen (MrzGeneratorMrvATest) by filling the optional-data tail, which
        // carries no check digit on this format, so the doc-number/DOB/expiry check digits stay valid.
        val mrvALine1 = "V<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
        val mrvALine2 = "L898902C<3UTO6908061F3008063ABCDEFGHIJKLMNOP"
        val mrvADecoded = decoded(mrvALine1, mrvALine2)
        assertTrue(mrvADecoded.parse is ParseResult.Success, "the MRV-A optional-data fixture must parse to Success")

        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ReviewContent(
                        decoded = mrvADecoded,
                        expanded = true,
                        onToggleExpanded = {},
                        onUse = {},
                        onRescan = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Optional data").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Personal number", substring = true).assertCountEquals(0)
    }

    @Test
    fun scan_quality_telemetry_is_never_shown_in_the_ui() {
        // TES-96: the scan-quality line (region / OCR confidence / recognized lines) is internal telemetry
        // surfaced via the result API, never user-facing copy — this must be true for every provenance, not
        // just manual entry (which is where the earlier, now-removed conditional lived).
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ReviewContent(
                        decoded = decoded(specimenLine1, specimenLine2),
                        expanded = true,
                        onToggleExpanded = {},
                        onUse = {},
                        onRescan = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("All fields").assertIsDisplayed()
        composeRule.onAllNodesWithText("Scan quality", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("OCR confidence", substring = true).assertCountEquals(0)
    }

    @Test
    fun expanded_view_show_less_collapses_back_to_the_summary() {
        // TES-71: the expanded all-fields view previously had no way back. It now has a "Show less ▴" control
        // that calls onToggleExpanded (the flow flips Review.expanded back to false → the summary). Here the
        // expanded flag is hoisted so the toggle drives a real recomposition, exactly as the flow wires it.
        composeRule.setContent {
            var expanded by remember { mutableStateOf(true) }
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ReviewContent(
                        decoded = decoded(specimenLine1, specimenLine2),
                        expanded = expanded,
                        onToggleExpanded = { expanded = !expanded },
                        onUse = {},
                        onRescan = {},
                    )
                }
            }
        }

        // Starts expanded (the all-fields view). Tap "Show less ▴" (in the scrollable body) → back to summary.
        composeRule.onNodeWithTag(REVIEW_EXPANDED_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Show less ▴").performScrollTo().performClick()
        composeRule.onNodeWithTag(REVIEW_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(REVIEW_EXPANDED_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun instant_return_routes_a_decode_straight_to_confirmed_without_a_review_screen() {
        // The decode originates from the camera (device-only), so the flow's routing decision is extracted
        // into the pure routeDecode() and tested here directly — the same function ScannerFlow calls.
        // INSTANT_RETURN must hand a non-failure decode straight back (ReturnConfirmed), never park it on a
        // review screen; REVIEW must park it (ShowReview).
        val decoded = decoded(specimenLine1, specimenLine2)
        assertEquals(
            DecodeRoute.ReturnConfirmed(decoded),
            routeDecode(decoded, ReviewMode.INSTANT_RETURN),
            "INSTANT_RETURN must return Confirmed with no review step",
        )
        assertEquals(
            DecodeRoute.ShowReview(decoded),
            routeDecode(decoded, ReviewMode.REVIEW),
            "REVIEW must park the decode on the review screen",
        )
        // A PartialSuccess is still a non-failure — it follows the same paths, never diverted (Principle 1).
        val partial = decoded(specimenLine1, mismatchLine2)
        assertTrue(partial.parse is ParseResult.PartialSuccess)
        assertEquals(
            DecodeRoute.ReturnConfirmed(partial),
            routeDecode(partial, ReviewMode.INSTANT_RETURN),
            "a check-digit mismatch is a valid reading — INSTANT_RETURN still returns it",
        )
    }

    @Test
    fun read_failed_shows_captured_text_verbatim_and_never_says_invalid() {
        val garbled =
            RecognizedText(
                lines =
                    listOf(
                        RecognizedLine("P<UT0ERlKSS0N<<ANN A<<<<<<<<<<<<", null),
                        RecognizedLine("L8989 2C36UT0 74O8122F32O41 9<<<<", null),
                    ),
            )
        var triedAgain = false
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ReadFailedContent(
                        capturedText = garbled,
                        onTryAgain = { triedAgain = true },
                        onManualEntry = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(READ_FAILED_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't read this MRZ").assertIsDisplayed()
        // Garbled captured text is shown verbatim, garbles preserved (Principle 5).
        composeRule.onNodeWithText("P<UT0ERlKSS0N<<ANN A<<<<<<<<<<<<").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        assertTrue(triedAgain, "Try again must route back to scanning")
    }

    // ----------------------------------------------------------------------------------------------------
    // TES-94: the resolved date renders wherever mrz-core resolved one; the raw, explicitly labeled form
    // renders only where core genuinely could not resolve a century — never bare, ambiguous two-digit
    // components. Uses the classic ICAO 9303 published TD3 specimen (also the MRZ explainer's illustration).
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun an_expiry_core_cannot_resolve_a_century_for_renders_the_labeled_raw_form_while_birth_still_resolves() {
        val icaoLine1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
        val icaoLine2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10"
        // A reference time far enough past this specimen's printed expiry (12 → 20xx) that neither century
        // mrz-core tries falls inside its plausibility window relative to "now" — the same structural cause
        // any sufficiently old real document eventually hits (TES-94), not a UI-side century guess.
        val farReferenceTime = Instant.parse("2026-05-04T12:00:00Z")
        val icaoDecoded =
            MrzScanResult.Decoded(
                parse = MrzParser.parse(listOf(icaoLine1, icaoLine2), referenceTime = farReferenceTime),
                recognizedText = RecognizedText(listOf(RecognizedLine(icaoLine1, null), RecognizedLine(icaoLine2, null))),
                quality = ScanQuality(mrzRegionFound = true, ocrConfidence = null, recognizedLineCount = 2),
            )
        val document =
            when (val parse = icaoDecoded.parse) {
                is ParseResult.Success -> parse.document
                is ParseResult.PartialSuccess -> parse.document
                is ParseResult.Failure -> error("the ICAO published specimen must parse (got Failure: ${parse.error})")
            }
        // Confirms the premise this test is pinning: core resolved a birth-year century but not an expiry one.
        assertTrue(document.commonFields.dateOfBirth.computedDate != null, "date of birth must resolve")
        assertNull(document.commonFields.dateOfExpiry.computedDate, "expiry must NOT resolve at this reference time")

        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ReviewContent(decoded = icaoDecoded, expanded = true, onToggleExpanded = {}, onUse = {}, onRescan = {})
                }
            }
        }

        composeRule.onNodeWithText("1974-08-12").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("12-04-15 (year not resolved)").performScrollTo().assertIsDisplayed()
    }

    // ----------------------------------------------------------------------------------------------------
    // TES-95: SummaryRow falls back to a two-line (label, then value below) layout at a large system font
    // size, rather than squeezing the monospace value into an unreadable sliver.
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun summary_row_falls_back_to_two_lines_at_a_large_font_scale_when_it_would_not_fit_one_line() {
        // A very narrow forced width makes the overflow deterministic regardless of exactly how precisely
        // the host JVM's font/text-measurement environment reports glyph metrics (Robolectric's are coarser
        // than a real device's) — the point under test is the wrap-fallback MECHANISM (measured overflow →
        // Column), which a large system font size triggers on a real, narrower phone exactly the same way.
        // Any non-empty label + value cannot fit this width.
        composeRule.setContent {
            val bumped = Density(density = LocalDensity.current.density, fontScale = 1.5f)
            CompositionLocalProvider(LocalDensity provides bumped) {
                TesseraScannerTheme(MrzScannerConfig().theme) {
                    Box(modifier = Modifier.width(5.dp)) {
                        SummaryRow(FieldRow(label = "Issuing state", value = "UNITED KINGDOM OF GREAT BRITAIN"))
                    }
                }
            }
        }

        val labelBounds = composeRule.onNodeWithText("Issuing state").fetchSemanticsNode().boundsInRoot
        val valueBounds = composeRule.onNodeWithText("UNITED KINGDOM OF GREAT BRITAIN").fetchSemanticsNode().boundsInRoot
        assertTrue(
            valueBounds.top >= labelBounds.bottom,
            "the value (top=${valueBounds.top}) must fall below the label (bottom=${labelBounds.bottom}), " +
                "i.e. wrap to its own line rather than sharing the label's line",
        )
    }

    @Test
    fun summary_row_stays_one_line_when_the_content_fits() {
        // The companion case: a short label/value pair in a very roomy width must NOT wrap — the fallback
        // only fires once the content genuinely does not fit, never unconditionally.
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                Box(modifier = Modifier.width(2000.dp)) {
                    SummaryRow(FieldRow(label = "Sex", value = "F"))
                }
            }
        }

        val labelBounds = composeRule.onNodeWithText("Sex").fetchSemanticsNode().boundsInRoot
        val valueBounds = composeRule.onNodeWithText("F").fetchSemanticsNode().boundsInRoot
        assertTrue(
            valueBounds.top < labelBounds.bottom,
            "a short label/value pair that fits must stay on one line (label and value vertically overlap)",
        )
    }

    // ----------------------------------------------------------------------------------------------------
    // TES-98 / TES-99: an unrecognized-but-spec-conformant document code falls back to a first-letter
    // category guess rather than showing an unexplained raw code. The fallback itself now lives in
    // mrz-core (`DocumentType.broadCategory`, TES-99) as a single source of truth — this screen only
    // delegates to it (`documentDisplay`); this test confirms the delegation still resolves the cases the
    // UI relies on, exercised through the same public entry point mrz-core's own DocumentTypeTest covers.
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun document_type_broad_category_backs_the_review_screens_document_display_fallback() {
        assertEquals(DocumentCategory.IDENTITY_CARD, DocumentType("IA").broadCategory)
        assertEquals(DocumentCategory.PASSPORT, DocumentType("P").broadCategory)
        assertEquals(DocumentCategory.VISA, DocumentType("V").broadCategory)
        assertNull(DocumentType("XY").broadCategory, "an unreserved first letter must fall back to null (raw code alone)")
    }
}
