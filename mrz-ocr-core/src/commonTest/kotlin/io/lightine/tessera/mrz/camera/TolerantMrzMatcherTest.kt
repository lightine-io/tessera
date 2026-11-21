package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.checkdigit.computeCheckDigit
import io.lightine.tessera.mrz.parsing.ParseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Host tests for [TolerantMrzMatcher] — the candidate-disambiguation core behind [SavedImageMrzReader]'s
 * `tolerant = true` path. Synthetic MRZ only: the TD3 lines are the project's specimen, the same
 * fixture [MrzFrameAnalyzerTest] uses.
 *
 * TES-117: the matcher used to keep its own run-based, chevron-blind line detection after [MrzFrameAnalyzer]
 * was rewritten to window over candidate lines, guard on the MRZ alphabet, and recover chevron glyphs — so a
 * frame the strict core decoded could still surface zero tolerant candidates. Detection is now shared via
 * [MrzLineDetection]; these tests reuse the analyzer's own repro cases to prove parity.
 */
class TolerantMrzMatcherTest {
    private val referenceTime = Instant.parse("2026-05-04T12:00:00Z")

    // The project's specimen (synthetic; UTO = Utopia).
    private val td3Line1 = "P<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<"
    private val td3Line2 = "T220001293UTO9003152F3403141S98765432<<<<<20"

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

    // Line 2 of the specimen with the expiry date's `0` (column 23) read as `O`. In reading order the guess budget is
    // spent long before column 23: line 1 holds five ambiguous glyphs no check digit covers, and the document number
    // seven more.
    private val td3Line2ExpiryMisread = td3Line2.replaceRange(23, 24, "O")

    @Test
    fun the_budget_reaches_a_check_digit_field_past_an_ambiguous_name() {
        // Before TES-265 the budget went to the first ambiguous glyphs in reading order: all of them on line 1
        // (issuing state and name). The misread expiry date was never tried, so no reading passed its check digits.
        val result = matcher().candidates(textOf(td3Line1, td3Line2ExpiryMisread))

        // Only check-digit-consistent readings are listed once any exists. Two swaps can also cancel out in a check
        // digit, so the fix need not be the only one listed; it must be among them.
        assertTrue(result.isNotEmpty() && result.all { it.parse is ParseResult.Success }, "a consistent reading was found")
        val fixed = result.single { it.mrzLines == listOf(td3Line1, td3Line2) }
        assertEquals(listOf(CharacterDisambiguation(1, 23, 'O', '0')), fixed.disambiguations)
    }

    @Test
    fun no_glyph_is_changed_in_a_field_that_no_check_digit_covers() {
        // Neither expiry digit fix passes (the expiry check digit is also wrong), so the matcher falls back to every
        // parseable reading: the widest set it ever lists. Every change must still sit in a check-digit field.
        val unfixable = td3Line2ExpiryMisread.replaceRange(27, 28, "8")
        val inputs = listOf(listOf(td3Line1, td3Line2), listOf(td3Line1, td3Line2ExpiryMisread), listOf(td3Line1, unfixable))

        inputs.forEach { lines ->
            val result = matcher().candidates(textOf(*lines.toTypedArray()))
            assertTrue(result.isNotEmpty())
            result.forEach { candidate ->
                assertEquals(lines[0], candidate.mrzLines[0], "line 1 (document code, issuing state, name) is never changed")
                candidate.disambiguations.forEach { d ->
                    assertEquals(1, d.lineIndex)
                    assertTrue(d.columnIndex !in 10..12 && d.columnIndex != 20, "nationality and sex are never changed: $d")
                }
            }
        }
    }

    // The specimen's expiry holds no `6`, so this variant moves it to 2036-03-14 ("360314", a `6` at column 22) and
    // recomputes the expiry and composite check digits, then misreads the expiry's `0` (column 23) as `O`. The failing
    // expiry field gets the guess budget first, so its `6` is inside the budget.
    private val td3Line2SixInExpiryMisread =
        td3Line2
            .replaceRange(21, 27, "360314")
            .let { it.replaceRange(27, 28, computeCheckDigit(it.substring(21, 27)).toString()) }
            .let {
                it.replaceRange(
                    43,
                    44,
                    computeCheckDigit(it.substring(0, 10) + it.substring(13, 20) + it.substring(21, 43)).toString(),
                )
            }.replaceRange(23, 24, "O")

    @Test
    fun a_six_g_swap_is_never_tried_because_no_check_digit_can_see_it() {
        // `6` and `G` have values 6 and 16, so every check digit is the same either way: the expiry date's `6`
        // read as `G` would be listed as a fully consistent reading. It must never be tried.
        assertEquals('6', td3Line2SixInExpiryMisread[22], "the fixture puts a 6 in the expiry date")
        val result = matcher().candidates(textOf(td3Line1, td3Line2SixInExpiryMisread))

        assertTrue(result.isNotEmpty())
        assertTrue(result.any { it.disambiguations == listOf(CharacterDisambiguation(1, 23, 'O', '0')) }, "the expiry fix is found")
        result.flatMap { it.disambiguations }.forEach { d ->
            assertTrue(d.recognized !in "6G" && d.resolved !in "6G", "no 6/G swap: $d")
        }
    }

    @Test
    fun a_clean_specimen_lists_no_name_guess() {
        // Changing a name glyph breaks no check digit, so before TES-265 such guesses were listed as consistent
        // readings. Now the name line is never changed in any listed reading.
        val result = matcher().candidates(textOf(td3Line1, td3Line2))

        assertTrue(result.any { it.mrzLines == listOf(td3Line1, td3Line2) && it.disambiguations.isEmpty() })
        assertTrue(result.all { it.mrzLines[0] == td3Line1 && it.disambiguations.all { d -> d.lineIndex == 1 } })
    }
}
