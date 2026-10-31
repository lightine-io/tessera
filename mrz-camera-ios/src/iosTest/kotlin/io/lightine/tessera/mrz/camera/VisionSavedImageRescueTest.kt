package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.camera.VisionSavedImageRescue.NormalizedRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Host tests (Simulator, no live camera) for [VisionSavedImageRescue] — the pure half of the saved-image
 * two-pass MRZ rescue (TES-130): the EXIF-undoing crop-rect math, padding, and row regions. All fixture text is synthetic.
 */
class VisionSavedImageRescueTest {
    // isMrzCandidate / rescuedLines are shared with Android and tested in mrz-camera-core (MrzRowRescueTest).

    // --- rawCropRect (oriented bottom-left normalized -> raw top-left normalized) ---

    @Test
    fun orientation_6_maps_the_low_display_band_to_the_raw_left_strip() {
        // The validated real-photo case (EXIF 6, the usual portrait back-camera photo): the MRZ band low on
        // the displayed image — full display width, y in [0.31, 0.48] bottom-left — must come back as a
        // tall strip on the raw bitmap's left side (raw x = 1 - displayTop).
        val raw = VisionSavedImageRescue.rawCropRect(NormalizedRect(0.19, 0.31, 0.70, 0.17), 6)!!
        assertEquals(0.52, raw.x, absoluteTolerance = 1e-9) // 1 - (y + h) = 1 - 0.48
        assertEquals(0.11, raw.y, absoluteTolerance = 1e-9) // 1 - (x + w) = 1 - 0.89
        assertEquals(0.17, raw.width, absoluteTolerance = 1e-9)
        assertEquals(0.70, raw.height, absoluteTolerance = 1e-9)
    }

    @Test
    fun orientation_1_only_flips_the_vertical_origin() {
        val raw = VisionSavedImageRescue.rawCropRect(NormalizedRect(0.1, 0.2, 0.5, 0.1), 1)!!
        assertEquals(0.1, raw.x, absoluteTolerance = 1e-9)
        assertEquals(0.7, raw.y, absoluteTolerance = 1e-9) // top-left y = 1 - 0.2 - 0.1
        assertEquals(0.5, raw.width, absoluteTolerance = 1e-9)
        assertEquals(0.1, raw.height, absoluteTolerance = 1e-9)
    }

    @Test
    fun round_trip_symmetry_holds_for_the_rotated_orientations() {
        // 3 (180°) applied twice is the identity; 6 and 8 are mutual inverses in rect space.
        val rect = NormalizedRect(0.15, 0.25, 0.4, 0.2)
        val twice3 = VisionSavedImageRescue.rawCropRect(flipToBottomLeft(VisionSavedImageRescue.rawCropRect(rect, 3)!!), 3)!!
        assertRectEquals(rect, flipToBottomLeft(twice3))
    }

    @Test
    fun mirrored_orientations_are_refused() {
        for (orientation in listOf(2, 4, 5, 7, 0, 9)) {
            assertNull(VisionSavedImageRescue.rawCropRect(NormalizedRect(0.1, 0.1, 0.5, 0.2), orientation))
        }
    }

    // --- padded ---

    @Test
    fun padding_grows_and_clamps_to_the_unit_square() {
        val padded = NormalizedRect(0.02, 0.9, 0.9, 0.08).padded(padX = 0.06, padY = 0.05)
        assertEquals(0.0, padded.x, absoluteTolerance = 1e-9)
        assertEquals(0.85, padded.y, absoluteTolerance = 1e-9)
        assertEquals(0.98, padded.x + padded.width, absoluteTolerance = 1e-9)
        assertEquals(1.0, padded.y + padded.height, absoluteTolerance = 1e-9)
    }

    // --- rowRegion ---

    @Test
    fun row_region_is_the_union_of_its_fragments() {
        val region =
            VisionSavedImageRescue.rowRegion(
                listOf(
                    TextFragment(x = 0.2, y = 0.38, width = 0.5, height = 0.02, text = "LEFT", confidence = null),
                    TextFragment(x = 0.72, y = 0.39, width = 0.1, height = 0.02, text = "RIGHT", confidence = null),
                ),
            )
        assertEquals(0.2, region.x, absoluteTolerance = 1e-9)
        assertEquals(0.38, region.y, absoluteTolerance = 1e-9)
        assertEquals(0.82, region.x + region.width, absoluteTolerance = 1e-9)
        assertEquals(0.41, region.y + region.height, absoluteTolerance = 1e-9)
    }

    // A raw-space rect (top-left origin) re-expressed with a bottom-left origin, so it can be fed back
    // through rawCropRect (which expects oriented bottom-left input) for the symmetry check.
    private fun flipToBottomLeft(rect: NormalizedRect) = NormalizedRect(rect.x, 1.0 - rect.y - rect.height, rect.width, rect.height)

    private fun assertRectEquals(
        expected: NormalizedRect,
        actual: NormalizedRect,
    ) {
        assertEquals(expected.x, actual.x, absoluteTolerance = 1e-9)
        assertEquals(expected.y, actual.y, absoluteTolerance = 1e-9)
        assertEquals(expected.width, actual.width, absoluteTolerance = 1e-9)
        assertEquals(expected.height, actual.height, absoluteTolerance = 1e-9)
    }
}
