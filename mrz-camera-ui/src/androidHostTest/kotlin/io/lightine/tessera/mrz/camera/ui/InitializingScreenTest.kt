package io.lightine.tessera.mrz.camera.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host-side test for the camera-initializing loading state ([InitializingContent], mockup 01b) — the state
 * [CameraPreview] shows while the scanner has not yet produced a preview surface (the camera + on-device OCR
 * are warming up). The real null-surface trigger lives inside [CameraPreview], which drives real CameraX and
 * cannot run under Robolectric, so — per the testing-layers rule — the content is exercised directly through
 * its extracted entry point here (the same pattern as [StrugglingHint] / [SavedImageAnalyzingContent]).
 *
 * The wiring into [CameraPreview] (render this when `surfaceRequest == null`, the live viewfinder otherwise)
 * is device-only and validated end-to-end on a physical device, not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InitializingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun initializing_renders_the_title_and_on_device_sub_text() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    InitializingContent()
                }
            }
        }

        composeRule.onNodeWithTag(INITIALIZING_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Starting camera…").assertIsDisplayed()
        // The on-device honesty sub-line — the read runs on the device, stated plainly (never a verdict).
        composeRule
            .onNodeWithText("Getting the camera ready. This all runs on your device.")
            .assertIsDisplayed()
    }

    @Test
    fun initializing_title_is_a_polite_live_region() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    InitializingContent()
                }
            }
        }

        // The state appears via an auto-transition (the preview mounts straight into it before a surface
        // exists), so the title is a polite live region — announced on arrival without moving focus (TES-47).
        composeRule
            .onNode(hasText("Starting camera…") and SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assertIsDisplayed()
    }
}
