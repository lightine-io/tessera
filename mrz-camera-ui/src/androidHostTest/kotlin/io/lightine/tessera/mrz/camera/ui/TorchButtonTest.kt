package io.lightine.tessera.mrz.camera.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host-side test for the torch (flashlight) toggle over the live preview ([TorchButton], mockup 01, TES-84).
 * The real flash wiring ([CameraXMrzScanner.setTorchEnabled] / [CameraXMrzScanner.hasTorch]) lives inside
 * [CameraPreview], which drives real CameraX and cannot run under Robolectric, so — per the testing-layers
 * rule — the button is exercised directly through its extracted entry point here (the same pattern as
 * [InitializingScreenTest] / [StrugglingHint]).
 *
 * These assertions lock the accessibility contract: the control has a spoken name (not carried by the
 * decorative glyph), its on/off state is a switch state a screen reader announces (never colour alone), and a
 * tap fires the toggle. Whether the button *appears* (gated on `showTorchButton` and a real flash unit) is
 * device wiring in [CameraPreview], validated end-to-end on a physical device, not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TorchButtonTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun torch_button_off_renders_with_label_and_off_toggle_state() {
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                TorchButton(torchOn = false, onToggle = {})
            }
        }

        composeRule
            .onNodeWithTag(TORCH_TEST_TAG)
            .assertIsDisplayed()
            // The control's name comes from the overridable label — not from the decorative glyph.
            .assertContentDescriptionEquals("Flashlight")
            // On/off is a switch state a screen reader announces, never carried by colour alone.
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
    }

    @Test
    fun torch_button_on_reflects_on_toggle_state() {
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                TorchButton(torchOn = true, onToggle = {})
            }
        }

        composeRule
            .onNodeWithTag(TORCH_TEST_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    }

    @Test
    fun torch_button_off_state_description_is_off() {
        // TES-84: an explicit stateDescription ("On" / "Off") is set on top of the Switch role's own toggled
        // state, so a screen reader announces the state in these exact words.
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                TorchButton(torchOn = false, onToggle = {})
            }
        }
        composeRule
            .onNodeWithTag(TORCH_TEST_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
    }

    @Test
    fun torch_button_on_state_description_is_on() {
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                TorchButton(torchOn = true, onToggle = {})
            }
        }
        composeRule
            .onNodeWithTag(TORCH_TEST_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))
    }

    @Test
    fun tapping_the_torch_button_fires_on_toggle() {
        var toggles = 0
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                TorchButton(torchOn = false, onToggle = { toggles++ })
            }
        }

        composeRule.onNodeWithTag(TORCH_TEST_TAG).performClick()

        assertEquals(1, toggles)
    }
}
