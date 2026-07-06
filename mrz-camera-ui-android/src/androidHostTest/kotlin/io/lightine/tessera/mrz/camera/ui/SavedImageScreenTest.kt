package io.lightine.tessera.mrz.camera.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.lightine.tessera.mrz.camera.CameraError
import io.lightine.tessera.mrz.camera.CharacterDisambiguation
import io.lightine.tessera.mrz.camera.MrzCandidate
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.RecognizedLine
import io.lightine.tessera.mrz.camera.RecognizedText
import io.lightine.tessera.mrz.camera.SavedImageScanResult
import io.lightine.tessera.mrz.camera.ScanQuality
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Host-side test for the saved-image (photo) reading screens ([SavedImageCandidatesContent] /
 * [SavedImageEmptyContent]) and the pure result mapping ([mapSavedImageResult] / [candidateDecoded]) — the
 * saved-image path of [MrzScannerScreen], run on the JVM via Robolectric (no device), same shape as
 * [ReviewScreenTest].
 *
 * The pure mapping is unit-tested directly with synthetic [SavedImageScanResult] fixtures (built from
 * [MrzParser.parse] + hand-made [CharacterDisambiguation]s), and the composables are driven directly with a
 * candidate list. The real photo pick + ML Kit read is device-only (the picker launches the system photo
 * picker and the recognizer runs ML Kit), so it is not exercised here — per the testing-layers rule, the
 * pure mapping and the composables are covered on host.
 *
 * The fixtures use the ICAO TD3 specimen the parser tests use (synthetic; UTO = Utopia), parsed at a fixed
 * [referenceTime] so date inference is deterministic. Two candidates differ at one column (line 2, column 5):
 * candidate A resolves it to `0` (document number `L898902C<`, whose recorded check digit `3` matches) →
 * a clean [`Success`][ParseResult.Success]; candidate B resolves it to `O` (document number `L8989O2C<`,
 * whose check digit no longer computes) → a [`PartialSuccess`][ParseResult.PartialSuccess] carrying a
 * document-number check-digit mismatch. This drives the reader-not-oracle assertions: both candidates are
 * shown unranked and both are pickable, the mismatch is stated (never "invalid"), and nothing is auto-chosen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedImageScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    // Fixed so 2-digit year inference is deterministic; at 1994 the specimen parses cleanly to Success.
    private val referenceTime = Instant.parse("1994-01-01T00:00:00Z")

    private val line1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"

    // Candidate A: the clean specimen — document number L898902C< (check digit 3 matches) → Success.
    private val matchLine2 = "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"

    // Candidate B: line 2 with column 5 resolved to the letter O instead of 0, so the document number is
    // L8989O2C< and its recorded check digit 3 no longer computes → PartialSuccess (doc-number mismatch).
    private val mismatchLine2 = "L8989O2C<3UTO6908061F9406236ZE184226B<<<<<14"

    // The single ambiguous position both candidates resolved (line index 1, column 5) — 0 vs O.
    private fun candidate(
        line2: String,
        resolved: Char,
    ): MrzCandidate =
        MrzCandidate(
            mrzLines = listOf(line1, line2),
            parse = MrzParser.parse(listOf(line1, line2), referenceTime = referenceTime),
            disambiguations =
                listOf(
                    CharacterDisambiguation(lineIndex = 1, columnIndex = 5, recognized = '0', resolved = resolved),
                ),
        )

    private val matchCandidate = candidate(matchLine2, resolved = '0')
    private val mismatchCandidate = candidate(mismatchLine2, resolved = 'O')

    private fun scanResult(
        scan: MrzScanResult,
        candidates: List<MrzCandidate>,
    ): SavedImageScanResult = SavedImageScanResult(scan = scan, candidates = candidates, captureMetadata = null)

    private fun decoded(line2: String): MrzScanResult.Decoded =
        MrzScanResult.Decoded(
            parse = MrzParser.parse(listOf(line1, line2), referenceTime = referenceTime),
            recognizedText = RecognizedText(listOf(RecognizedLine(line1, null), RecognizedLine(line2, null))),
            quality = ScanQuality(mrzRegionFound = true, ocrConfidence = null, recognizedLineCount = 2),
        )

    // ----------------------------------------------------------------------------------------------------
    // Pure mapping — mapSavedImageResult / candidateDecoded
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun candidates_present_map_to_the_candidates_outcome_in_order() {
        val candidates = listOf(matchCandidate, mismatchCandidate)
        val outcome = mapSavedImageResult(scanResult(decoded(matchLine2), candidates))
        val mapped = assertIs<SavedImageOutcome.Candidates>(outcome, "any candidates must map to Candidates")
        assertEquals(candidates, mapped.candidates, "the candidate list is passed through in order, unranked")
    }

    @Test
    fun no_candidates_but_a_decoded_scan_maps_to_single_decode() {
        val outcome = mapSavedImageResult(scanResult(decoded(matchLine2), candidates = emptyList()))
        val single = assertIs<SavedImageOutcome.SingleDecode>(outcome, "a Decoded scan with no candidates → SingleDecode")
        assertEquals(decoded(matchLine2), single.decoded)
    }

    @Test
    fun no_mrz_found_maps_to_empty() {
        val noMrz =
            MrzScanResult.NoMrzFound(
                recognizedText = RecognizedText(emptyList()),
                quality = ScanQuality(mrzRegionFound = false, ocrConfidence = null, recognizedLineCount = 0),
            )
        assertEquals(
            SavedImageOutcome.Empty,
            mapSavedImageResult(scanResult(noMrz, candidates = emptyList())),
            "NoMrzFound with no candidates → Empty",
        )
    }

    @Test
    fun capture_error_maps_to_empty() {
        val captureError =
            MrzScanResult.CaptureError(
                error = CameraError.OcrFailed("decode failed"),
                quality = ScanQuality(mrzRegionFound = false, ocrConfidence = null, recognizedLineCount = 0),
            )
        assertEquals(
            SavedImageOutcome.Empty,
            mapSavedImageResult(scanResult(captureError, candidates = emptyList())),
            "CaptureError with no candidates → Empty",
        )
    }

    @Test
    fun candidate_decoded_carries_the_parse_verbatim_and_the_lines_as_recognized_text() {
        val decoded = candidateDecoded(mismatchCandidate)
        assertEquals(mismatchCandidate.parse, decoded.parse, "the candidate's own parse verdict is carried through unchanged")
        assertEquals(
            listOf(line1, mismatchLine2),
            decoded.recognizedText.lines.map { it.text },
            "the reconstructed lines become the recognized text, verbatim",
        )
        assertEquals(2, decoded.quality.recognizedLineCount)
        assertTrue(decoded.quality.mrzRegionFound)
    }

    // ----------------------------------------------------------------------------------------------------
    // Candidates screen — reader, not oracle
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun candidates_screen_shows_a_matching_and_a_mismatching_candidate_both_pickable_with_no_auto_selection() {
        // Sanity: the two fixtures really are a Success and a PartialSuccess (a doc-number check-digit mismatch).
        assertIs<ParseResult.Success>(matchCandidate.parse, "candidate A must be a clean Success")
        assertIs<ParseResult.PartialSuccess>(mismatchCandidate.parse, "candidate B must be a PartialSuccess (mismatch)")

        val picked = mutableListOf<MrzCandidate>()
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    SavedImageCandidatesContent(
                        candidates = listOf(matchCandidate, mismatchCandidate),
                        onPick = { picked += it },
                        onChooseDifferent = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(SAVED_IMAGE_CANDIDATES_TEST_TAG).assertIsDisplayed()

        // Intro states the count and that nothing is chosen for the user (reader, not oracle).
        composeRule
            .onNodeWithText("Found 2 possible readings", substring = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Nothing is chosen for you", substring = true)
            .assertIsDisplayed()

        // Both candidates' readings are rendered (the differing char is part of each reading line). The
        // reconstructed line text is present verbatim for each candidate.
        composeRule.onNodeWithText(matchLine2, substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(mismatchLine2, substring = true).performScrollTo().assertIsDisplayed()

        // The matching candidate's verdict is stated as a match; the mismatching one states recorded/computed
        // digits — never "invalid" (Principle 1). The parser computes 7 for the corrupted L8989O2C< number.
        composeRule.onNodeWithText("Document number check digit matches").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText("Document number check digit: recorded 3, computed 7")
            .performScrollTo()
            .assertIsDisplayed()

        // NO "best" / auto-selection wording exists anywhere on the screen.
        composeRule.onAllNodesWithText("best", substring = true, ignoreCase = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("recommended", substring = true, ignoreCase = true).assertCountEquals(0)

        // There is exactly one "Use this ›" button per candidate — every candidate is pickable, including the
        // one with the mismatch (the user decides which reading is theirs).
        val useButtons = composeRule.onAllNodesWithText("Use this", substring = true)
        useButtons.assertCountEquals(2)

        // Pick each in turn: both fire onPick with their own candidate (the mismatch is NOT blocked).
        useButtons[0].performScrollTo().assertIsEnabled().performClick()
        useButtons[1].performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(matchCandidate, mismatchCandidate), picked, "every candidate is pickable, in order, regardless of verdict")
    }

    // ----------------------------------------------------------------------------------------------------
    // Empty screen
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun empty_screen_renders_and_its_two_actions_fire() {
        var choseDifferent = false
        var wentManual = false
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    SavedImageEmptyContent(
                        onChooseDifferent = { choseDifferent = true },
                        onManualEntry = { wentManual = true },
                    )
                }
            }
        }

        composeRule.onNodeWithTag(SAVED_IMAGE_EMPTY_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("No MRZ found in this photo").assertIsDisplayed()
        // The privacy note: the photo never leaves the device.
        composeRule.onNodeWithText("stays on your device", substring = true).assertIsDisplayed()

        composeRule.onNodeWithText("Choose different photo").performClick()
        assertTrue(choseDifferent, "Choose different photo must fire onChooseDifferent (re-launches the picker)")

        composeRule.onNodeWithText("Enter details manually").performClick()
        assertTrue(wentManual, "Enter details manually must fire onManualEntry")
    }
}
