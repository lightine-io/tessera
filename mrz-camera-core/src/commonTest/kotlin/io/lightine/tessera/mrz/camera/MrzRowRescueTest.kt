package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.camera.MrzRowRescue.Box
import io.lightine.tessera.mrz.camera.MrzRowRescue.BoxedLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Host tests for [MrzRowRescue], the short-row rescue rules shared by the Android and iOS recognizers.
 * Fixture text is the ICAO Doc 9303 Utopia TD3 specimen (as in [MrzFrameAnalyzerTest]); a short line is made
 * by collapsing its filler run, the way the OCR engines do.
 */
class MrzRowRescueTest {
    private val td3Line1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    private val td3Line2 = "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"

    // Line 1 with 9 filler chevrons lost (35 chars), split into two fragments the way ML Kit returned it.
    private val shortLine1 = td3Line1.dropLast(9)
    private val fragmentA = shortLine1.take(30)
    private val fragmentB = shortLine1.drop(30)

    private fun line(
        text: String,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        confidence: Float? = null,
    ) = BoxedLine(text, confidence, Box(left, top, right, bottom))

    // A 1000x600 image: line 1 in two fragments (100..700 and 720..820, y 400..420), line 2 exact at
    // 100..980, y 440..460.
    private val pass1 =
        listOf(
            line(fragmentA, 100.0, 400.0, 700.0, 420.0),
            line(fragmentB, 720.0, 401.0, 820.0, 421.0),
            line(td3Line2, 100.0, 440.0, 980.0, 460.0),
        )

    // --- shared constants ---

    @Test
    fun widths_are_the_icao_line_widths() {
        assertEquals(listOf(30, 36, 44), MrzRowRescue.MRZ_LINE_WIDTHS)
    }

    @Test
    fun chevron_glyphs_match_the_line_detection_set() {
        assertEquals(MrzLineDetection.MRZ_CHEVRON_GLYPHS, MrzRowRescue.CHEVRON_GLYPHS)
    }

    // --- isMrzCandidate (moved from the iOS rescue tests) ---

    @Test
    fun mrz_shaped_rows_qualify_and_viz_text_does_not() {
        assertTrue(MrzRowRescue.isMrzCandidate("ABCDEFGHI<KLMNOPQRSTUVWXYZ0123"))
        // Chevron-free text (a VIZ name line) never qualifies, whatever its charset.
        assertFalse(MrzRowRescue.isMrzCandidate("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123"))
        // Lowercase-heavy text fails the charset fraction.
        assertFalse(MrzRowRescue.isMrzCandidate("abcdefghijklmnopqrst<uvwxyz012"))
        // Short runs (a date, a document number) are below the candidacy floor.
        assertFalse(MrzRowRescue.isMrzCandidate("AB<12"))
    }

    // --- rescuedLines: the keep rule (moved from the iOS rescue tests, plus TD3 cases) ---

    @Test
    fun exact_width_reads_are_kept_for_every_icao_width() {
        for (width in listOf(30, 36, 44)) {
            val text = "A".repeat(width)
            assertEquals(listOf(text), MrzRowRescue.rescuedLines(text), "width $width")
        }
        assertEquals(listOf(td3Line1), MrzRowRescue.rescuedLines(td3Line1))
    }

    @Test
    fun a_short_read_is_rejected_and_nothing_is_padded() {
        assertEquals(emptyList(), MrzRowRescue.rescuedLines(shortLine1))
        assertEquals(emptyList(), MrzRowRescue.rescuedLines(td3Line1.dropLast(1)), "one short")
        assertEquals(emptyList(), MrzRowRescue.rescuedLines("A".repeat(31)), "off by one")
        assertEquals(emptyList(), MrzRowRescue.rescuedLines("A".repeat(120)), "too many rows")
        assertEquals(emptyList(), MrzRowRescue.rescuedLines(""), "empty")
    }

    @Test
    fun an_over_merged_block_splits_into_its_rows_in_read_order() {
        assertEquals(listOf(td3Line1, td3Line2), MrzRowRescue.rescuedLines(td3Line1 + td3Line2))
        val block = "A".repeat(30) + "B".repeat(30) + "C".repeat(30)
        assertEquals(listOf("A".repeat(30), "B".repeat(30), "C".repeat(30)), MrzRowRescue.rescuedLines(block))
    }

    // --- near-miss and exact-line tests ---

    @Test
    fun a_collapsed_filler_line_is_a_near_miss_and_an_exact_line_is_not() {
        assertTrue(MrzRowRescue.isNearMiss(shortLine1, 44))
        assertFalse(MrzRowRescue.isNearMiss(td3Line1, 44), "already exact width")
        assertFalse(MrzRowRescue.isNearMiss("ERIKSSON ANNA MARIA".filterNot(Char::isWhitespace), 44), "no filler")
        // ML Kit's `«` stands for one `<`: still a near miss after glyph recovery.
        assertTrue(MrzRowRescue.isNearMiss(shortLine1.replace('<', '«'), 44))
    }

    @Test
    fun exact_line_check_recovers_chevrons_one_for_one() {
        assertTrue(MrzRowRescue.isExactMrzLine(td3Line2))
        assertTrue(MrzRowRescue.isExactMrzLine(td3Line2.replace('<', '«')))
        assertFalse(MrzRowRescue.isExactMrzLine(shortLine1))
        assertFalse(MrzRowRescue.isExactMrzLine("L898902C,3UTO6908061F9406236ZE184226B<<<<<14"), "out of alphabet")
    }

    // --- row grouping ---

    @Test
    fun fragments_overlapping_by_half_a_row_join_left_to_right() {
        val rows = MrzRowRescue.groupRows(pass1)
        assertEquals(listOf(listOf(0, 1), listOf(2)), rows)
        assertEquals(shortLine1, MrzRowRescue.rowText(pass1, rows[0]))
        // Listed right fragment first: still joined left to right.
        val swapped = listOf(pass1[1], pass1[0])
        assertEquals(shortLine1, MrzRowRescue.rowText(swapped, MrzRowRescue.groupRows(swapped).single()))
    }

    // --- planRereads: the re-read rectangle ---

    @Test
    fun the_crop_spans_the_neighbour_width_and_the_padded_union_of_the_short_row() {
        val plan = MrzRowRescue.planRereads(pass1, 1000.0, 600.0).single()
        assertEquals(listOf(0, 1), plan.row)
        assertEquals(44, plan.width)
        assertEquals(Box(100.0, 400.0, 820.0, 421.0), plan.rowBox)
        // Neighbour 100..980 (width 880), 3% = 26.4 each side; row 400..421 (height 21), 60% = 12.6.
        assertBoxEquals(Box(73.6, 387.4, 1000.0, 433.6), plan.crop)
    }

    @Test
    fun the_crop_is_clamped_to_the_image() {
        val nearEdges =
            listOf(
                line(fragmentA, 5.0, 2.0, 600.0, 22.0),
                line(fragmentB, 610.0, 2.0, 700.0, 22.0),
                line(td3Line2, 5.0, 30.0, 795.0, 50.0),
            )
        val plan = MrzRowRescue.planRereads(nearEdges, 800.0, 600.0).single()
        assertBoxEquals(Box(0.0, 0.0, 800.0, 34.0), plan.crop)
    }

    @Test
    fun a_short_row_below_its_exact_neighbour_is_planned_too() {
        // Short row below an exact row (e.g. TD1 line 3 under line 2) is planned too.
        val below =
            listOf(
                line(td3Line2, 100.0, 400.0, 980.0, 420.0),
                line(shortLine1, 100.0, 440.0, 800.0, 460.0),
            )
        assertEquals(listOf(1), MrzRowRescue.planRereads(below, 1000.0, 600.0).single().row)
    }

    @Test
    fun no_plan_without_an_exact_neighbour_or_for_an_exact_row() {
        // Both rows short: nothing to borrow a width from.
        val bothShort =
            listOf(
                line(shortLine1, 100.0, 400.0, 800.0, 420.0),
                line(td3Line2.dropLast(3), 100.0, 440.0, 900.0, 460.0),
            )
        assertEquals(emptyList(), MrzRowRescue.planRereads(bothShort, 1000.0, 600.0))
        // Both rows exact: never re-read an exact row.
        val bothExact =
            listOf(
                line(td3Line1, 100.0, 400.0, 980.0, 420.0),
                line(td3Line2, 100.0, 440.0, 980.0, 460.0),
            )
        assertEquals(emptyList(), MrzRowRescue.planRereads(bothExact, 1000.0, 600.0))
        // A non-adjacent exact row does not count as a neighbour.
        val gap =
            listOf(
                line(shortLine1, 100.0, 300.0, 800.0, 320.0),
                line("UTOPIA", 100.0, 360.0, 300.0, 380.0),
                line(td3Line2, 100.0, 440.0, 980.0, 460.0),
            )
        assertEquals(emptyList(), MrzRowRescue.planRereads(gap, 1000.0, 600.0))
    }

    // --- scale and coordinate mapping ---

    @Test
    fun scale_reaches_the_character_minimum_within_bounds() {
        assertEquals(2, MrzRowRescue.rereadScale(rowHeight = 20.0, cropLongestSide = 900.0), "default 2x")
        assertEquals(3, MrzRowRescue.rereadScale(rowHeight = 6.0, cropLongestSide = 900.0), "16/6 -> 3x")
        assertEquals(4, MrzRowRescue.rereadScale(rowHeight = 2.0, cropLongestSide = 900.0), "capped at 4x")
        assertEquals(1, MrzRowRescue.rereadScale(rowHeight = 20.0, cropLongestSide = 3000.0), "side cap")
    }

    @Test
    fun crop_space_maps_back_to_image_space() {
        val crop = Box(73.6, 387.4, 1000.0, 433.6)
        val mapped = MrzRowRescue.fromCropSpace(Box(10.0, 20.0, 110.0, 60.0), crop, scale = 2)
        assertBoxEquals(Box(78.6, 397.4, 128.6, 417.4), mapped)
    }

    // --- keptLines ---

    @Test
    fun an_exact_reread_of_the_short_row_is_kept_and_a_neighbour_sliver_is_ignored() {
        val plan = MrzRowRescue.planRereads(pass1, 1000.0, 600.0).single()
        val reread =
            listOf(
                line(td3Line1.replace("<<<<<", "«<<<<"), 100.0, 401.0, 980.0, 420.0, confidence = 0.9f),
                // The padded crop caught the top of line 2; whatever it read there is not this row.
                line(td3Line2, 100.0, 428.0, 980.0, 434.0, confidence = 0.5f),
            )
        val kept = MrzRowRescue.keptLines(reread, plan)
        assertEquals(listOf(RecognizedLine(td3Line1.replace("<<<<<", "«<<<<"), 0.9f)), kept)
    }

    @Test
    fun a_short_or_wrong_width_reread_keeps_nothing() {
        val plan = MrzRowRescue.planRereads(pass1, 1000.0, 600.0).single()
        assertEquals(emptyList(), MrzRowRescue.keptLines(listOf(line(shortLine1, 100.0, 401.0, 820.0, 420.0)), plan))
        assertEquals(emptyList(), MrzRowRescue.keptLines(listOf(line("A<".repeat(15), 100.0, 401.0, 820.0, 420.0)), plan))
    }

    @Test
    fun a_reread_split_into_fragments_is_joined_before_the_keep_rule() {
        val plan = MrzRowRescue.planRereads(pass1, 1000.0, 600.0).single()
        val reread =
            listOf(
                line(td3Line1.take(20), 100.0, 401.0, 500.0, 420.0, confidence = 0.8f),
                line(td3Line1.drop(20), 510.0, 401.0, 980.0, 420.0, confidence = 0.6f),
            )
        assertEquals(listOf(RecognizedLine(td3Line1, 0.6f)), MrzRowRescue.keptLines(reread, plan))
    }

    // --- splice ---

    @Test
    fun rescued_lines_take_the_place_of_the_short_rows_fragments() {
        val original = listOf("header", fragmentA, fragmentB, td3Line2)
        assertEquals(
            listOf("header", td3Line1, td3Line2),
            MrzRowRescue.splice(original, mapOf(listOf(1, 2) to listOf(td3Line1))),
        )
        // No replacement, or an empty one: unchanged.
        assertEquals(original, MrzRowRescue.splice(original, emptyMap()))
        assertEquals(original, MrzRowRescue.splice(original, mapOf(listOf(1, 2) to emptyList())))
    }

    private fun assertBoxEquals(
        expected: Box,
        actual: Box,
    ) {
        assertEquals(expected.left, actual.left, absoluteTolerance = 1e-9, "left")
        assertEquals(expected.top, actual.top, absoluteTolerance = 1e-9, "top")
        assertEquals(expected.right, actual.right, absoluteTolerance = 1e-9, "right")
        assertEquals(expected.bottom, actual.bottom, absoluteTolerance = 1e-9, "bottom")
    }
}
