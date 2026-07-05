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
import kotlin.test.assertEquals

/**
 * Host-side Compose UI test for [MrzScannerScreen], run on the JVM via Robolectric (no device) — the shape
 * the 0.5.0 tech-stack recap fixed for this module's UI testing.
 *
 * These assertions cover the **permission-gate** branch of the screen. On the JVM host the `CAMERA`
 * permission is not held (no device, and the test manifest declares none), so the screen shows the
 * permission prompt — which is what lets us test the gate, the permission hand-off to the host, and the
 * cancel path without a camera. The **granted → live preview** branch drives real CameraX (and constructs
 * the ML Kit recognizer), which cannot run under Robolectric; it is verified on a physical device (the
 * live-preview slice), per the testing-layers rule (no live camera on host/emulator).
 *
 * SDK is pinned below the module's compileSdk (37) to a level with Robolectric shadows; the assertions
 * are SDK-agnostic. Uses the non-deprecated `junit4.v2` compose rule. The recap's
 * `enableAccessibilityChecks()` a11y gate is wired with the a11y slice (TES-47), not here — it lives on
 * the deprecated v1 rule and is not exposed on v2.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MrzScannerScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun renders_root() {
        composeRule.setContent {
            MrzScannerScreen(config = MrzScannerConfig(), onResult = {})
        }

        composeRule.onNodeWithTag(SCANNER_ROOT_TEST_TAG).assertExists()
    }

    @Test
    fun shows_permission_prompt_when_permission_not_held() {
        composeRule.setContent {
            MrzScannerScreen(config = MrzScannerConfig(), onResult = {})
        }

        composeRule.onNodeWithTag(PERMISSION_PROMPT_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun permission_prompt_copy_resolves_from_string_resources() {
        // The displayed rationale comes from the overridable tessera_scanner_permission_rationale resource;
        // seeing its exact text proves the module's res/ + R class resolve on the host (TES-46). The
        // "Grant camera access" / "Cancel" resources are exercised by the interaction tests below.
        composeRule.setContent {
            MrzScannerScreen(config = MrzScannerConfig(), onResult = {})
        }

        composeRule.onNodeWithText("Camera permission is needed to scan the document").assertIsDisplayed()
    }

    @Test
    fun grant_button_hands_request_to_host_without_the_sdk_requesting() {
        var requestCalls = 0
        composeRule.setContent {
            MrzScannerScreen(
                config = MrzScannerConfig(onRequestPermission = { requestCalls++ }),
                onResult = {},
            )
        }

        composeRule.onNodeWithText("Grant camera access").performClick()

        assertEquals(1, requestCalls, "the host's onRequestPermission handles the request; the SDK never does")
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
