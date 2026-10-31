package io.lightine.tessera.mrz.camera.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Host-side tests for the shared navigation scaffold and the method switcher (TES-71) — the slice that ties
 * the reading methods together. Run on the JVM via Robolectric (no device), same shape as the sibling screen
 * tests.
 *
 * Two layers, per the testing-layers rule:
 *  * the **pure** helpers ([initialState], [switcherMethods], [showsMethodSwitcher], [activeMethod]) are
 *    unit-tested directly for every relevant [ScanMethod] combination — no Compose, no device;
 *  * the **Compose** chrome ([ScannerScaffold] / [MethodSwitcher]) is driven directly with a stub body, so
 *    the ✕-cancel (now reachable on every screen, including screens that previously lacked it) and the
 *    switcher's visibility / switch wiring are verified without needing a live camera.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MethodSwitcherTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val camera = ScanMethod.CAMERA
    private val photo = ScanMethod.SAVED_IMAGE
    private val type = ScanMethod.MANUAL_ENTRY

    // ----------------------------------------------------------------------------------------------------
    // Pure helpers — initialState / switcherMethods / showsMethodSwitcher / activeMethod
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun initial_state_prefers_camera_then_manual_then_saved_image() {
        // Camera wins whenever present (the default primary method).
        assertTrue(initialState(setOf(camera)) is ScannerUiState.Scanning)
        assertTrue(initialState(setOf(camera, type)) is ScannerUiState.Scanning)
        assertTrue(initialState(setOf(camera, photo, type)) is ScannerUiState.Scanning)
        assertTrue(initialState(setOf(camera, photo)) is ScannerUiState.Scanning)

        // No camera → manual entry is the next preference.
        assertTrue(initialState(setOf(type)) is ScannerUiState.ManualRaw)
        assertTrue(initialState(setOf(type, photo)) is ScannerUiState.ManualRaw)

        // Only saved image → the await-pick launcher state (the flow launches the picker on entering it).
        assertEquals(ScannerUiState.AwaitingSavedImagePick, initialState(setOf(photo)))

        // Empty set → treated as camera defensively (the UI must still show something).
        assertTrue(initialState(emptySet()) is ScannerUiState.Scanning)
    }

    @Test
    fun switcher_methods_are_only_the_enabled_ones_in_fixed_order() {
        // Fixed display order is camera, photo, type — regardless of the set's own iteration order.
        assertEquals(
            listOf(camera, photo, type),
            switcherMethods(setOf(type, photo, camera)),
            "order is always camera, photo, type — not the set's iteration order",
        )
        assertEquals(listOf(camera, type), switcherMethods(setOf(camera, type)))
        assertEquals(listOf(photo, type), switcherMethods(setOf(type, photo)))
        assertEquals(listOf(camera), switcherMethods(setOf(camera)))
        assertEquals(emptyList(), switcherMethods(emptySet()))
    }

    @Test
    fun switcher_shows_only_on_capture_and_entry_states() {
        // Capture / entry screens — the switcher shows.
        assertTrue(showsMethodSwitcher(ScannerUiState.Scanning()))
        assertTrue(showsMethodSwitcher(ScannerUiState.ManualRaw()))
        assertTrue(showsMethodSwitcher(ScannerUiState.AwaitingSavedImagePick))
        assertTrue(showsMethodSwitcher(ScannerUiState.SavedImageEmpty))

        // Outcome / gate screens — the switcher is hidden (switching mid-outcome would be a confusing detour).
        assertFalse(showsMethodSwitcher(ScannerUiState.Review(fakeDecoded(), source = ScanMethod.CAMERA)))
        assertFalse(showsMethodSwitcher(ScannerUiState.Review(fakeDecoded(), source = ScanMethod.CAMERA, expanded = true)))
        assertFalse(showsMethodSwitcher(ScannerUiState.ReadFailed(RecognizedText(emptyList()))))
        assertFalse(showsMethodSwitcher(ScannerUiState.CameraInUse))
        assertFalse(showsMethodSwitcher(ScannerUiState.CameraUnavailable))
        assertFalse(showsMethodSwitcher(ScannerUiState.SavedImageAnalyzing))
    }

    @Test
    fun active_method_maps_the_capture_state_to_its_chip() {
        assertEquals(camera, activeMethod(ScannerUiState.Scanning()))
        assertEquals(type, activeMethod(ScannerUiState.ManualRaw()))
        assertEquals(photo, activeMethod(ScannerUiState.AwaitingSavedImagePick))
        assertEquals(photo, activeMethod(ScannerUiState.SavedImageEmpty))
        assertEquals(null, activeMethod(ScannerUiState.Review(fakeDecoded(), source = ScanMethod.CAMERA)))
    }

    // ----------------------------------------------------------------------------------------------------
    // Scaffold ✕ cancel — reachable on EVERY screen, including ones that previously lacked it (Review)
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun close_x_is_present_and_fires_close_from_a_screen_that_previously_lacked_a_cancel() {
        // The Review screen previously had only "Use this result" / "Rescan" — no cancel. The shared top bar
        // now owns a ✕ that fires onClose (the flow maps it to Cancelled(USER_DISMISSED)) from every screen.
        var closed = false
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ScannerScaffold(
                        enabledMethods = config.enabledMethods,
                        currentState = ScannerUiState.Review(fakeDecoded(), source = ScanMethod.CAMERA),
                        onClose = { closed = true },
                        onSelectMethod = {},
                    ) {
                        // Stand-in for the Review body; the point is the ✕ in the shared top bar above it.
                        Text("review body")
                    }
                }
            }
        }

        // The ✕ carries the spoken "Close" label (the glyph itself is not a label).
        composeRule.onNodeWithTag(CLOSE_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close").performClick()
        assertTrue(closed, "the top-bar ✕ must fire onClose from the Review screen (which previously had no cancel)")

        // And on the Review screen the switcher is NOT shown (it is an outcome screen).
        composeRule.onNodeWithTag(SWITCHER_TEST_TAG).assertDoesNotExist()
    }

    // ----------------------------------------------------------------------------------------------------
    // Switcher visibility — exactly the enabled methods, hidden when fewer than two
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun switcher_shows_exactly_the_enabled_methods_on_a_capture_screen() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ScannerScaffold(
                        enabledMethods = setOf(camera, photo, type),
                        currentState = ScannerUiState.ManualRaw(),
                        onClose = {},
                        onSelectMethod = {},
                    ) { Text("body") }
                }
            }
        }

        composeRule.onNodeWithTag(SWITCHER_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Camera").assertIsDisplayed()
        composeRule.onNodeWithText("Photo").assertIsDisplayed()
        composeRule.onNodeWithText("Manual").assertIsDisplayed()
    }

    @Test
    fun switcher_is_hidden_when_fewer_than_two_methods_are_enabled() {
        // Only camera enabled → nothing to switch between → the switcher renders nothing.
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ScannerScaffold(
                        enabledMethods = setOf(camera),
                        currentState = ScannerUiState.Scanning(),
                        onClose = {},
                        onSelectMethod = {},
                    ) { Text("body") }
                }
            }
        }

        composeRule.onNodeWithTag(SWITCHER_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Camera").assertDoesNotExist()
    }

    // ----------------------------------------------------------------------------------------------------
    // Switcher switching — tapping a chip selects the corresponding method
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun tapping_a_chip_fires_select_with_its_method() {
        val selected = mutableListOf<ScanMethod>()
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ScannerScaffold(
                        enabledMethods = setOf(camera, photo, type),
                        currentState = ScannerUiState.Scanning(),
                        onClose = {},
                        onSelectMethod = { selected += it },
                    ) { Text("body") }
                }
            }
        }

        composeRule.onNodeWithText("Manual").performClick()
        composeRule.onNodeWithText("Photo").performClick()
        composeRule.onNodeWithText("Camera").performClick()
        assertEquals(
            listOf(ScanMethod.MANUAL_ENTRY, ScanMethod.SAVED_IMAGE, ScanMethod.CAMERA),
            selected,
            "each chip fires onSelectMethod with the method it represents (Camera / Photo / Type labels)",
        )
    }

    // ----------------------------------------------------------------------------------------------------
    // TES-95 — the switcher stays one consistent height at a large system font size: "Manual" must not wrap
    // mid-word and grow taller than its siblings.
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun switcher_segments_stay_single_line_at_a_large_font_scale() {
        composeRule.setContent {
            val bumped = Density(density = LocalDensity.current.density, fontScale = 1.5f)
            CompositionLocalProvider(LocalDensity provides bumped) {
                MrzScannerConfig().let { config ->
                    TesseraScannerTheme(config.theme) {
                        ScannerScaffold(
                            enabledMethods = setOf(camera, photo, type),
                            currentState = ScannerUiState.Scanning(),
                            onClose = {},
                            onSelectMethod = {},
                        ) { Text("body") }
                    }
                }
            }
        }

        // "Manual" must render as tall as "Camera" — if it wrapped mid-word onto a second line, its measured
        // height would be roughly double, breaking the switcher's one consistent height.
        val manualHeight =
            composeRule
                .onNodeWithText("Manual")
                .fetchSemanticsNode()
                .boundsInRoot.height
        val cameraHeight =
            composeRule
                .onNodeWithText("Camera")
                .fetchSemanticsNode()
                .boundsInRoot.height
        assertTrue(
            abs(manualHeight - cameraHeight) < 2f,
            "at fontScale 1.5 'Manual' (height=$manualHeight) must stay the same single-line height as " +
                "'Camera' (height=$cameraHeight), not wrap onto a second line",
        )
    }

    // ----------------------------------------------------------------------------------------------------
    // TES-95 follow-up — scroll-when-overflow, center-when-fit: at the default font scale the switcher is
    // genuinely centred and not scrollable; once the segments outgrow the available width (a large font
    // scale), it becomes horizontally scrollable instead of clipping (the old maxLines=1/softWrap=false bug
    // that chopped the trailing "l" off "Manual") or wrapping.
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun switcher_is_not_scrollable_and_is_centred_at_the_default_font_scale() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ScannerScaffold(
                        enabledMethods = setOf(camera, photo, type),
                        currentState = ScannerUiState.Scanning(),
                        onClose = {},
                        onSelectMethod = {},
                    ) { Text("body") }
                }
            }
        }

        // The scrollable row's horizontal scroll range collapses to zero once its content already fits —
        // nothing to scroll through, so it must not report itself as scrollable.
        val maxScroll = switcherHorizontalScrollMax()
        assertEquals(
            0f,
            maxScroll,
            "the switcher must not be scrollable when its segments already fit the available width",
        )

        // Genuinely centred, not merely start-aligned with all the leftover space trailing on the right: the
        // gap before the first chip (Camera) must match the gap after the last chip (Manual).
        val switcherBounds = composeRule.onNodeWithTag(SWITCHER_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val firstChipLeft =
            composeRule
                .onNodeWithText("Camera")
                .fetchSemanticsNode()
                .boundsInRoot.left
        val lastChipRight =
            composeRule
                .onNodeWithText("Manual")
                .fetchSemanticsNode()
                .boundsInRoot.right
        val leadingGap = firstChipLeft - switcherBounds.left
        val trailingGap = switcherBounds.right - lastChipRight
        assertTrue(
            abs(leadingGap - trailingGap) < 2f,
            "the switcher must be genuinely centred (leading gap=$leadingGap, trailing gap=$trailingGap), " +
                "not start-aligned with the leftover space on one side",
        )
    }

    @Test
    fun switcher_scrolls_without_clipping_once_segments_overflow_the_available_width() {
        // TES-86 reported real clipping at fontScale 1.5 on a physical device. Reproducing that here by
        // bumping fontScale turns out not to be reliable: verified empirically that Robolectric's text
        // measurement in this project's setup does not scale meaningfully with fontScale (a fixed string's
        // measured width came back identical at fontScale 1x and 5x). A constrained available width exercises
        // the exact same scroll-vs-clip code path deterministically, independent of that Robolectric font-
        // metrics limitation — the switcher does not know or care *why* its content doesn't fit, only *that*
        // it doesn't, so this is an equally faithful way to drive the behaviour under test.
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    Box(modifier = Modifier.width(120.dp)) {
                        MethodSwitcher(
                            enabledMethods = setOf(camera, photo, type),
                            currentState = ScannerUiState.Scanning(),
                            onSelectMethod = {},
                        )
                    }
                }
            }
        }

        // The segments no longer fit the available width, so the row must be scrollable rather than clipping.
        val maxScroll = switcherHorizontalScrollMax()
        assertTrue(
            maxScroll > 0f,
            "the switcher must become scrollable once its segments overflow the available width " +
                "(maxScroll=$maxScroll)",
        )

        // Every label is still fully laid out (reachable by scrolling) — none dropped or clipped out of the
        // tree. assertExists (not assertIsDisplayed) is the right check here: a label scrolled out of the
        // current viewport is legitimately off-screen, exactly like the tail of any scrollable list, which is
        // the whole point — it is reachable via scroll rather than being clipped away or never laid out.
        composeRule.onNodeWithText("Camera").assertExists()
        composeRule.onNodeWithText("Photo").assertExists()
        composeRule.onNodeWithText("Manual").assertExists()
    }

    /** The switcher's horizontal-scroll node's scroll range ceiling — 0f means "nothing to scroll through". */
    private fun switcherHorizontalScrollMax(): Float =
        composeRule
            .onNode(hasScrollAction())
            .fetchSemanticsNode()
            .config[SemanticsProperties.HorizontalScrollAxisRange]
            .maxValue()

    private fun fakeDecoded(): MrzScanResult.Decoded {
        val line1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
        val line2 = "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"
        return MrzScanResult.Decoded(
            parse = MrzParser.parse(listOf(line1, line2), referenceTime = Instant.parse("1994-01-01T00:00:00Z")),
            recognizedText = RecognizedText(listOf(RecognizedLine(line1, null), RecognizedLine(line2, null))),
            quality = ScanQuality(mrzRegionFound = true, ocrConfidence = null, recognizedLineCount = 2),
        )
    }
}
