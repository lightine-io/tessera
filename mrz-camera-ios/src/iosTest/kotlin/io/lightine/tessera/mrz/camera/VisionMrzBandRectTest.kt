package io.lightine.tessera.mrz.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Host test (Simulator, no live camera) for [VisionMrzTextRecognizer.mrzBandOrigin] — the pure band-region
 * math behind the MRZ `regionOfInterest` crop (TES-129, the iOS mirror of Android's TES-86
 * `MlKitMrzBandRectTest`). Vision's `regionOfInterest` is normalized against the frame regardless of its
 * pixel size, so — unlike Android's pixel-based crop — the band needs no frame-size input: it is a pure
 * function of the configured height fraction alone. The actual `CGRect` assignment on a live `VNRecognizeTextRequest`,
 * and the `CGImagePropertyOrientation` that makes the band line up with the upright document, need a real
 * Vision call and are exercised by [VisionMrzTextRecognizerTest] (pipeline) and device verification
 * (TES-129 wave 4) — only the band geometry is pure, so it is locked here.
 */
class VisionMrzBandRectTest {
    @Test
    fun band_is_the_configured_fraction_centred_vertically() {
        val fraction = VisionMrzTextRecognizer.MRZ_BAND_HEIGHT_FRACTION
        val (originY, height) = VisionMrzTextRecognizer.mrzBandOrigin(fraction)

        assertEquals(fraction, height, "band height is the configured fraction")
        // Vision's regionOfInterest is normalized with a BOTTOM-LEFT origin; centring is symmetric regardless
        // of which edge origin.y is measured from, so "equal margins above and below" is the same claim as
        // Android's "band is centred vertically" test.
        val marginAbove = 1.0 - (originY + height)
        assertTrue(kotlin.math.abs(originY - marginAbove) < 1e-9, "equal margins above and below the band")
    }

    @Test
    fun band_stays_within_the_normalized_frame_at_the_extremes() {
        val (zeroOrigin, zeroHeight) = VisionMrzTextRecognizer.mrzBandOrigin(0.0)
        assertEquals(0.0, zeroHeight, "a zero fraction is a zero-height band")
        assertEquals(0.5, zeroOrigin, "a zero-height band still sits centred, at the frame's midline")

        val (fullOrigin, fullHeight) = VisionMrzTextRecognizer.mrzBandOrigin(1.0)
        assertEquals(1.0, fullHeight, "a fraction of 1 is the whole frame")
        assertEquals(0.0, fullOrigin, "the whole-frame band starts at the region's origin")
    }
}
