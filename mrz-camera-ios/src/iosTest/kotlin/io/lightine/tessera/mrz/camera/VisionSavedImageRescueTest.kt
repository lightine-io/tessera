package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.camera.VisionSavedImageRescue.NormalizedRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Host tests (Simulator, no live camera) for [VisionSavedImageRescue] — the pure half of the saved-image
 * two-pass MRZ rescue (TES-130): candidate detection, exact-width selection with over-merged-block
 * splitting, and the EXIF-undoing crop-rect math. All fixture text is synthetic.
 */
class VisionSavedImageRescueTest {
    // --- isMrzCandidate ---

    @Test
    fun mrz_shaped_rows_qualify_and_viz_text_does_not() {
        assertTrue(VisionSavedImageRescue.isMrzCandidate("ABCDEFGHI<KLMNOPQRSTUVWXYZ0123"))
        // Chevron-free text (a VIZ name line) never qualifies, whatever its charset.
        assertFalse(VisionSavedImageRescue.isMrzCandidate("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123"))
        // Lowercase-heavy text fails the charset fraction.
        assertFalse(VisionSavedImageRescue.isMrzCandidate("abcdefghijklmnopqrst<uvwxyz012"))
        // Short runs (a date, a document number) are below the candidacy floor.
        assertFalse(VisionSavedImageRescue.isMrzCandidate("AB<12"))
    }

    // --- rescuedLines ---

    @Test
    fun exact_width_reads_are_kept_for_every_icao_width() {
        for (width in listOf(30, 36, 44)) {
            val line = "A".repeat(width)
            assertEquals(listOf(line), VisionSavedImageRescue.rescuedLines(line), "width $width")
        }
    }

    @Test
    fun an_over_merged_block_splits_into_its_rows_in_read_order() {
        // Photo-verified: a tight TD1 block sometimes returns as ONE 90-char observation (3×30).
        val block = "A".repeat(30) + "B".repeat(30) + "C".repeat(30)
        assertEquals(
            listOf("A".repeat(30), "B".repeat(30), "C".repeat(30)),
            VisionSavedImageRescue.rescuedLines(block),
        )
        // 2×44 — a TD3 pair merged into one read.
        val td3 = "D".repeat(44) + "E".repeat(44)
        assertEquals(listOf("D".repeat(44), "E".repeat(44)), VisionSavedImageRescue.rescuedLines(td3))
    }

    @Test
    fun non_matching_lengths_rescue_nothing() {
        assertEquals(emptyList(), VisionSavedImageRescue.rescuedLines("A".repeat(24)), "short read")
        assertEquals(emptyList(), VisionSavedImageRescue.rescuedLines("A".repeat(31)), "off by one")
        // 4×30 would be deeper than any ICAO shape — noise, not an MRZ block.
        assertEquals(emptyList(), VisionSavedImageRescue.rescuedLines("A".repeat(120)), "too many rows")
        assertEquals(emptyList(), VisionSavedImageRescue.rescuedLines(""), "empty")
    }

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
