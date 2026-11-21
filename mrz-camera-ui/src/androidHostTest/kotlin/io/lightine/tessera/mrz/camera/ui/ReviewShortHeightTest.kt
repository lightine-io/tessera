package io.lightine.tessera.mrz.camera.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.RecognizedLine
import io.lightine.tessera.mrz.camera.RecognizedText
import io.lightine.tessera.mrz.camera.ScanQuality
import io.lightine.tessera.mrz.parsing.MrzParser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * Host-side test for the review screens on a short height (TES-231). On a phone in landscape the content
 * area under the top bar and above the footer is about 250dp; with the title and both buttons pinned, the
 * scrolling field list shrank to a sliver showing only the "Document" row. Below the threshold the whole
 * column now scrolls as one, with the buttons at its end; on a tall (portrait) height the buttons stay pinned.
 *
 * The landscape tests use a landscape phone qualifier for the window and host the screen in a [ShortHeight]
 * box, the content area the real scaffold leaves on a Pixel 9 in landscape. Which column scrolls is read from
 * the semantics tree ([SemanticsProperties.VerticalScrollAxisRange] on the screen's tagged column), so nothing
 * depends on timing. The fixture is the project's synthetic TD3 specimen [ReviewScreenTest] uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewShortHeightTest {
    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        /** The content area the scaffold leaves on a Pixel 9 in landscape (about 411dp minus the chrome). */
        val ShortHeight: Dp = 250.dp
    }

    private val decoded: MrzScanResult.Decoded =
        run {
            val line1 = "P<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<"
            val line2 = "T220001293UTO9003152F3403141S98765432<<<<<20"
            MrzScanResult.Decoded(
                parse = MrzParser.parse(listOf(line1, line2), referenceTime = Instant.parse("2026-05-04T12:00:00Z")),
                recognizedText = RecognizedText(lines = listOf(RecognizedLine(line1, 0.95f), RecognizedLine(line2, 0.93f))),
                quality = ScanQuality(mrzRegionFound = true, ocrConfidence = 0.94f, recognizedLineCount = 2),
            )
        }

    private val scrollsVertically = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    private val doesNotScrollVertically = SemanticsMatcher.keyNotDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun setScreen(
        height: Dp?,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    val box = if (height == null) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(height)
                    Box(modifier = box) { content() }
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w900dp-h411dp-land")
    fun landscape_review_scrolls_as_one_column_with_every_field_and_the_buttons_reachable() {
        setScreen(ShortHeight) {
            ReviewContent(decoded = decoded, expanded = false, onToggleExpanded = {}, onUse = {}, onRescan = {})
        }

        // The whole screen column scrolls, so the buttons are no longer pinned on screen from the start.
        composeRule.onNodeWithTag(REVIEW_TEST_TAG).assert(scrollsVertically)
        composeRule.onNodeWithText("Use this result").assertIsNotDisplayed()

        // Every summary row (label and value) can be scrolled into view, then the provenance and both buttons.
        listOf("Document", "P - passport", "Name", "SPECIMEN, ALEX MORGAN", "Number", "T22000129", "Expiry")
            .forEach { composeRule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        composeRule.onNodeWithText("Read by live camera").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Use this result").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Rescan").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w900dp-h411dp-land")
    fun landscape_expanded_review_scrolls_as_one_column_with_the_buttons_reachable() {
        setScreen(ShortHeight) {
            ReviewContent(decoded = decoded, expanded = true, onToggleExpanded = {}, onUse = {}, onRescan = {})
        }

        composeRule.onNodeWithTag(REVIEW_EXPANDED_TEST_TAG).assert(scrollsVertically)
        composeRule.onNodeWithText("T220001293UTO9003152F3403141S98765432<<<<<20").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Use this result").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Rescan").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w900dp-h411dp-land")
    fun landscape_read_failed_scrolls_as_one_column_with_the_buttons_reachable() {
        val captured = RecognizedText(lines = listOf(RecognizedLine("P<UT0SPEClMEN<<ALE X<<<<<<<<<<<<", null)))
        setScreen(ShortHeight) {
            ReadFailedContent(capturedText = captured, onTryAgain = {}, onManualEntry = {})
        }

        composeRule.onNodeWithTag(READ_FAILED_TEST_TAG).assert(scrollsVertically)
        composeRule.onNodeWithText("P<UT0SPEClMEN<<ALE X<<<<<<<<<<<<").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Enter details manually").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-port")
    fun portrait_review_keeps_the_buttons_pinned_and_visible_without_scrolling() {
        setScreen(height = null) {
            ReviewContent(decoded = decoded, expanded = false, onToggleExpanded = {}, onUse = {}, onRescan = {})
        }

        // Today's layout: the screen column itself does not scroll (only the body inside it does), and both
        // buttons are on screen with no scrolling at all.
        composeRule.onNodeWithTag(REVIEW_TEST_TAG).assert(doesNotScrollVertically)
        composeRule.onNodeWithText("Use this result").assertIsDisplayed()
        composeRule.onNodeWithText("Rescan").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-port")
    fun portrait_expanded_review_keeps_the_buttons_pinned_and_visible_without_scrolling() {
        setScreen(height = null) {
            ReviewContent(decoded = decoded, expanded = true, onToggleExpanded = {}, onUse = {}, onRescan = {})
        }

        composeRule.onNodeWithTag(REVIEW_EXPANDED_TEST_TAG).assert(doesNotScrollVertically)
        composeRule.onNodeWithText("Use this result").assertIsDisplayed()
        composeRule.onNodeWithText("Rescan").assertIsDisplayed()
    }
}
