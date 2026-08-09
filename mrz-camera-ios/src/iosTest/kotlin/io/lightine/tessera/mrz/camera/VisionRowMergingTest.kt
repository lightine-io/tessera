package io.lightine.tessera.mrz.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Host test (Simulator, no live camera) for [VisionMrzTextRecognizer.mergeRowFragments] — the pure
 * row-reassembly behind [VisionMrzTextRecognizer.recognize]. Vision segments text into observations that are
 * not guaranteed to be whole visual lines; the merge groups fragments sharing a row, joins them left-to-right,
 * and orders rows top-to-bottom, so the exact-width MRZ shape match downstream sees complete lines
 * (TES-129/TES-130 device evidence: a 30-char TD1 row arrived as 25 + 5 fragments on one row). All fixture
 * text is synthetic; the geometry mirrors the real device observations.
 */
class VisionRowMergingTest {
    @Test
    fun split_row_joins_left_to_right_into_one_line() {
        // The device case: one 30-char MRZ row returned as 25 + 5 on the same visual row (y ≈ 0.38),
        // with intact rows above and below it — synthetic text, real geometry.
        val merged =
            VisionMrzTextRecognizer.mergeRowFragments(
                listOf(
                    fragment(x = 0.24, y = 0.40, width = 0.59, height = 0.02, text = "A".repeat(30)),
                    fragment(x = 0.73, y = 0.38, width = 0.09, height = 0.01, text = "C".repeat(5)),
                    fragment(x = 0.23, y = 0.38, width = 0.49, height = 0.01, text = "B".repeat(25)),
                    fragment(x = 0.23, y = 0.36, width = 0.42, height = 0.01, text = "D".repeat(22)),
                ),
            )

        assertEquals(3, merged.size, "four fragments across three visual rows merge to three lines")
        assertEquals("A".repeat(30), merged[0].text, "top row intact")
        assertEquals("B".repeat(25) + "C".repeat(5), merged[1].text, "split row joined in left-to-right order")
        assertEquals("D".repeat(22), merged[2].text, "bottom row intact")
    }

    @Test
    fun rows_order_top_to_bottom_regardless_of_input_order() {
        // Vision's normalized origin is bottom-left: a LARGER y is HIGHER on the page.
        val merged =
            VisionMrzTextRecognizer.mergeRowFragments(
                listOf(
                    fragment(x = 0.1, y = 0.2, width = 0.5, height = 0.02, text = "LOWER"),
                    fragment(x = 0.1, y = 0.8, width = 0.5, height = 0.02, text = "UPPER"),
                ),
            )

        assertEquals(listOf("UPPER", "LOWER"), merged.map { it.text })
    }

    @Test
    fun adjacent_but_non_overlapping_rows_stay_separate() {
        // Two rows 0.02 apart with 0.01 heights — no vertical overlap, so no merge even though close.
        val merged =
            VisionMrzTextRecognizer.mergeRowFragments(
                listOf(
                    fragment(x = 0.1, y = 0.38, width = 0.5, height = 0.01, text = "ROW1"),
                    fragment(x = 0.1, y = 0.36, width = 0.5, height = 0.01, text = "ROW2"),
                ),
            )

        assertEquals(listOf("ROW1", "ROW2"), merged.map { it.text })
    }

    @Test
    fun merged_confidence_is_the_minimum_of_the_fragments() {
        val merged =
            VisionMrzTextRecognizer.mergeRowFragments(
                listOf(
                    fragment(x = 0.1, y = 0.5, width = 0.3, height = 0.02, text = "LEFT", confidence = 0.9f),
                    fragment(x = 0.5, y = 0.5, width = 0.3, height = 0.02, text = "RIGHT", confidence = 0.4f),
                ),
            )

        assertEquals(1, merged.size)
        assertEquals(0.4f, merged[0].confidence, "a line is no more trustworthy than its worst fragment")
    }

    @Test
    fun all_null_confidences_stay_null() {
        val merged =
            VisionMrzTextRecognizer.mergeRowFragments(
                listOf(
                    fragment(x = 0.1, y = 0.5, width = 0.3, height = 0.02, text = "LEFT", confidence = null),
                    fragment(x = 0.5, y = 0.5, width = 0.3, height = 0.02, text = "RIGHT", confidence = null),
                ),
            )

        assertNull(merged.single().confidence)
    }

    @Test
    fun zero_height_fragment_never_merges() {
        // A degenerate zero-height box can never satisfy the overlap test — it conservatively stays its
        // own line rather than being glued onto a neighbour by a coincidental centre.
        val merged =
            VisionMrzTextRecognizer.mergeRowFragments(
                listOf(
                    fragment(x = 0.1, y = 0.5, width = 0.3, height = 0.02, text = "REAL"),
                    fragment(x = 0.5, y = 0.5, width = 0.3, height = 0.0, text = "DEGENERATE"),
                ),
            )

        assertEquals(2, merged.size)
    }

    @Test
    fun empty_input_yields_no_lines() {
        assertEquals(emptyList(), VisionMrzTextRecognizer.mergeRowFragments(emptyList()))
    }

    private fun fragment(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        text: String,
        confidence: Float? = 0.5f,
    ) = TextFragment(x = x, y = y, width = width, height = height, text = text, confidence = confidence)
}
