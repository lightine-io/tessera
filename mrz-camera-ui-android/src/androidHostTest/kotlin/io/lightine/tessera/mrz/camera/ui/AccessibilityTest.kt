package io.lightine.tessera.mrz.camera.ui

import androidx.compose.material3.Text
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
import kotlin.time.Instant

/**
 * Cross-screen accessibility gate for the default scanner UI (TES-47). The sibling
 * [MrzScannerScreenA11yTest] covers the permission Grant screen; this suite consolidates the a11y
 * additions that span the *other* now-built screens: live-region announcements on the auto-transition /
 * decode-landing status text, the interactive controls on the review / saved-image-empty / method switcher
 * carrying the `Button` role and a non-empty label, and the top-bar ✕ carrying its "Close" label.
 *
 * **Why semantics assertions, not `enableAccessibilityChecks()`** — the Accessibility Test Framework is a
 * verified no-op under Robolectric (it needs a rendered, measured view hierarchy Robolectric does not
 * provide), so these read the Compose semantics tree directly, which Robolectric *does* populate. That
 * covers label / role / structure / live-region regressions — the common a11y failures. It does **not**
 * cover **contrast** or **measured touch-target size** (visual checks needing real rendering): those stay a
 * device-side concern and are deliberately not asserted here. (See [MrzScannerScreenA11yTest] for the full
 * rationale; TES-A-70.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    // Fixed reference time so the specimen parses deterministically (mirrors the sibling screen tests).
    private val referenceTime = Instant.parse("1994-01-01T00:00:00Z")
    private val specimenLine1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    private val specimenLine2 = "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"

    private fun decoded(): MrzScanResult.Decoded =
        MrzScanResult.Decoded(
            parse = MrzParser.parse(listOf(specimenLine1, specimenLine2), referenceTime = referenceTime),
            recognizedText = RecognizedText(listOf(RecognizedLine(specimenLine1, null), RecognizedLine(specimenLine2, null))),
            quality = ScanQuality(mrzRegionFound = true, ocrConfidence = null, recognizedLineCount = 2),
        )

    private fun isLiveRegion(mode: LiveRegionMode): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, mode)

    // ----------------------------------------------------------------------------------------------------
    // Live regions on auto-transition / decode-landing status text
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun review_decode_landing_title_is_an_assertive_live_region() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ReviewContent(decoded = decoded(), expanded = false, onToggleExpanded = {}, onUse = {}, onRescan = {})
                }
            }
        }

        // "Check the details" is announced on arrival (the review screen is the decode-landing) — Assertive.
        composeRule
            .onNode(hasText("Check the details") and isLiveRegion(LiveRegionMode.Assertive))
            .assertIsDisplayed()
    }

    @Test
    fun read_failed_landing_title_is_an_assertive_live_region() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ReadFailedContent(
                        capturedText = RecognizedText(listOf(RecognizedLine("GARBLED<<TEXT", null))),
                        onTryAgain = {},
                        onManualEntry = {},
                    )
                }
            }
        }

        composeRule
            .onNode(hasText("Couldn't read this MRZ") and isLiveRegion(LiveRegionMode.Assertive))
            .assertIsDisplayed()
    }

    @Test
    fun initializing_status_is_a_polite_live_region() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    InitializingContent()
                }
            }
        }

        // The initializing state shows the instant the preview mounts, before the first surface arrives (an
        // auto-transition — mockup 01b), so its title is announced without the user moving focus — Polite.
        composeRule
            .onNode(hasText("Starting camera…") and isLiveRegion(LiveRegionMode.Polite))
            .assertIsDisplayed()
    }

    @Test
    fun analyzing_photo_status_is_a_polite_live_region() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    SavedImageAnalyzingContent()
                }
            }
        }

        // The analyzing screen appears the moment a photo is picked (an auto-transition) — Polite.
        composeRule
            .onNode(hasText("Analyzing photo…") and isLiveRegion(LiveRegionMode.Polite))
            .assertIsDisplayed()
    }

    @Test
    fun camera_in_use_reconnecting_status_is_a_polite_live_region() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    CameraInUseContent(onManualEntry = {})
                }
            }
        }

        // The camera-in-use screen arrives via the flow reducer (another app grabbed the camera) — Polite.
        composeRule
            .onNode(hasText("Waiting to reconnect…") and isLiveRegion(LiveRegionMode.Polite))
            .assertIsDisplayed()
    }

    @Test
    fun camera_unavailable_landing_title_is_an_assertive_live_region() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    CameraUnavailableContent(onManualEntry = {})
                }
            }
        }

        // Terminal, reached purely by auto-transition — Assertive, mirroring ReadFailed / Review.
        composeRule
            .onNode(hasText("Camera unavailable") and isLiveRegion(LiveRegionMode.Assertive))
            .assertIsDisplayed()
    }

    @Test
    fun saved_image_empty_landing_title_is_an_assertive_live_region() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    SavedImageEmptyContent(onChooseDifferent = {}, onManualEntry = {})
                }
            }
        }

        // Terminal, reached purely by auto-transition (a picked photo yielded no MRZ) — Assertive.
        composeRule
            .onNode(hasText("No MRZ found in this photo") and isLiveRegion(LiveRegionMode.Assertive))
            .assertIsDisplayed()
    }

    @Test
    fun struggling_hint_is_a_polite_live_region() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    StrugglingHint(onManualEntry = {})
                }
            }
        }

        // The struggle hint overlays the preview via the struggle-timeout auto-transition (mockup 02) — Polite.
        composeRule
            .onNode(hasText("Can't read it yet. Try more light or move the document farther away.") and isLiveRegion(LiveRegionMode.Polite))
            .assertIsDisplayed()
    }

    @Test
    fun gathering_hint_is_a_polite_live_region() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    GatheringHint()
                }
            }
        }

        // The gathering cue overlays the preview when a read starts confirming across frames (an auto-transition,
        // not a focus move) — Polite, so it is announced on appearance.
        composeRule
            .onNode(hasText("Hold steady…") and isLiveRegion(LiveRegionMode.Polite))
            .assertIsDisplayed()
    }

    // ----------------------------------------------------------------------------------------------------
    // Interactive controls carry the Button role + a non-empty label
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun review_controls_are_labeled_buttons() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ReviewContent(decoded = decoded(), expanded = false, onToggleExpanded = {}, onUse = {}, onRescan = {})
                }
            }
        }

        // The two pinned actions announce as labeled Buttons — the primary stays enabled and reachable.
        listOf("Use this result", "Rescan").forEach { label ->
            composeRule
                .onNode(hasText(label) and hasClickAction())
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        }
    }

    @Test
    fun saved_image_empty_controls_are_labeled_buttons() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    SavedImageEmptyContent(onChooseDifferent = {}, onManualEntry = {})
                }
            }
        }

        // The "Choose different photo" and "Enter details manually" actions are Buttons.
        listOf("Choose different photo", "Enter details manually").forEach { label ->
            composeRule
                .onNode(hasText(label, substring = true) and hasClickAction())
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        }
    }

    @Test
    fun close_x_carries_its_close_label_and_button_role() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ScannerScaffold(
                        enabledMethods = config.enabledMethods,
                        currentState = ScannerUiState.Review(decoded(), source = ScanMethod.CAMERA),
                        onClose = {},
                        onSelectMethod = {},
                    ) { Text("body") }
                }
            }
        }

        // The glyph ✕ is not a label; the spoken "Close" contentDescription is, and the IconButton is a Button.
        composeRule
            .onNodeWithContentDescription("Close")
            .assert(hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun method_switcher_chips_carry_labels_and_selected_state() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ScannerScaffold(
                        enabledMethods = setOf(ScanMethod.CAMERA, ScanMethod.SAVED_IMAGE, ScanMethod.MANUAL_ENTRY),
                        currentState = ScannerUiState.Scanning(),
                        onClose = {},
                        onSelectMethod = {},
                    ) { Text("body") }
                }
            }
        }

        // Each chip is a labeled, clickable control. The active method carries the `Selected` semantics — a
        // non-colour cue for which method is current (so the selection never depends on colour alone).
        composeRule.onNode(hasText("Camera") and hasClickAction()).assertIsDisplayed()
        composeRule
            .onNode(hasText("Camera") and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        composeRule
            .onNode(hasText("Photo") and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }
}
