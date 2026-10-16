package io.lightine.tessera.mrz.camera.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Host-side tests for TES-123: the scanner configuration is fixed per presentation. [MrzScannerScreen] reads
 * the config when it opens; a different config passed while it is open is ignored until the next opening,
 * and a configuration change (rotation) is the same presentation, so it keeps the opening config.
 *
 * On the host the CAMERA permission is not held, so the camera method shows the permission-grant screen;
 * the tests use that and manual entry, neither of which needs a live camera. The fixture MRZ is the
 * synthetic ICAO TD3 specimen used across the module (UTO, a reserved ICAO test issuer).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenedConfigTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val cameraAndManual = setOf(ScanMethod.CAMERA, ScanMethod.MANUAL_ENTRY)
    private val cameraOnly = setOf(ScanMethod.CAMERA)
    private val manualOnly = setOf(ScanMethod.MANUAL_ENTRY)

    // ----------------------------------------------------------------------------------------------------
    // A config change while the screen is open is ignored.
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun enabling_a_method_while_open_does_not_add_it_to_the_switcher() {
        var config by mutableStateOf(MrzScannerConfig { enabledMethods = manualOnly })
        composeRule.setContent { MrzScannerScreen(config = config, onResult = {}) }

        // One method: manual entry, no switcher.
        composeRule.onNodeWithTag(MANUAL_RAW_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SWITCHER_TEST_TAG).assertDoesNotExist()

        config = MrzScannerConfig { enabledMethods = cameraAndManual }
        composeRule.waitForIdle()

        // The open screen keeps its opening config: still no switcher, no way into the camera.
        composeRule.onNodeWithTag(MANUAL_RAW_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SWITCHER_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Camera").assertDoesNotExist()
    }

    @Test
    fun disabling_a_method_while_open_keeps_the_switcher_and_the_current_screen_on_the_opening_config() {
        var config by mutableStateOf(MrzScannerConfig { enabledMethods = cameraAndManual })
        composeRule.setContent { MrzScannerScreen(config = config, onResult = {}) }

        composeRule.onNodeWithText("Manual").performClick()
        composeRule.onNodeWithTag(MANUAL_RAW_TEST_TAG).assertIsDisplayed()

        config = MrzScannerConfig { enabledMethods = cameraOnly }
        composeRule.waitForIdle()

        // Switcher and current screen agree: both still follow the opening config (camera and manual).
        composeRule.onNodeWithTag(MANUAL_RAW_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SWITCHER_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Manual").assertIsDisplayed()
        composeRule.onNodeWithText("Camera").performClick()
        composeRule.onNodeWithTag(PERMISSION_GRANT_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun rescan_after_a_config_change_stays_within_the_opening_config() {
        var config by mutableStateOf(MrzScannerConfig { enabledMethods = cameraAndManual })
        composeRule.setContent { MrzScannerScreen(config = config, onResult = {}) }

        composeRule.onNodeWithText("Manual").performClick()
        composeRule.onNodeWithTag(MANUAL_RAW_FIELD_TEST_TAG).performTextInput("$SPECIMEN_LINE_1\n$SPECIMEN_LINE_2")
        composeRule.onNodeWithText("Read this").performScrollTo().performClick()
        composeRule.onNodeWithTag(REVIEW_TEST_TAG).assertIsDisplayed()

        // The app switches to INSTANT_RETURN and drops manual entry while the review is up.
        config =
            MrzScannerConfig {
                enabledMethods = cameraOnly
                reviewMode = ReviewMode.INSTANT_RETURN
            }
        composeRule.waitForIdle()

        // "Edit entry" returns to manual entry, a method of the opening config, and the switcher there offers
        // the same opening methods: no screen the switcher does not know about.
        composeRule.onNodeWithText("Edit entry").performClick()
        composeRule.onNodeWithTag(MANUAL_RAW_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SWITCHER_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Manual").assertIsDisplayed()

        // Reading again still uses the opening review mode (REVIEW), not the INSTANT_RETURN passed later.
        composeRule.onNodeWithText("Read this").performScrollTo().performClick()
        composeRule.onNodeWithTag(REVIEW_TEST_TAG).assertIsDisplayed()
    }

    // ----------------------------------------------------------------------------------------------------
    // A new presentation takes the new config.
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun a_new_presentation_uses_the_new_config() {
        var shown by mutableStateOf(true)
        var config = MrzScannerConfig { enabledMethods = cameraAndManual }
        composeRule.setContent { if (shown) MrzScannerScreen(config = config, onResult = {}) }

        composeRule.onNodeWithTag(SWITCHER_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(PERMISSION_GRANT_TEST_TAG).assertIsDisplayed()

        // The screen closes, the app changes the config, and the screen opens again.
        shown = false
        composeRule.waitForIdle()
        config = MrzScannerConfig { enabledMethods = manualOnly }
        shown = true
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MANUAL_RAW_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SWITCHER_TEST_TAG).assertDoesNotExist()
    }

    // ----------------------------------------------------------------------------------------------------
    // Rotation is the same presentation: the opening config survives it.
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun rotation_keeps_the_opening_config_and_takes_the_permission_callback_from_the_current_one() {
        val restorationTester = StateRestorationTester(composeRule)
        var openingRequests = 0
        var currentRequests = 0
        // A plain var (not Compose state): changing it triggers no recomposition, so the screen first sees
        // the new config on the recreation below, as after a real rotation.
        var config =
            MrzScannerConfig {
                enabledMethods = cameraAndManual
                onRequestPermission = { openingRequests++ }
            }
        restorationTester.setContent { MrzScannerScreen(config = config, onResult = {}) }

        composeRule.onNodeWithText("Manual").performClick()
        composeRule.onNodeWithTag(MANUAL_RAW_TEST_TAG).assertIsDisplayed()

        config =
            MrzScannerConfig {
                enabledMethods = cameraOnly
                onRequestPermission = { currentRequests++ }
            }
        restorationTester.emulateSavedInstanceStateRestore()

        // Same presentation: still on manual entry, with the opening switcher.
        composeRule.onNodeWithTag(MANUAL_RAW_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SWITCHER_TEST_TAG).assertIsDisplayed()

        // The permission callback cannot outlive the recreation; the one passed now is used.
        composeRule.onNodeWithText("Camera").performClick()
        composeRule.onNodeWithText("Grant access").performClick()
        assertEquals(0, openingRequests)
        assertEquals(1, currentRequests)
    }

    // ----------------------------------------------------------------------------------------------------
    // The pure codec behind the rotation survival.
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun codec_round_trips_every_value_option() {
        val callback = {}
        val original =
            MrzScannerConfig {
                enabledMethods = setOf(ScanMethod.SAVED_IMAGE, ScanMethod.MANUAL_ENTRY)
                reviewMode = ReviewMode.INSTANT_RETURN
                showTorchButton = false
                torchOnByDefault = true
                struggleTimeout = 1500.milliseconds
                scanTimeout = 90.seconds
                consensusReads = 3
                hapticFeedback = false
                theme {
                    brandColor = 0xFF00695CL
                    darkMode = DarkMode.ON
                    useDynamicColor = true
                }
            }

        val restored = assertNotNull(decodeOpenedConfig(encodeOpenedConfig(original), callback))

        assertEquals(original.enabledMethods, restored.enabledMethods)
        assertEquals(original.reviewMode, restored.reviewMode)
        assertEquals(original.showTorchButton, restored.showTorchButton)
        assertEquals(original.torchOnByDefault, restored.torchOnByDefault)
        assertEquals(original.struggleTimeout, restored.struggleTimeout)
        assertEquals(original.scanTimeout, restored.scanTimeout)
        assertEquals(original.consensusReads, restored.consensusReads)
        assertEquals(original.hapticFeedback, restored.hapticFeedback)
        assertEquals(original.theme.brandColor, restored.theme.brandColor)
        assertEquals(original.theme.darkMode, restored.theme.darkMode)
        assertEquals(original.theme.useDynamicColor, restored.theme.useDynamicColor)
        assertSame(callback, restored.onRequestPermission)
    }

    @Test
    fun codec_round_trips_infinite_timeouts_and_defaults() {
        val original = MrzScannerConfig { struggleTimeout = Duration.INFINITE }
        val restored = assertNotNull(decodeOpenedConfig(encodeOpenedConfig(original), null))

        assertEquals(Duration.INFINITE, restored.struggleTimeout)
        assertEquals(Duration.INFINITE, restored.scanTimeout)
        assertNull(restored.theme.brandColor)
        assertNull(restored.onRequestPermission)
    }

    @Test
    fun codec_rejects_a_malformed_list() {
        val valid = encodeOpenedConfig(MrzScannerConfig())
        assertNull(decodeOpenedConfig(emptyList(), null))
        assertNull(decodeOpenedConfig(valid.dropLast(1), null))
        assertNull(decodeOpenedConfig(valid.toMutableList().also { it[0] = arrayListOf("NOT_A_METHOD") }, null))
        assertNull(decodeOpenedConfig(valid.toMutableList().also { it[1] = "NOT_A_MODE" }, null))
        assertNull(decodeOpenedConfig(valid.toMutableList().also { it[6] = "3" }, null))
    }

    private companion object {
        const val SPECIMEN_LINE_1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
        const val SPECIMEN_LINE_2 = "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"
    }
}
