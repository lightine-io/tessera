// @Composable functions are PascalCase by Compose convention — see the note in MrzScannerScreen.kt.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.width
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Host-side test for the adaptive-layout foundation ([Modifier.contentMaxWidth], TES-78): the single
 * content-width cap that keeps the single-pane scanner screens readable on a wide screen (tablet / unfolded
 * foldable) instead of stretching their rows and full-width buttons edge-to-edge. This is the *foundation*
 * only — a centred, width-capped single column, NOT a multi-pane / list-detail tablet build (that is deferred,
 * TES-77).
 *
 * The full tablet/foldable rendering (fold posture, real window insets) is a device/config concern not fully
 * host-assertable, but the cap's *own* behaviour is: each test drives Robolectric with a device-width
 * qualifier (a wide "tablet" window vs a narrow "phone" one) and measures the wrapped content, asserting the
 * two behaviours that carry the whole foundation:
 *  * on a window **wider** than the cap, the wrapped content measures at [ContentMaxWidth] — the cap bites, so
 *    content does not span the whole width (no edge-to-edge stretch on a tablet);
 *  * on a window **narrower** than the cap (a phone), the wrapper is a no-op — the content fills the available
 *    width, so the phone appearance is unchanged.
 *
 * The per-screen tests ([ReviewScreenTest], [PermissionScreenTest], [CameraStatusScreenTest], etc.) already
 * render every screen under the wrapper; [a_capped_screen_still_renders_its_content] adds one explicit
 * wide-window smoke check that a screen still composes with the cap applied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdaptiveLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        const val CAPPED_TAG = "adaptive-capped-content"
    }

    /** A capped box filled by a full-width child (like a screen's fillMaxWidth buttons), tagged for measuring. */
    @Composable
    private fun CappedProbe() {
        Box(modifier = Modifier.contentMaxWidth().testTag(CAPPED_TAG)) {
            // A full-width child so the capped box is driven to its max; without one, widthIn(max) would let
            // it shrink to the text and the measurement would prove nothing.
            Box(modifier = Modifier.fillMaxWidth()) { Text("content") }
        }
    }

    @Test
    @Config(qualifiers = "w1200dp-h800dp")
    fun caps_content_width_on_a_wide_screen() {
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) { CappedProbe() }
        }

        // On a 1200dp-wide window the wrapped content must be capped to ContentMaxWidth (480dp), NOT the full
        // 1200dp — that is exactly what stops the rows/buttons stretching edge-to-edge on a tablet.
        val width = composeRule.onNodeWithTag(CAPPED_TAG).getUnclippedBoundsInRoot().width
        assertTrue(
            width.value in (ContentMaxWidth.value - 1f)..(ContentMaxWidth.value + 1f),
            "on a 1200dp window the capped content should measure ~${ContentMaxWidth.value.toInt()}dp, " +
                "was ${width.value}dp",
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun is_a_no_op_narrower_than_the_cap_so_the_phone_layout_is_unchanged() {
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) { CappedProbe() }
        }

        // On a 360dp-wide window (a phone) the cap never bites: the content fills the full width, so the phone
        // appearance is identical to before the wrapper was introduced.
        val width = composeRule.onNodeWithTag(CAPPED_TAG).getUnclippedBoundsInRoot().width
        assertTrue(
            width.value in 359f..361f,
            "on a 360dp window the content should fill the width (cap is a no-op), was ${width.value}dp",
        )
    }

    @Test
    @Config(qualifiers = "w1200dp-h800dp")
    fun a_capped_screen_still_renders_its_content() {
        // A representative content screen renders under the wrapper on a wide window (the cap is applied inside
        // its column) — a smoke check that applying contentMaxWidth did not break a screen's composition.
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                CameraUnavailableContent(onManualEntry = {})
            }
        }

        composeRule.onNodeWithTag(CAMERA_UNAVAILABLE_TEST_TAG).assertIsDisplayed()
    }
}
