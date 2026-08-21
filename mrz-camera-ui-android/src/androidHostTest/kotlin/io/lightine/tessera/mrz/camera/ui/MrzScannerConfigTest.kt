package io.lightine.tessera.mrz.camera.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Pure-JVM unit tests for [MrzScannerConfig] and its DSL builder — no Android runtime needed, so they run
 * as ordinary host tests. The defaults test pins the SDK's conservative posture: the default UI must not
 * opt a consumer into anything (dynamic color off, no brand, no permission side effects, no forced
 * timeout) without their say-so. The builder test proves every option is carried verbatim.
 */
class MrzScannerConfigTest {
    @Test
    fun defaults_are_conservative() {
        val config = MrzScannerConfig()

        assertEquals(
            setOf(ScanMethod.CAMERA, ScanMethod.MANUAL_ENTRY),
            config.enabledMethods,
            "saved-image is opt-in (scope TES-A-62): off by default",
        )
        assertEquals(ReviewMode.REVIEW, config.reviewMode, "review is the default; instant-return is opt-in")
        assertTrue(config.showTorchButton, "torch toggle shown by default")
        assertFalse(config.torchOnByDefault, "torch off by default")
        assertEquals(10.seconds, config.struggleTimeout)
        assertEquals(Duration.INFINITE, config.scanTimeout, "no scan timeout by default (kiosk-friendly)")
        assertEquals(2, config.consensusReads, "2 agreeing frames by default (transient-misread guard)")
        assertTrue(config.hapticFeedback, "confirming haptic on by default (honours the system haptic setting)")
        assertFalse(config.theme.useDynamicColor, "dynamic color OFF by default (module-own fixed scheme)")
        assertNull(config.theme.brandColor, "no brand accent by default")
        assertEquals(DarkMode.AUTO, config.theme.darkMode, "dark mode follows the system by default")
        assertNull(config.onRequestPermission, "no permission handler by default — the host owns the request")
    }

    @Test
    fun builder_carries_options_verbatim() {
        val handler: () -> Unit = {}
        val config =
            MrzScannerConfig {
                enabledMethods = setOf(ScanMethod.CAMERA, ScanMethod.MANUAL_ENTRY)
                reviewMode = ReviewMode.INSTANT_RETURN
                showTorchButton = false
                torchOnByDefault = true
                struggleTimeout = 5.seconds
                scanTimeout = 30.seconds
                consensusReads = 5
                hapticFeedback = false
                theme {
                    brandColor = 0xFF00695CL
                    darkMode = DarkMode.ON
                    useDynamicColor = true
                }
                onRequestPermission = handler
            }

        assertEquals(setOf(ScanMethod.CAMERA, ScanMethod.MANUAL_ENTRY), config.enabledMethods)
        assertEquals(ReviewMode.INSTANT_RETURN, config.reviewMode)
        assertFalse(config.showTorchButton)
        assertTrue(config.torchOnByDefault)
        assertEquals(5.seconds, config.struggleTimeout)
        assertEquals(30.seconds, config.scanTimeout)
        assertEquals(5, config.consensusReads)
        assertFalse(config.hapticFeedback)
        assertEquals(0xFF00695CL, config.theme.brandColor)
        assertEquals(DarkMode.ON, config.theme.darkMode)
        assertTrue(config.theme.useDynamicColor)
        assertEquals(handler, config.onRequestPermission)
    }

    @Test
    fun consensusReads_below_one_fails_to_build() {
        assertFailsWith<IllegalArgumentException> {
            MrzScannerConfig { consensusReads = 0 }
        }
    }

    @Test
    fun consensusReads_of_one_builds() {
        val config = MrzScannerConfig { consensusReads = 1 }

        assertEquals(1, config.consensusReads)
    }
}
