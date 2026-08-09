package io.lightine.tessera.mrz.camera

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
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
@OptIn(ExperimentalForeignApi::class)
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

    // --- visionRegionOfInterest: metadata-output space (unrotated buffer, top-left origin) → Vision
    // regionOfInterest space for the `.right`-oriented image (oriented image, bottom-left origin). ---

    @Test
    fun whole_buffer_maps_to_the_whole_oriented_image() {
        assertRect(
            expected = listOf(0.0, 0.0, 1.0, 1.0),
            actual = VisionMrzTextRecognizer.visionRegionOfInterest(MetadataRect(0.0, 0.0, 1.0, 1.0)),
            message = "the identity region survives the rotation and origin flip",
        )
    }

    @Test
    fun low_onscreen_guide_band_maps_to_a_low_full_width_oriented_band() {
        // A guide band sitting low on a portrait display (display-space y in [0.70, 0.85], full width) is,
        // in metadata-output space for a `.right` buffer, a tall strip: x in [0.70, 0.85], full height.
        // In Vision's oriented bottom-left space that must come back as a full-width horizontal band with
        // its bottom edge at y = 1 - 0.85 = 0.15 — where the MRZ sits on screen.
        assertRect(
            expected = listOf(0.0, 0.15, 1.0, 0.15),
            actual = VisionMrzTextRecognizer.visionRegionOfInterest(MetadataRect(0.70, 0.0, 0.15, 1.0)),
            message = "the on-screen MRZ band lands as a low horizontal band of the oriented image",
        )
    }

    @Test
    fun rect_poking_outside_the_buffer_is_clamped_into_the_normalized_space() {
        // Vision rejects out-of-space rects (the request fails), so a guide rect that pokes outside the
        // buffer after the aspect-fill conversion must be clamped, never passed through.
        val clamped = VisionMrzTextRecognizer.visionRegionOfInterest(MetadataRect(-0.1, -0.1, 1.3, 1.3))
        clamped.useContents {
            assertTrue(origin.x >= 0.0 && origin.y >= 0.0, "origin clamped into the unit square")
            assertTrue(origin.x + size.width <= 1.0 + 1e-9, "right edge clamped to the unit square")
            assertTrue(origin.y + size.height <= 1.0 + 1e-9, "top edge clamped to the unit square")
        }
    }

    private fun assertRect(
        expected: List<Double>,
        actual: kotlinx.cinterop.CValue<platform.CoreGraphics.CGRect>,
        message: String,
    ) {
        actual.useContents {
            assertEquals(expected[0], origin.x, absoluteTolerance = 1e-9, "$message (x)")
            assertEquals(expected[1], origin.y, absoluteTolerance = 1e-9, "$message (y)")
            assertEquals(expected[2], size.width, absoluteTolerance = 1e-9, "$message (width)")
            assertEquals(expected[3], size.height, absoluteTolerance = 1e-9, "$message (height)")
        }
    }
}
