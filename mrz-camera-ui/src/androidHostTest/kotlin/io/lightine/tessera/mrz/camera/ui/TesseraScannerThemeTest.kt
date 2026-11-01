package io.lightine.tessera.mrz.camera.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Host-side test for [TesseraScannerTheme]'s `brandColor` contrast handling, run on the JVM via Robolectric
 * (no device). Locks the fix for the brandColor/onPrimary contrast gap: a consumer's arbitrary tint (e.g. the
 * documented example `0xFF00695C` dark teal) must not leave a dark label on a dark container or dark-on-dark
 * text — [onPrimary] is derived from the tint's own relative luminance rather than kept at a fixed value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TesseraScannerThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun a_light_brand_color_pairs_with_dark_on_primary() {
        var onPrimaryLuminance = -1f
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig { theme { brandColor = 0xFFF5F5F5L } }.theme) {
                onPrimaryLuminance = MaterialTheme.colorScheme.onPrimary.luminance()
            }
        }
        assertTrue(onPrimaryLuminance < 0.5f, "a near-white brand tint must pair with dark text for legibility")
    }

    @Test
    fun a_dark_brand_color_pairs_with_light_on_primary() {
        var onPrimaryLuminance = -1f
        composeRule.setContent {
            // The documented example brandColor (MrzScannerConfig KDoc) — a dark teal.
            TesseraScannerTheme(MrzScannerConfig { theme { brandColor = 0xFF00695CL } }.theme) {
                onPrimaryLuminance = MaterialTheme.colorScheme.onPrimary.luminance()
            }
        }
        assertTrue(onPrimaryLuminance > 0.5f, "a dark brand tint must pair with light text for legibility")
    }

    @Test
    fun no_brand_color_resolves_without_touching_on_primary_derivation() {
        // No brandColor → the baseline scheme's own primary/onPrimary pair (already contrast-tuned by
        // Material3); this just locks that the null path still resolves a usable theme.
        var resolved = false
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                resolved = true
            }
        }
        assertTrue(resolved, "the default (no brand accent) theme must resolve")
    }
}
