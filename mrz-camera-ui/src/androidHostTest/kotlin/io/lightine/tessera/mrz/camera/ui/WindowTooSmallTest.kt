package io.lightine.tessera.mrz.camera.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Host-side test for the "window too small to scan" notice (TES-342). The real live preview needs CameraX, so a
 * fake camera stands in: it holds a fake scanner through the same [rememberViewportScanner] the preview uses, so
 * "the camera is bound" here means a fake scanner was started and not yet closed. What CameraX does on a real
 * bind, and how the notice looks in a real split screen, is checked on a device, not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowTooSmallTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class FakeScanner {
        var started = false
        var closed = false
    }

    private val made = mutableListOf<FakeScanner>()
    private var size by mutableStateOf(DpSize(360.dp, 120.dp))
    private var manualEntries = 0
    private var closes = 0

    private val bound: List<FakeScanner> get() = made.filter { it.started && !it.closed }

    private val fakeCamera: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxSize().testTag(VIEWFINDER_TEST_TAG)) {
            rememberViewportScanner<FakeScanner>(
                viewport = rememberPreviewViewport(constraints, 0),
                context = LocalContext.current,
                lifecycleOwner = LocalLifecycleOwner.current,
                create = { FakeScanner().also(made::add) },
                onStart = { it.started = true },
                onClose = { it.closed = true },
            )
        }
    }

    private fun setGate(showManualEntry: Boolean = true) {
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                Box(Modifier.size(size)) {
                    ScanAreaSizeGate(
                        onManualEntry = { manualEntries++ },
                        showManualEntry = showManualEntry,
                        camera = fakeCamera,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun the_threshold_is_the_guide_window_plus_the_torch_and_link_reserves() {
        assertEquals(224.dp, MinScanAreaHeight)
        assertTrue(scanAreaTooSmall(223.dp))
        assertFalse(scanAreaTooSmall(224.dp))
    }

    @Test
    fun a_short_area_shows_the_notice_and_never_binds_the_camera() {
        size = DpSize(360.dp, 120.dp)
        setGate()

        composeRule.onNodeWithTag(WINDOW_TOO_SMALL_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Window too small to scan").assertIsDisplayed()
        composeRule.onNodeWithTag(VIEWFINDER_TEST_TAG).assertDoesNotExist()
        assertTrue(made.isEmpty(), "no scanner may be made while the notice shows")
    }

    @Test
    fun an_area_one_dp_under_the_threshold_shows_the_notice_and_one_at_it_shows_the_camera() {
        size = DpSize(360.dp, MinScanAreaHeight - 1.dp)
        setGate()
        composeRule.onNodeWithTag(WINDOW_TOO_SMALL_TEST_TAG).assertIsDisplayed()

        size = DpSize(360.dp, MinScanAreaHeight)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(WINDOW_TOO_SMALL_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(VIEWFINDER_TEST_TAG).assertExists()
    }

    @Test
    fun growing_the_window_brings_the_camera_back_by_itself() {
        size = DpSize(360.dp, 120.dp)
        setGate()
        assertTrue(made.isEmpty())

        size = DpSize(360.dp, 480.dp)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(WINDOW_TOO_SMALL_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(VIEWFINDER_TEST_TAG).assertExists()
        assertEquals(1, bound.size, "exactly one scanner runs once the window is large enough")
    }

    @Test
    fun shrinking_the_window_unbinds_the_camera_and_growing_it_starts_a_fresh_one() {
        size = DpSize(360.dp, 480.dp)
        setGate()
        val first = made.single()
        assertTrue(first.started)

        size = DpSize(360.dp, 120.dp)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(WINDOW_TOO_SMALL_TEST_TAG).assertIsDisplayed()
        assertTrue(first.closed, "the camera must not run while the notice shows")
        assertTrue(bound.isEmpty())

        size = DpSize(360.dp, 480.dp)
        composeRule.waitForIdle()
        assertEquals(2, made.size)
        assertEquals(1, bound.size)
        assertTrue(made.last().started)
    }

    @Test
    fun the_notice_title_is_a_polite_live_region() {
        setGate()

        composeRule
            .onNode(
                hasText("Window too small to scan") and
                    SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
            ).assertExists()
    }

    @Test
    fun manual_entry_is_offered_only_when_enabled() {
        setGate(showManualEntry = false)

        composeRule.onNodeWithTag(WINDOW_TOO_SMALL_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Enter details manually").assertDoesNotExist()
    }

    @Test
    fun in_a_short_window_close_and_manual_entry_stay_reachable() {
        // The whole scanner chrome in a short window: top bar, method switcher and footer leave the camera area
        // well under the threshold.
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                Box(Modifier.size(DpSize(360.dp, 320.dp))) {
                    ScannerScaffold(
                        enabledMethods = setOf(ScanMethod.CAMERA, ScanMethod.SAVED_IMAGE, ScanMethod.MANUAL_ENTRY),
                        currentState = ScannerUiState.Scanning(),
                        onClose = { closes++ },
                        onSelectMethod = {},
                    ) {
                        ScanAreaSizeGate(
                            onManualEntry = { manualEntries++ },
                            showManualEntry = true,
                            camera = fakeCamera,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(WINDOW_TOO_SMALL_TEST_TAG).assertExists()
        assertTrue(made.isEmpty())

        composeRule.onNodeWithTag(CLOSE_TEST_TAG).assertIsDisplayed().performClick()
        assertEquals(1, closes)

        composeRule
            .onNodeWithText("Enter details manually")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, manualEntries)

        composeRule.onNodeWithTag(SWITCHER_TEST_TAG).assertIsDisplayed()
    }
}
