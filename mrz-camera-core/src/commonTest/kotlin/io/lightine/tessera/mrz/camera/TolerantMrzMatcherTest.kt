package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.parsing.ParseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Host tests for [TolerantMrzMatcher] — the candidate-disambiguation core behind [SavedImageMrzReader]'s
 * `tolerant = true` path. Synthetic MRZ only: the TD3 lines are the ICAO Doc 9303 Utopia specimen, the same
 * fixture [MrzFrameAnalyzerTest] uses.
 *
 * TES-117: the matcher used to keep its own run-based, chevron-blind line detection after [MrzFrameAnalyzer]
 * was rewritten to window over candidate lines, guard on the MRZ alphabet, and recover chevron glyphs — so a
 * frame the strict core decoded could still surface zero tolerant candidates. Detection is now shared via
 * [MrzLineDetection]; these tests reuse the analyzer's own repro cases to prove parity.
 */
class TolerantMrzMatcherTest {
    private val referenceTime = Instant.parse("2026-05-04T12:00:00Z")

    // ICAO Doc 9303 Utopia specimen (synthetic).
    private val td3Line1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    private val td3Line2 = "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"

    private fun matcher(mode: ParsingMode = ParsingMode.STRICT): TolerantMrzMatcher =
        TolerantMrzMatcher(mode = mode, referenceTimeProvider = { referenceTime })

    private fun textOf(vararg lines: String): RecognizedText = RecognizedText(lines.map { RecognizedLine(it, null) })

    // The candidate whose disambiguations are empty is the as-recognized reading — no glyph was resolved
    // away from what the (post-recovery) line said. Mirrors SavedImageMrzReaderTest's own helper pattern.
    private fun List<MrzCandidate>.asRecognized(): MrzCandidate? = singleOrNull { it.disambiguations.isEmpty() }

    @Test
    fun finds_candidates_despite_an_adjacent_same_width_printed_line() {
        // The exact repro from MrzFrameAnalyzerTest's "finds_the_mrz_despite_an_adjacent_same_width_printed_line":
        // a printed line the SAME width as a TD3 line (44) sits directly against the zone. Before TES-117 the
        // matcher's own line detection lumped the neighbour into a 3x44 "run" — not a known shape — so
        // candidates() returned empty even though the strict analyzer decoded this exact input successfully.
        val printedNeighbour = td3Line1.dropLast(1) + "," // 44 chars, but a comma -> not MRZ-alphabet
        val result = matcher().candidates(textOf(printedNeighbour, td3Line1, td3Line2))

        assertTrue(result.isNotEmpty(), "candidates must not be empty now that window search is shared with the strict core")
        val asRecognized = result.asRecognized()
        assertEquals(listOf(td3Line1, td3Line2), asRecognized?.mrzLines, "the as-recognized TD3 reading must be among the candidates")
        assertIs<ParseResult.Success>(asRecognized?.parse)
    }

    @Test
    fun recovers_chevron_glyphs_the_same_way_the_analyzer_does() {
        // OCR sometimes reads the MRZ filler `<` as the out-of-alphabet chevron `«` (device-observed with ML
        // Kit — see MrzFrameAnalyzerTest's "recovers_ocr_chevron_glyphs_to_the_filler_and_still_decodes"). The
        // strict analyzer recovers `«` -> `<` before shape-matching; the matcher must do the same via the
        // shared MrzLineDetection, or a chevron-corrupted frame never reaches a known shape and candidates()
        // stays empty.
        val ocrLine1 = td3Line1.replace('<', '«')
        val ocrLine2 = td3Line2.replace('<', '«')

        val result = matcher().candidates(textOf(ocrLine1, ocrLine2))

        assertTrue(result.isNotEmpty(), "chevron-corrupted lines must still resolve into a shape-matched window")
        val asRecognized = result.asRecognized()
        assertEquals(listOf(td3Line1, td3Line2), asRecognized?.mrzLines, "chevrons recover to the same clean TD3 reading")
        assertIs<ParseResult.Success>(asRecognized?.parse)
    }

    @Test
    fun clean_input_still_matches_with_no_regression() {
        val result = matcher().candidates(textOf(td3Line1, td3Line2))

        assertTrue(result.isNotEmpty(), "a clean, already-shape-matching TD3 pair must still surface candidates")
        val asRecognized = result.asRecognized()
        assertEquals(listOf(td3Line1, td3Line2), asRecognized?.mrzLines)
        assertIs<ParseResult.Success>(asRecognized?.parse)
    }

    @Test
    fun no_shape_matched_window_yields_no_candidates() {
        // Unrelated printed text of no known MRZ shape still yields nothing — the shared detection must not
        // become MORE permissive than before, only correctly windowed.
        val result = matcher().candidates(textOf("UTOPIA", "JUST SOME PRINTED TEXT"))

        assertTrue(result.isEmpty())
    }
}
