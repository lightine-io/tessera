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
 * Host-side test for the privacy / transparency notice (TES-88): the top-bar "Privacy" affordance
 * ([PrivacyNoticeAction]) and the plain-language dialog it opens ([PrivacyNoticeDialog]). Both are exercised
 * directly through their extracted entry points, per the testing-layers rule (the scaffold wiring is trivial;
 * the behaviour under test is open-on-tap and the dialog's content).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivacyNoticeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun affordance_shows_the_label_and_the_dialog_is_closed_until_tapped() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    PrivacyNoticeAction()
                }
            }
        }

        composeRule.onNodeWithTag(PRIVACY_ACTION_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Privacy").assertIsDisplayed()
        // Nothing is shown until the user opts in by tapping — the notice informs, it never nags.
        composeRule.onNodeWithTag(PRIVACY_DIALOG_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun tapping_the_affordance_opens_the_dialog_with_the_plain_explanation() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    PrivacyNoticeAction()
                }
            }
        }

        composeRule.onNodeWithTag(PRIVACY_ACTION_TEST_TAG).performClick()

        composeRule.onNodeWithTag(PRIVACY_DIALOG_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Your document stays on your device").assertIsDisplayed()
        // The honest boundary: reads on-device, keeps nothing, hands the result back to the host app.
        composeRule
            .onNodeWithText(
                "Tessera reads your document right here on your device. Nothing is uploaded or saved anywhere. " +
                    "What it reads is handed back to the app you are using, and that app decides what happens next.",
            ).assertIsDisplayed()
    }

    @Test
    fun got_it_dismisses_the_dialog() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    PrivacyNoticeAction()
                }
            }
        }

        composeRule.onNodeWithTag(PRIVACY_ACTION_TEST_TAG).performClick()
        composeRule.onNodeWithTag(PRIVACY_DIALOG_TEST_TAG).assertIsDisplayed()

        composeRule.onNodeWithText("Got it").performClick()
        composeRule.onNodeWithTag(PRIVACY_DIALOG_TEST_TAG).assertDoesNotExist()
    }
}
