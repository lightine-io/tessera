package io.lightine.tessera.mrz.camera.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Host-side Compose UI smoke test for [MrzScannerScreen], run on the JVM via Robolectric (no device) —
 * the shape the 0.5.0 tech-stack recap fixed for this module's UI testing. It proves the harness works
 * and the scaffold's two guarantees hold: the screen renders inside the module theme, and the cancel
 * path drives `onResult` exactly once with [`Cancelled`][MrzScannerResult.Cancelled].
 *
 * SDK is pinned below the module's compileSdk (36) to a level with Robolectric shadows; the smoke
 * assertions are SDK-agnostic.
 *
 * Uses the non-deprecated `junit4.v2` compose rule. The tech-stack recap's `enableAccessibilityChecks()`
 * a11y gate is NOT wired here: that method lives on the deprecated v1 rule and is not exposed on v2, and
 * the placeholder screen has nothing meaningful to check. It is wired with the real screens (where
 * contrast / touch-target size actually matter) — see the 0.5.0 UI slices.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MrzScannerScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun renders_scaffold_root() {
        composeRule.setContent {
            MrzScannerScreen(config = MrzScannerConfig(), onResult = {})
        }

        composeRule.onNodeWithTag(SCANNER_ROOT_TEST_TAG).assertExists()
    }

    @Test
    fun cancel_reports_cancelled_exactly_once() {
        var result: MrzScannerResult? = null
        var callbacks = 0
        composeRule.setContent {
            MrzScannerScreen(config = MrzScannerConfig()) {
                result = it
                callbacks++
            }
        }

        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(MrzScannerResult.Cancelled, result)
        assertEquals(1, callbacks, "onResult must fire exactly once")
    }
}
