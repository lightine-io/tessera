package io.lightine.tessera.mrz.camera.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * permission is not held (no device, and the test manifest declares none) and nothing has been asked yet, so
 * the gate shows the adaptive permission screen in **Grant mode** (mockup 04) — which is what lets us test
 * the gate, the permission hand-off to the host, and the manual-entry escape without a camera. The
 * permanently-denied (Settings) face and the pure [permissionScreenState] decision are unit-tested in
 * [PermissionScreenTest]. The **granted → live preview** branch drives real CameraX (and constructs the ML
 * Kit recognizer), which cannot run under Robolectric; it is verified on a physical device (the live-preview
 * slice), per the testing-layers rule (no live camera on host/emulator).
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
    fun shows_permission_grant_screen_when_permission_not_held_and_not_yet_asked() {
        // No permission held + nothing asked yet → the adaptive gate shows Grant mode (mockup 04). The
        // permanently-denied (Settings) face needs a real "asked once, rationale now false" transition it
        // cannot reach on the host; it is covered by PermissionScreenTest against permissionScreenState.
        composeRule.setContent {
            MrzScannerScreen(config = MrzScannerConfig(), onResult = {})
        }

        composeRule.onNodeWithTag(PERMISSION_GRANT_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun permission_grant_copy_resolves_from_string_resources() {
        // The displayed title comes from the overridable tessera_scanner_permission_grant_title resource;
        // seeing its exact text proves the module's res/ + R class resolve on the host (TES-46). The
        // "Grant access" / "Enter details manually" resources are exercised by the interaction tests below.
        composeRule.setContent {
            MrzScannerScreen(config = MrzScannerConfig(), onResult = {})
        }

        composeRule.onNodeWithText("Camera permission needed").assertIsDisplayed()
    }

    @Test
    fun grant_button_hands_request_to_host_without_the_sdk_requesting() {
        var requestCalls = 0
        composeRule.setContent {
            MrzScannerScreen(
                config = MrzScannerConfig { onRequestPermission = { requestCalls++ } },
                onResult = {},
            )
        }

        composeRule.onNodeWithText("Grant access").performClick()

        assertEquals(1, requestCalls, "the host's onRequestPermission handles the request; the SDK never does")
    }

    @Test
    fun no_grant_button_when_host_supplied_no_request_handler() {
        // Without an onRequestPermission handler there is nothing to call, so no dead Grant button is drawn —
        // only the manual-entry escape remains (the earlier single-prompt nuance, preserved).
        composeRule.setContent {
            MrzScannerScreen(config = MrzScannerConfig(), onResult = {})
        }

        composeRule.onNodeWithTag(PERMISSION_GRANT_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Grant access").assertDoesNotExist()
        composeRule.onNodeWithText("Enter details manually").assertIsDisplayed()
    }

    @Test
    fun top_bar_close_reports_cancelled_user_dismissed() {
        // The shared top bar's ✕ owns cancel on every screen and maps to Cancelled(USER_DISMISSED) — the
        // full flow wiring, not just the ScannerScaffold in isolation (covered in MethodSwitcherTest).
        var result: MrzScannerResult? = null
        composeRule.setContent {
            MrzScannerScreen(config = MrzScannerConfig(), onResult = { result = it })
        }

        composeRule.onNodeWithContentDescription("Close").performClick()
        assertEquals(MrzScannerResult.Cancelled(DismissReason.USER_DISMISSED), result)
    }

    @Test
    fun switching_to_type_shows_manual_entry_and_back_to_camera_returns_to_the_camera_screen() {
        // Default config (camera + manual) → the flow starts on the camera state, which on the host shows the
        // permission-grant screen with the two-method switcher above it. Tapping Type switches to manual raw
        // entry; tapping Camera switches back (to the camera state → the permission-grant screen again).
        composeRule.setContent {
            MrzScannerScreen(config = MrzScannerConfig { onRequestPermission = {} }, onResult = {})
        }

        // Starts on the camera path (permission-grant on host), switcher visible.
        composeRule.onNodeWithTag(SWITCHER_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(PERMISSION_GRANT_TEST_TAG).assertIsDisplayed()

        // Type → manual raw entry.
        composeRule.onNodeWithText("Type").performClick()
        composeRule.onNodeWithTag(MANUAL_RAW_TEST_TAG).assertIsDisplayed()

        // Camera → back to the camera state (permission-grant screen on the host).
        composeRule.onNodeWithText("Camera").performClick()
        composeRule.onNodeWithTag(PERMISSION_GRANT_TEST_TAG).assertIsDisplayed()
    }
}
