package io.lightine.tessera.mrz.camera.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host-side test for the MRZ framing guide over the live preview ([MrzGuideOverlay], mockup 01, TES-87). The
 * real overlay-on-a-live-preview wiring lives inside [CameraPreview], which drives real CameraX and cannot run
 * under Robolectric, so — per the testing-layers rule — the guide is exercised directly through its extracted
 * entry point here (the same pattern as [InitializingScreenTest] / [StrugglingHint] / [TorchButtonTest]).
 *
 * The assertion locks the advisory hint: it renders, carries the overridable framing copy, and is reachable
 * by the shared guide-hint anchor (matching the iOS identifier). Whether the guide *appears* — over a live
 * preview only — is device wiring in [CameraPreview], validated end-to-end on a physical device, not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MrzGuideOverlayTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun guide_renders_the_framing_hint() {
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                MrzGuideOverlay()
            }
        }

        // Advisory framing hint — it guides, it never gates capture (Principle 1 untouched).
        composeRule.onNodeWithText("Line up the bottom of your document with the box.").assertIsDisplayed()
        composeRule.onNodeWithTag(GUIDE_HINT_TEST_TAG).assertIsDisplayed()
    }
}
