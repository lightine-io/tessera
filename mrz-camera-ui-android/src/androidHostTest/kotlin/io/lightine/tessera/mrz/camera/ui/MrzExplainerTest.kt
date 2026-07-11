package io.lightine.tessera.mrz.camera.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host-side test for the MRZ explainer (TES-89): the "What's the MRZ?" link ([MrzExplainerLink]) and the
 * illustrated dialog it opens ([MrzExplainerDialog]). Exercised through the extracted entry points, per the
 * testing-layers rule — the link's real home is the live-camera overlay, which needs a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MrzExplainerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun link_shows_the_question_and_the_dialog_is_closed_until_tapped() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    MrzExplainerLink()
                }
            }
        }

        composeRule.onNodeWithTag(EXPLAINER_ACTION_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("What's the MRZ?").assertIsDisplayed()
        composeRule.onNodeWithTag(EXPLAINER_DIALOG_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun tapping_the_link_opens_the_explainer_with_the_plain_answer_and_where_to_find_it() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    MrzExplainerLink()
                }
            }
        }

        composeRule.onNodeWithTag(EXPLAINER_ACTION_TEST_TAG).performClick()

        composeRule.onNodeWithTag(EXPLAINER_DIALOG_TEST_TAG).assertIsDisplayed()
        composeRule
            .onNodeWithText("MRZ is short for machine-readable zone. It's the block of text a scanner can read.")
            .assertIsDisplayed()
        // The illustration + "where to find it" note sit lower in the scrollable dialog body, so assert they
        // are present (they may be below the fold on a short test viewport), not necessarily on-screen.
        composeRule.onNodeWithText("Where to find it").assertExists()
        composeRule
            .onNodeWithText(
                "Along the bottom of the document - the last 2 or 3 lines. " +
                    "It's also on ID cards, visas and residence permits.",
            ).assertExists()
    }

    @Test
    fun got_it_dismisses_the_explainer() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    MrzExplainerLink()
                }
            }
        }

        composeRule.onNodeWithTag(EXPLAINER_ACTION_TEST_TAG).performClick()
        composeRule.onNodeWithTag(EXPLAINER_DIALOG_TEST_TAG).assertIsDisplayed()

        composeRule.onNodeWithText("Got it").performClick()
        composeRule.onNodeWithTag(EXPLAINER_DIALOG_TEST_TAG).assertDoesNotExist()
    }
}
