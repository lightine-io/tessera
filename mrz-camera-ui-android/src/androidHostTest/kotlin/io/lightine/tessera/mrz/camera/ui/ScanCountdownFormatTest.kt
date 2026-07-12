package io.lightine.tessera.mrz.camera.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * TES-125: locks [formatCountdown] — the pure `M:SS` formatting behind the scan-timeout countdown chip. Seconds
 * round UP (the chip reads `0:01` through the final second and shows `0:00` only at true expiry), minutes are not
 * capped at two digits, and a negative input clamps to `0:00`.
 */
class ScanCountdownFormatTest {
    @Test
    fun `formats minutes and zero-padded seconds`() {
        assertEquals("1:30", formatCountdown(90.seconds))
        assertEquals("1:01", formatCountdown(61.seconds))
        assertEquals("0:05", formatCountdown(5.seconds))
        assertEquals("0:00", formatCountdown(0.seconds))
    }

    @Test
    fun `does not cap minutes at two digits`() {
        assertEquals("10:00", formatCountdown(10.minutes))
        assertEquals("100:00", formatCountdown(100.minutes))
    }

    @Test
    fun `rounds seconds up so the last whole second still shows`() {
        // 4.5s left still reads 0:05 (rounds up), not 0:04 — the chip only shows 0:00 at true expiry.
        assertEquals("0:05", formatCountdown(4500.milliseconds))
        assertEquals("0:01", formatCountdown(1.milliseconds))
        assertEquals("1:00", formatCountdown(59500.milliseconds))
    }

    @Test
    fun `clamps a negative duration to zero`() {
        assertEquals("0:00", formatCountdown((-5).seconds))
    }
}
