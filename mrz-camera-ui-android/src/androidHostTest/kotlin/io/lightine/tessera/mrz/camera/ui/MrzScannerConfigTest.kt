package io.lightine.tessera.mrz.camera.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM unit tests for [MrzScannerConfig] — no Android runtime needed, so they run as ordinary host
 * tests. The default-values test pins the SDK's conservative posture: the default UI must not opt a
 * consumer into anything (dynamic color off, no permission side effects) without their say-so.
 */
class MrzScannerConfigTest {
    @Test
    fun defaults_are_conservative() {
        val config = MrzScannerConfig()

        assertFalse(config.useDynamicColor, "dynamic color must be OFF by default (module-own fixed scheme)")
        assertNull(config.onRequestPermission, "no permission handler by default — the host owns the request")
    }

    @Test
    fun options_are_carried_verbatim() {
        val handler: () -> Unit = {}
        val config = MrzScannerConfig(useDynamicColor = true, onRequestPermission = handler)

        assertTrue(config.useDynamicColor)
        assertNotNull(config.onRequestPermission)
    }
}
