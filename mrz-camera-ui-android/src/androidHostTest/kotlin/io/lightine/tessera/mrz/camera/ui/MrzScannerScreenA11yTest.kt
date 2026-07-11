package io.lightine.tessera.mrz.camera.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Accessibility gate for the default scanner screen ([TES-A-70]). It asserts the screen's accessibility
 * **semantics** — every interactive control carries a non-empty label and the `Button` role, and the
 * informational copy is exposed as readable text — over the adaptive permission screen's Grant-mode branch
 * (the host-testable one; the granted → live-preview branch drives real CameraX, see [MrzScannerScreenTest]).
 *
 * **Why semantics assertions and not `enableAccessibilityChecks()`** (the Accessibility Test Framework):
 * ATF is a **no-op under Robolectric** — verified 2026-07-05 that it does not flag even a clearly
 * inaccessible control (an unlabeled clickable, with the validator set to throw on any warning), because
 * it needs a rendered, measured view hierarchy that Robolectric does not provide. Semantics assertions
 * read the Compose semantics tree, which Robolectric *does* populate, so they are meaningful host-side.
 * They cover label / role / structure regressions (the common a11y failures); they do **not** cover
 * contrast or touch-target size (visual checks needing real rendering) — those stay a device-side concern.
 * (TES-47; TES-A-70 corrected to match this reality.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MrzScannerScreenA11yTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun permission_grant_controls_are_labeled_buttons() {
        composeRule.setContent {
            MrzScannerScreen(
                config = MrzScannerConfig { onRequestPermission = {} },
                onResult = {},
            )
        }

        // Each interactive control is announced as a Button and carries a non-empty label, so a screen
        // reader user can find and identify it. An unlabeled control, or one missing the Button role,
        // fails here. (The Grant-mode primary + the shared manual-entry escape.)
        listOf("Grant access", "Enter details manually").forEach { label ->
            composeRule
                .onNode(hasText(label) and hasClickAction())
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        }

        // The permission rationale body is exposed as readable text (not a decorative, unlabeled element).
        composeRule
            .onNodeWithText("The scanner reads your document on this device - nothing is uploaded or stored.")
            .assertIsDisplayed()
    }
}
