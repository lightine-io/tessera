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
 * Host-side test for the adaptive camera-permission screen (mockups 04 / 04b), run on the JVM via Robolectric
 * (no device), same shape as [CameraStatusScreenTest].
 *
 * Two layers:
 *  * the pure [permissionScreenState] decision — unit-tested for every combination of the three read-only
 *    signals (granted × hasAsked × showRationale), the seam that makes the adaptive mode host-testable
 *    without a device or an Activity;
 *  * the [PermissionContent] composable — both faces render their title and fire the right primary action
 *    (Grant fires onGrant, Settings fires onOpenSettings, manual fires onManualEntry), driven directly with
 *    their callbacks exactly as the gate wires them.
 *
 * The gate's own signal wiring (checkSelfPermission, shouldShowRequestPermissionRationale, the ON_RESUME
 * recompute, the Settings intent) runs through real Android plumbing and is exercised on a device / through
 * [MrzScannerScreenTest]'s Grant-mode host branch; the *decision* each combination drives is the pure
 * function unit-tested here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    // ---------------------------------------------------------------------------------------------------------
    // permissionScreenState — the pure decision, every combination.
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun granted_is_granted_regardless_of_the_other_signals() {
        // Once held, neither hasAsked nor showRationale can change the verdict — the gate shows the preview.
        for (hasAsked in listOf(false, true)) {
            for (showRationale in listOf(false, true)) {
                assertEquals(
                    PermissionScreenState.GRANTED,
                    permissionScreenState(granted = true, hasAsked = hasAsked, showRationale = showRationale),
                    "granted must win over hasAsked=$hasAsked showRationale=$showRationale",
                )
            }
        }
    }

    @Test
    fun never_asked_needs_grant() {
        // showRationale is false when never asked; hasAsked is false → a request can still succeed.
        assertEquals(
            PermissionScreenState.NEEDS_GRANT,
            permissionScreenState(granted = false, hasAsked = false, showRationale = false),
            "never asked (no rationale, not asked) → a request can still succeed",
        )
    }

    @Test
    fun never_asked_but_rationale_true_still_needs_grant() {
        // A defensive combination (not asked yet but the platform already reports a rationale): a request can
        // still succeed, so NEEDS_GRANT.
        assertEquals(
            PermissionScreenState.NEEDS_GRANT,
            permissionScreenState(granted = false, hasAsked = false, showRationale = true),
        )
    }

    @Test
    fun asked_and_rationale_true_is_denied_once_so_needs_grant() {
        // Denied once: the system will still show the dialog (rationale true), so a request can succeed.
        assertEquals(
            PermissionScreenState.NEEDS_GRANT,
            permissionScreenState(granted = false, hasAsked = true, showRationale = true),
            "denied once (rationale true) — the dialog can still appear, so Grant mode",
        )
    }

    @Test
    fun asked_and_no_rationale_is_permanently_denied() {
        // Asked at least once and the system won't show a rationale → the dialog won't appear again; only the
        // OS settings can grant it now (Settings mode, mockup 04b).
        assertEquals(
            PermissionScreenState.PERMANENTLY_DENIED,
            permissionScreenState(granted = false, hasAsked = true, showRationale = false),
            "asked, and the system won't show the dialog again → permanently denied",
        )
    }

    // ---------------------------------------------------------------------------------------------------------
    // PermissionContent — Grant mode (mockup 04).
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun grant_mode_renders_its_title_and_the_grant_action_fires() {
        var grantCalls = 0
        var settingsCalls = 0
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                PermissionContent(
                    state = PermissionScreenState.NEEDS_GRANT,
                    onGrant = { grantCalls++ },
                    onOpenSettings = { settingsCalls++ },
                    onManualEntry = {},
                    hasRequestHandler = true,
                )
            }
        }

        composeRule.onNodeWithTag(PERMISSION_GRANT_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Camera permission needed").assertIsDisplayed()

        composeRule.onNodeWithText("Grant access").performClick()
        assertEquals(1, grantCalls, "the Grant-mode primary fires onGrant (which hands the request to the host)")
        assertEquals(0, settingsCalls, "Grant mode never opens Settings")
    }

    @Test
    fun grant_mode_without_a_request_handler_draws_no_grant_button() {
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                PermissionContent(
                    state = PermissionScreenState.NEEDS_GRANT,
                    onGrant = {},
                    onOpenSettings = {},
                    onManualEntry = {},
                    hasRequestHandler = false,
                )
            }
        }

        composeRule.onNodeWithTag(PERMISSION_GRANT_TEST_TAG).assertIsDisplayed()
        // No handler → no dead Grant button; the manual-entry escape still stands.
        composeRule.onNodeWithText("Grant access").assertDoesNotExist()
        composeRule.onNodeWithText("Enter details manually").assertIsDisplayed()
    }

    @Test
    fun grant_mode_manual_entry_escape_fires() {
        var manualCalls = 0
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                PermissionContent(
                    state = PermissionScreenState.NEEDS_GRANT,
                    onGrant = {},
                    onOpenSettings = {},
                    onManualEntry = { manualCalls++ },
                    hasRequestHandler = true,
                )
            }
        }

        composeRule.onNodeWithText("Enter details manually").performClick()
        assertEquals(1, manualCalls, "the shared secondary routes to manual entry")
    }

    // ---------------------------------------------------------------------------------------------------------
    // PermissionContent — Settings mode (mockup 04b).
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun settings_mode_renders_its_title_and_the_open_settings_action_fires() {
        var grantCalls = 0
        var settingsCalls = 0
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                PermissionContent(
                    state = PermissionScreenState.PERMANENTLY_DENIED,
                    onGrant = { grantCalls++ },
                    onOpenSettings = { settingsCalls++ },
                    onManualEntry = {},
                    // hasRequestHandler is irrelevant in Settings mode — Open Settings is always available.
                    hasRequestHandler = false,
                )
            }
        }

        composeRule.onNodeWithTag(PERMISSION_DENIED_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Camera access is off").assertIsDisplayed()

        composeRule.onNodeWithText("Open Settings").performClick()
        assertEquals(1, settingsCalls, "the Settings-mode primary fires onOpenSettings (the UI's own navigation)")
        assertEquals(0, grantCalls, "Settings mode never hands a request to the host")
    }

    @Test
    fun settings_mode_manual_entry_escape_fires() {
        var manualCalls = 0
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                PermissionContent(
                    state = PermissionScreenState.PERMANENTLY_DENIED,
                    onGrant = {},
                    onOpenSettings = {},
                    onManualEntry = { manualCalls++ },
                    hasRequestHandler = false,
                )
            }
        }

        composeRule.onNodeWithText("Enter details manually").performClick()
        assertEquals(1, manualCalls, "the shared secondary routes to manual entry")
    }
}
