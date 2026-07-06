package io.lightine.tessera.mrz.camera.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.lightine.tessera.mrz.camera.CameraError
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.RecognizedText
import io.lightine.tessera.mrz.camera.ScanQuality
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Host-side test for the camera-status screens ([CameraInUseContent] / [CameraUnavailableContent], mockups
 * 05 / 05b), the struggling hint ("Type it instead" affordance, mockup 02), and the pure
 * [reduceCameraResult] reducer — the camera-error / struggling states of [MrzScannerScreen], run on the JVM
 * via Robolectric (no device), same shape as [ReviewScreenTest] / [ManualEntryScreenTest].
 *
 * The reducer is the seam that makes the flow's reaction to the per-frame result stream host-testable: the
 * live timeout and the CameraInUse → Scanning auto-recovery go through real CameraX and are device-only (per
 * the testing-layers rule, no live camera on host/emulator), but the *decision* each result drives is the
 * pure [reduceCameraResult], unit-tested here for every input. The composables are driven directly with
 * their callbacks, exactly as the flow wires them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraStatusScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    // Quality is metadata only and never gates a result (ADR-020); a fixed neutral value is fine for the
    // reducer, which branches purely on the result kind.
    private val quality = ScanQuality(mrzRegionFound = false, ocrConfidence = null, recognizedLineCount = 0)

    // A real Decoded fixture assembled from a clean synthetic TD3 specimen (the same the sibling tests use),
    // so GoDecoded carries a genuine decode rather than a hand-built one.
    private val decoded: MrzScanResult.Decoded =
        assembleManualDecoded(
            text = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<\nL898902C<3UTO6908061F9406236ZE184226B<<<<<14",
            hint = ManualFormatHint.AUTO,
            referenceTime = Instant.parse("1994-01-01T00:00:00Z"),
        )

    // ---------------------------------------------------------------------------------------------------------
    // reduceCameraResult — the pure reducer, all inputs.
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun decoded_result_reduces_to_go_decoded_carrying_the_decode() {
        val effect = reduceCameraResult(decoded)
        assertTrue(effect is CameraFlowEffect.GoDecoded, "a Decoded result must reduce to GoDecoded")
        assertEquals(decoded, effect.decoded, "GoDecoded carries the decode through for routing")
    }

    @Test
    fun camera_in_use_capture_error_reduces_to_go_camera_in_use() {
        val result = MrzScanResult.CaptureError(CameraError.CameraInUse("held by another app"), quality)
        assertEquals(
            CameraFlowEffect.GoCameraInUse,
            reduceCameraResult(result),
            "CameraInUse is recoverable — it drives the in-use notice, which self-resumes (no retry)",
        )
    }

    @Test
    fun camera_unavailable_capture_error_reduces_to_go_camera_unavailable() {
        val result = MrzScanResult.CaptureError(CameraError.CameraUnavailable("no usable camera"), quality)
        assertEquals(
            CameraFlowEffect.GoCameraUnavailable,
            reduceCameraResult(result),
            "CameraUnavailable is terminal — it drives the unavailable notice (no auto-recovery)",
        )
    }

    @Test
    fun ocr_failed_capture_error_is_a_transient_miss_and_stays_scanning() {
        val result = MrzScanResult.CaptureError(CameraError.OcrFailed("frame OCR miss"), quality)
        assertEquals(
            CameraFlowEffect.StayScanning,
            reduceCameraResult(result),
            "OcrFailed is a transient per-frame miss — keep scanning, no state change",
        )
    }

    @Test
    fun no_mrz_found_stays_scanning() {
        val result = MrzScanResult.NoMrzFound(RecognizedText(emptyList()), quality)
        assertEquals(
            CameraFlowEffect.StayScanning,
            reduceCameraResult(result),
            "NoMrzFound is the normal per-frame churn — keep scanning",
        )
    }

    @Test
    fun permission_denied_capture_error_stays_scanning_because_the_gate_owns_the_permission_path() {
        // The screen's own permission gate owns the permission path before the stream starts; a
        // permission-denied capture error therefore drives no distinct flow effect here.
        val result = MrzScanResult.CaptureError(CameraError.PermissionDenied("not granted"), quality)
        assertEquals(CameraFlowEffect.StayScanning, reduceCameraResult(result))
    }

    // ---------------------------------------------------------------------------------------------------------
    // Camera-status composables — heading renders, manual-entry action fires. (mockups 05 / 05b)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun camera_in_use_renders_its_heading_and_the_manual_entry_action_fires() {
        var manualEntryCalls = 0
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                CameraInUseContent(onManualEntry = { manualEntryCalls++ })
            }
        }

        composeRule.onNodeWithTag(CAMERA_IN_USE_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Camera is in use").assertIsDisplayed()
        // Recoverable status stated in words (not carried by the pulse alone).
        composeRule.onNodeWithText("Reconnecting…").assertIsDisplayed()

        composeRule.onNodeWithText("Enter details manually").performClick()
        assertEquals(1, manualEntryCalls, "the single action routes to manual entry (there is no retry)")
    }

    @Test
    fun camera_unavailable_renders_its_heading_and_the_manual_entry_action_fires() {
        var manualEntryCalls = 0
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                CameraUnavailableContent(onManualEntry = { manualEntryCalls++ })
            }
        }

        composeRule.onNodeWithTag(CAMERA_UNAVAILABLE_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Camera unavailable").assertIsDisplayed()

        composeRule.onNodeWithText("Enter details manually").performClick()
        assertEquals(1, manualEntryCalls, "the terminal screen's only forward path is manual entry")
    }

    // ---------------------------------------------------------------------------------------------------------
    // Struggling hint — the "Type it instead" affordance fires its callback. (mockup 02)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun struggling_hint_type_it_instead_fires_the_manual_entry_callback() {
        var manualEntryCalls = 0
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                // The live preview that overlays the hint needs a real camera (device-only), so the hint's
                // copy and its "Type it instead" affordance are exercised through the StrugglingHint entry
                // point directly — the same composable the flow overlays on the preview.
                StrugglingHint(onManualEntry = { manualEntryCalls++ })
            }
        }

        composeRule.onNodeWithTag(STRUGGLING_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Still looking — try more light or move closer").assertIsDisplayed()

        composeRule.onNodeWithText("Type it instead").performClick()
        assertEquals(1, manualEntryCalls, "\"Type it instead\" routes to manual entry")
    }
}
