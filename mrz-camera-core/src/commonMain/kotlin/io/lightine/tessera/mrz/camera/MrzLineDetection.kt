package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.formats.MrvAFormatSpec
import io.lightine.tessera.mrz.formats.MrvBFormatSpec
import io.lightine.tessera.mrz.formats.Td1FormatSpec
import io.lightine.tessera.mrz.formats.Td2FormatSpec
import io.lightine.tessera.mrz.formats.Td3FormatSpec

/**
 * The line-detection core shared by [MrzFrameAnalyzer] (strict/lenient decode) and [TolerantMrzMatcher]
 * (candidate disambiguation): windowed extraction of a known ICAO MRZ shape out of a run of recognized-text
 * lines, plus the glyph recovery (case fold, chevron recovery) applied before the shape search. Kept in ONE
 * place, internal, so the two consumers cannot silently drift apart the way they did before TES-117 — the
 * matcher had kept an older run-based, chevron-blind detection after the analyzer was rewritten to window and
 * recover chevrons.
 */
internal object MrzLineDetection {
    /**
     * Finds a window of consecutive [normalizedLines] — already run through [normalizeLine] — that matches a
     * known ICAO MRZ shape (TD1 3×30, TD2/MRV-B 2×36, TD3/MRV-A 2×44), and returns it as the candidate; null
     * when none matches. For each shape (lineCount L × lineWidth W) it slides a window of L consecutive lines
     * and takes the first where every line is exactly W long. Windowing — rather than requiring a whole
     * equal-length *run* to itself be the shape — tolerates the printed noise a live camera OCRs around the
     * zone (place-of-birth, blood group, the legal paragraph, device-observed): a stray line of the same width
     * no longer inflates the run past L and breaks the match, and the shape is still found among longer output.
     * Width stays EXACT (never padded to fit) so every candidate is parseable and no data is inferred
     * (Principle 1) — a frame where OCR genuinely dropped or split an MRZ line simply does not match. Larger
     * shapes first (more lines => more specific) for a deterministic pick.
     */
    fun findMrzWindow(normalizedLines: List<String>): List<String>? {
        for (shape in MRZ_SHAPES_BY_SPECIFICITY) {
            for (start in 0..normalizedLines.size - shape.lineCount) {
                if ((0 until shape.lineCount).all { normalizedLines[start + it].isMrzLineOf(shape.lineLength) }) {
                    return normalizedLines.subList(start, start + shape.lineCount).toList()
                }
            }
        }
        return null
    }

    /**
     * Case is folded to upper (the MRZ alphabet is uppercase-only), and out-of-alphabet chevron glyphs an OCR
     * engine emits for the filler `<` (e.g. ML Kit reads the chevron as `«`) are recovered to `<`. Both are
     * glyph *recovery* — the source can only have been the one intended character, not a choice between two
     * valid ones — so they hold in both modes; whitespace is forgiven only in [ParsingMode.LENIENT]. Callers
     * are responsible for preserving the raw (un-normalized) text on their result (Principle 5) — this function
     * only ever produces the parse candidate.
     */
    fun normalizeLine(
        raw: String,
        mode: ParsingMode,
    ): String {
        val cased =
            when (mode) {
                ParsingMode.STRICT -> raw.trim().uppercase()
                ParsingMode.LENIENT -> raw.filterNot(Char::isWhitespace).uppercase()
            }
        return MRZ_CHEVRON_GLYPHS.fold(cased) { line, chevron -> line.replace(chevron, '<') }
    }

    // A normalized line qualifies for a shape's window when it is exactly the shape's width AND every
    // character is in the MRZ alphabet (A-Z, 0-9, `<`). The alphabet guard is what lets windowing pick the
    // real zone out of same-width printed noise: a line of the right length but carrying punctuation, digits'
    // separators, or lowercase left over from surrounding text (already upper-folded and chevron-recovered by
    // normalizeLine) is rejected, so the window lands on the actual MRZ pair/triple rather than a neighbour.
    private fun String.isMrzLineOf(width: Int): Boolean = length == width && all { it in 'A'..'Z' || it in '0'..'9' || it == '<' }

    internal data class MrzLineShape(
        val lineCount: Int,
        val lineLength: Int,
    )

    // Out-of-alphabet chevron / guillemet glyphs an OCR engine emits for the MRZ filler `<` (device-observed:
    // ML Kit reads the chevron as `«`). None are part of the MRZ alphabet (A-Z, 0-9, `<`), so mapping them to
    // `<` recovers the only glyph they can be — like folding case to upper, not choosing between two valid
    // characters (reader-not-oracle holds: no data is inferred, the raw OCR text is preserved on every result).
    // Deliberately narrow — only unambiguous chevron look-alikes, never an in-alphabet character.
    private val MRZ_CHEVRON_GLYPHS: Set<Char> = setOf('«', '»', '‹', '›', '＜', '＞', '〈', '〉', '⟨', '⟩')

    // The distinct ICAO line shapes, sourced from mrz-core's format specs rather than restated as magic
    // numbers: TD1 3×30, TD2/MRV-B 2×36, TD3/MRV-A 2×44. Ordered by line count descending so the window search
    // tries the more specific (more lines) shape first — deterministic when output could otherwise satisfy
    // more than one.
    private val MRZ_SHAPES_BY_SPECIFICITY: List<MrzLineShape> =
        listOf(Td1FormatSpec, Td2FormatSpec, Td3FormatSpec, MrvAFormatSpec, MrvBFormatSpec)
            .map { MrzLineShape(it.lineCount, it.lineLength) }
            .distinct()
            .sortedByDescending { it.lineCount }
}
