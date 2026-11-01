package io.lightine.tessera.mrz.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Host test (JVM, no device) for [MlKitMrzTextRecognizer.mrzBandRect] — the pure band-rectangle math behind
 * the MRZ region-of-interest crop (TES-86). The crop itself (`ImageProxy.toBitmap()` → rotate → crop →
 * `InputImage.fromBitmap`) needs a real camera frame and is device-verified; only the band geometry is pure,
 * so it is locked here: the band is the bottom [MlKitMrzTextRecognizer.MRZ_BAND_HEIGHT_FRACTION] of the
 * (upright) frame and reaches the very bottom edge (where the MRZ sits on a document held upright). Derives
 * the expectation from the constant so retuning the band fraction on-device does not break the test.
 */
class MlKitMrzBandRectTest {
    @Test
    fun band_is_the_configured_fraction_centred_vertically() {
        val frame = 1000
        val (top, height) = MlKitMrzTextRecognizer.mrzBandRect(frame)
        val expected = (frame * MlKitMrzTextRecognizer.MRZ_BAND_HEIGHT_FRACTION).toInt()

        assertEquals(expected, height, "band height is the configured fraction")
        assertEquals((frame - expected) / 2, top, "band is centred vertically")
        // Equal margins above and below (within integer rounding) — the band sits in the middle.
        val marginBelow = frame - (top + height)
        assertTrue(kotlin.math.abs(top - marginBelow) <= 1, "equal margins above and below the band")
    }

    @Test
    fun band_stays_within_a_tiny_frame() {
        val (top, height) = MlKitMrzTextRecognizer.mrzBandRect(1)
        assertTrue(height >= 1, "band is at least 1px even when the fraction rounds to 0")
        assertTrue(top >= 0 && top + height <= 1, "band never exceeds the frame")
    }
}
