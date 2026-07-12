package io.lightine.tessera.mrz.camera.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes

/**
 * TES-125: the scan-countdown chip is shared chrome, gated purely on whether the host enabled a finite
 * `scanTimeout`. It shows on every screen when finite and is absent when `INFINITE` (the default). Rendered
 * here on the permission-gate branch (no camera on the host, per the testing-layers rule); the scaffold — and
 * so the chip — wraps every screen, so this branch is sufficient to prove the gating. A generous 5-minute
 * timeout keeps the deadline from firing during the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanCountdownVisibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun shows_countdown_chip_when_scanTimeout_is_finite() {
        composeRule.setContent {
            MrzScannerScreen(config = MrzScannerConfig { scanTimeout = 5.minutes }, onResult = {})
        }

        composeRule.onNodeWithTag(COUNTDOWN_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun no_countdown_chip_when_scanTimeout_is_infinite() {
        // Default config leaves scanTimeout at INFINITE (off) — no host deadline, so no countdown anywhere.
        composeRule.setContent {
            MrzScannerScreen(config = MrzScannerConfig(), onResult = {})
        }

        composeRule.onNodeWithTag(COUNTDOWN_TEST_TAG).assertDoesNotExist()
    }
}
