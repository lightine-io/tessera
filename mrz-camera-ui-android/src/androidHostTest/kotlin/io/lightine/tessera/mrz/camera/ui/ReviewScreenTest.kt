package io.lightine.tessera.mrz.camera.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.RecognizedLine
import io.lightine.tessera.mrz.camera.RecognizedText
import io.lightine.tessera.mrz.camera.ScanQuality
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
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
        // Summary rows: document (raw code + category), name (SURNAME, GIVEN), number, and a match
        // observation. The summary + observations live in a scrollable column, so scroll each into view
        // before asserting it is displayed (the whole set may exceed the test viewport height).
        composeRule.onNodeWithText("P — passport").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("ERIKSSON, ANNA MARIA").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("L898902C<").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Document number check digit matches").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun partial_success_shows_the_mismatch_and_use_stays_enabled_and_fires_confirmed() {
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

        // The mismatch is stated with both recorded and computed digits — never called "invalid". Scroll
        // the observations into view first (they sit in the scrollable middle of the screen).
        composeRule.onNodeWithText("Date of birth check digit: recorded 9, computed 1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Some checks did not match — verify against the document.").performScrollTo().assertIsDisplayed()

        // Reader-not-oracle: the primary action is NOT disabled on a mismatch. It stays enabled and returns
        // the reading verbatim — the consumer decides what the mismatch means (Principle 1). The action
        // buttons are pinned below the scroll area, so they are directly reachable.
        composeRule.onNode(hasText("Use this result")).assertIsEnabled()
        composeRule.onNodeWithText("Use this result").performClick()
        assertEquals(decoded, confirmed, "Use this result must fire onUse even when a check digit mismatched")
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
}
