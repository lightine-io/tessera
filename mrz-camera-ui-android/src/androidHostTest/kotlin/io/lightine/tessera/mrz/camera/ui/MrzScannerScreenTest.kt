package io.lightine.tessera.mrz.camera.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.RecognizedLine
import io.lightine.tessera.mrz.camera.RecognizedText
import io.lightine.tessera.mrz.camera.ScanQuality
import io.lightine.tessera.mrz.parsing.MrzParser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.time.Instant

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

        // Manual → manual raw entry.
        composeRule.onNodeWithText("Manual").performClick()
        composeRule.onNodeWithTag(MANUAL_RAW_TEST_TAG).assertIsDisplayed()

        // Camera → back to the camera state (permission-grant screen on the host).
        composeRule.onNodeWithText("Camera").performClick()
        composeRule.onNodeWithTag(PERMISSION_GRANT_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun camera_only_config_never_offers_a_manual_entry_escape() {
        // A consumer that disabled MANUAL_ENTRY must never be routed into — and stranded on — a manual-entry
        // screen it cannot reach any other way (no switcher tab to get back out, since fewer than two methods
        // are enabled). The permission-gate screen reached here on host must not offer the escape.
        composeRule.setContent {
            MrzScannerScreen(
                config = MrzScannerConfig { enabledMethods = setOf(ScanMethod.CAMERA) },
                onResult = {},
            )
        }

        composeRule.onNodeWithTag(PERMISSION_GRANT_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Enter details manually").assertDoesNotExist()
    }

    // ----------------------------------------------------------------------------------------------------
    // TES-92 — backEffect(): the pure decision behind the scanner's BackHandler. Host-unit-tested directly,
    // mirroring routeDecode / reduceCameraResult (no real back-press dispatcher needed).
    // ----------------------------------------------------------------------------------------------------

    private fun fakeDecoded(): MrzScanResult.Decoded {
        val line1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
        val line2 = "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"
        return MrzScanResult.Decoded(
            parse = MrzParser.parse(listOf(line1, line2), referenceTime = Instant.parse("1994-01-01T00:00:00Z")),
            recognizedText = RecognizedText(listOf(RecognizedLine(line1, null), RecognizedLine(line2, null))),
            quality = ScanQuality(mrzRegionFound = true, ocrConfidence = null, recognizedLineCount = 2),
        )
    }

    @Test
    fun back_at_the_root_capture_screen_cancels_exactly_like_the_close_x() {
        assertEquals(BackEffect.Cancel, backEffect(ScannerUiState.Scanning(), cameraEnabled = true))
        assertEquals(BackEffect.Cancel, backEffect(ScannerUiState.ManualRaw(), cameraEnabled = true))
        // Notice / gate screens with no back target of their own also cancel.
        assertEquals(BackEffect.Cancel, backEffect(ScannerUiState.CameraInUse, cameraEnabled = true))
        assertEquals(BackEffect.Cancel, backEffect(ScannerUiState.CameraUnavailable, cameraEnabled = true))
        assertEquals(BackEffect.Cancel, backEffect(ScannerUiState.SavedImageAnalyzing, cameraEnabled = true))
    }

    @Test
    fun back_from_a_non_expanded_review_returns_to_its_source_method_without_firing_on_result() {
        // "Without firing onResult" is structural here, not asserted at the call site: ReturnToSource is a
        // distinct effect from Cancel, and only Cancel ever drives onResult (see ScannerFlow's BackHandler) —
        // so returning ReturnToSource for a non-expanded review is itself the guarantee onResult is not fired.
        val review = ScannerUiState.Review(fakeDecoded(), source = ScanMethod.CAMERA)
        assertEquals(
            BackEffect.ReturnToSource(review),
            backEffect(review, cameraEnabled = true),
            "back from review must return to the source method, the same target its own secondary action uses",
        )
    }

    @Test
    fun back_from_an_expanded_review_collapses_it_first() {
        val expanded = ScannerUiState.Review(fakeDecoded(), source = ScanMethod.CAMERA, expanded = true)
        assertEquals(BackEffect.Collapse, backEffect(expanded, cameraEnabled = true))
    }

    @Test
    fun back_from_read_failed_reopens_the_saved_image_capture_prompt() {
        // ReadFailed is only ever reached from the saved-image flow — never the live camera.
        assertEquals(
            BackEffect.ReenterSavedImagePick,
            backEffect(ScannerUiState.ReadFailed(RecognizedText(emptyList())), cameraEnabled = true),
        )
    }

    @Test
    fun back_from_a_saved_image_prompt_returns_to_camera_when_enabled_else_cancels() {
        assertEquals(BackEffect.ReturnToCamera, backEffect(ScannerUiState.AwaitingSavedImagePick, cameraEnabled = true))
        assertEquals(BackEffect.ReturnToCamera, backEffect(ScannerUiState.SavedImageEmpty, cameraEnabled = true))
        // Saved-image is the actual entry point here (camera disabled) — nowhere else to go but cancel.
        assertEquals(BackEffect.Cancel, backEffect(ScannerUiState.AwaitingSavedImagePick, cameraEnabled = false))
        assertEquals(BackEffect.Cancel, backEffect(ScannerUiState.SavedImageEmpty, cameraEnabled = false))
    }

    // ----------------------------------------------------------------------------------------------------
    // TES-93 — the hoisted manual-entry draft survives a method switch and a rescan/edit-entry round trip.
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun manual_entry_text_survives_a_method_switch_away_and_back() {
        composeRule.setContent {
            MrzScannerScreen(config = MrzScannerConfig { onRequestPermission = {} }, onResult = {})
        }

        composeRule.onNodeWithText("Manual").performClick()
        composeRule.onNodeWithTag(MANUAL_RAW_FIELD_TEST_TAG).performTextInput("P<UTOTEST")

        // Switch away to Camera (permission-grant screen on host, no live preview) and back to Manual — the
        // typed text must not have been silently discarded by rebuilding an empty ManualRaw().
        composeRule.onNodeWithText("Camera").performClick()
        composeRule.onNodeWithTag(PERMISSION_GRANT_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Manual").performClick()

        composeRule.onNodeWithTag(MANUAL_RAW_FIELD_TEST_TAG).assertTextContains("P<UTOTEST", substring = true)
    }

    @Test
    fun edit_entry_from_a_manual_provenance_review_prefills_the_submitted_lines() {
        composeRule.setContent {
            MrzScannerScreen(config = MrzScannerConfig { onRequestPermission = {} }, onResult = {})
        }

        composeRule.onNodeWithText("Manual").performClick()
        val line1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
        val line2 = "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"
        composeRule.onNodeWithTag(MANUAL_RAW_FIELD_TEST_TAG).performTextInput("$line1\n$line2")
        // Scroll the primary action into view before clicking — with the field focused (and imePadding
        // shrinking the scroll viewport), it can sit outside the current viewport.
        composeRule.onNodeWithText("Read this").performScrollTo().performClick()

        // A valid typed MRZ lands on the review screen (default ReviewMode.REVIEW); its secondary action is
        // provenance-aware ("Edit entry" for manual, TES-96) — tapping it must return to manual entry
        // PREFILLED with the lines that were actually submitted, not a blank field (TES-93).
        composeRule.onNodeWithTag(REVIEW_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Edit entry").performClick()

        composeRule.onNodeWithTag(MANUAL_RAW_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(MANUAL_RAW_FIELD_TEST_TAG).assertTextContains(line1, substring = true)
        composeRule.onNodeWithTag(MANUAL_RAW_FIELD_TEST_TAG).assertTextContains(line2, substring = true)
    }
}
