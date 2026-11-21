package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.checkdigit.computeCheckDigit
import io.lightine.tessera.mrz.formats.MrvAFormatSpec
import io.lightine.tessera.mrz.formats.MrvBFormatSpec
import io.lightine.tessera.mrz.formats.MrzFormatSpec
import io.lightine.tessera.mrz.formats.Td1FormatSpec
import io.lightine.tessera.mrz.formats.Td2FormatSpec
import io.lightine.tessera.mrz.formats.Td3FormatSpec
import io.lightine.tessera.mrz.formats.extractCharFrom
import io.lightine.tessera.mrz.formats.extractFrom
import io.lightine.tessera.mrz.parsing.isMrzAlphabetCharacter

/**
 * The line-detection core shared by [MrzFrameAnalyzer] (strict/lenient decode) and [TolerantMrzMatcher]
 * (candidate disambiguation): windowed extraction of a known ICAO MRZ shape out of a run of recognized-text
 * lines, plus the glyph recovery (case fold, chevron recovery) applied before the shape search. Kept in ONE
 * place, internal, so the two consumers cannot silently drift apart the way they did before TES-117 - the
 * matcher had kept an older run-based, chevron-blind detection after the analyzer was rewritten to window and
 * recover chevrons.
 */
internal object MrzLineDetection {
    /**
     * Finds a window of consecutive [normalizedLines] - already run through [normalizeLine] - that matches a
     * known ICAO MRZ shape (TD1 3×30, TD2/MRV-B 2×36, TD3/MRV-A 2×44), and returns it as the candidate; null
     * when none matches. For each shape (lineCount L × lineWidth W) it slides a window of L consecutive lines
     * and takes the first where every line is exactly W long. Windowing - rather than requiring a whole
     * equal-length *run* to itself be the shape - tolerates the printed noise a live camera OCRs around the
     * zone (place-of-birth, blood group, the legal paragraph, device-observed): a stray line of the same width
     * no longer inflates the run past L and breaks the match, and the shape is still found among longer output.
     * Width stays EXACT here (never padded to fit) - a frame where OCR genuinely dropped or split an MRZ line
     * does not match. The one padding rule lives in [detect] and is tried only after this finds nothing.
     * Larger shapes first (more lines => more specific) for a deterministic pick.
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
     * The MRZ lines for [normalizedLines]: an exact-width window from [findMrzWindow] when there is one;
     * otherwise the first pair of consecutive lines whose name line (line 1) was read short and qualifies for
     * padding ([padShortNameLine], TES-264), reported as [MrzFillerPadding]. Null when neither matches.
     */
    fun detect(normalizedLines: List<String>): DetectedMrz? {
        findMrzWindow(normalizedLines)?.let { return DetectedMrz(it, fillerPadding = null) }
        for (start in 0 until normalizedLines.size - 1) {
            padShortNameLine(normalizedLines[start], normalizedLines[start + 1])?.let { return it }
        }
        return null
    }

    /**
     * Pads [line1] with `<` at the end to the width of [line2], as ICAO Doc 9303 Part 3 fills every position
     * after the name, or returns null when the pair does not qualify. The rules and their limit are on
     * [MrzFillerPadding]:
     * - [line2] is exactly a two-line format's width (36 or 44), all MRZ alphabet, and at least one of its
     *   document number, birth date and expiry date check digits agrees with its field. That is the "rest of the
     *   reading matches a known format" gate: the parser fails only on shape and alphabet, which padding always
     *   satisfies, so without it a stray line ending in `<` above a full-width line 1 could be padded into a pair
     *   that is no MRZ at all. One agreeing digit is enough: a name line or printed text has letters or filler in
     *   those places, and a data line with one misread digit is still the parser's to report.
     * - [line1] is all MRZ alphabet, shorter than [line2], holds a name character after the document code and
     *   issuing state, and ends with the filler as read. That last rule keeps every added `<` after the last name
     *   character the camera saw: a line that stops on a letter or digit may have lost the end of the name, and
     *   padding it would shorten the name without a trace.
     *
     * Nothing else is changed; a TD1 card (three lines, name on line 3) is never padded.
     */
    fun padShortNameLine(
        line1: String,
        line2: String,
    ): DetectedMrz? {
        val width = line2.length
        if (width !in TWO_LINE_WIDTHS || !line2.all(::isMrzAlphabetCharacter)) return null
        if (line1.length >= width || line1.length < MIN_PADDABLE_NAME_LINE) return null
        if (!line1.all(::isMrzAlphabetCharacter) || line1.last() != '<') return null
        if (line1.substring(NAME_START).all { it == '<' }) return null
        val lines = listOf(line1.padEnd(width, '<'), line2)
        if (!anyLine2CheckDigitAgrees(lines)) return null
        return DetectedMrz(
            lines = lines,
            fillerPadding = MrzFillerPadding(lineIndex = 0, recognizedLength = line1.length, paddedLength = width),
        )
    }

    // Line 2 of every two-line format starts with the same three checked fields; the spec is picked as the parser
    // picks it (width, and a leading `V` for a visa), though the positions are the same either way.
    private fun anyLine2CheckDigitAgrees(lines: List<String>): Boolean {
        val visa = lines[0].startsWith('V')
        val spec: MrzFormatSpec =
            when (lines[1].length) {
                Td2FormatSpec.lineLength -> if (visa) MrvBFormatSpec else Td2FormatSpec
                else -> if (visa) MrvAFormatSpec else Td3FormatSpec
            }
        return listOf(
            spec.documentNumber to spec.documentNumberCheckDigit,
            spec.dateOfBirth to spec.dateOfBirthCheckDigit,
            spec.dateOfExpiry to spec.dateOfExpiryCheckDigit,
        ).any { (field, checkDigit) -> computeCheckDigit(field.extractFrom(lines)) == checkDigit.extractCharFrom(lines) }
    }

    /** MRZ lines ready to parse, and the filler padding added to them, if any. */
    internal data class DetectedMrz(
        val lines: List<String>,
        val fillerPadding: MrzFillerPadding?,
    )

    // On every two-line format the name starts after the two-character document code and the three-character
    // issuing state. A paddable line holds that prefix, at least one name character and a trailing filler.
    private const val NAME_START: Int = 5
    private const val MIN_PADDABLE_NAME_LINE: Int = NAME_START + 2

    /**
     * Case is folded to upper (the MRZ alphabet is uppercase-only), and out-of-alphabet chevron glyphs an OCR
     * engine emits for the filler `<` (e.g. ML Kit reads the chevron as `«`) are recovered to `<`. Both are
     * glyph *recovery* - the source can only have been the one intended character, not a choice between two
     * valid ones - so they hold in both modes; whitespace is forgiven only in [ParsingMode.LENIENT]. Callers
     * are responsible for preserving the raw (un-normalized) text on their result (Principle 5) - this function
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
    private fun String.isMrzLineOf(width: Int): Boolean = length == width && all(::isMrzAlphabetCharacter)

    internal data class MrzLineShape(
        val lineCount: Int,
        val lineLength: Int,
    )

    // Out-of-alphabet chevron / guillemet glyphs an OCR engine emits for the MRZ filler `<` (device-observed:
    // ML Kit reads the chevron as `«`). None are part of the MRZ alphabet (A-Z, 0-9, `<`), so mapping them to
    // `<` recovers the only glyph they can be — like folding case to upper, not choosing between two valid
    // characters (reader-not-oracle holds: no data is inferred, the raw OCR text is preserved on every result).
    // Deliberately narrow — only unambiguous chevron look-alikes, never an in-alphabet character. Also used by
    // MrzRowRescue's glyph recovery, so the two cannot drift apart.
    internal val MRZ_CHEVRON_GLYPHS: Set<Char> = setOf('«', '»', '‹', '›', '＜', '＞', '〈', '〉', '⟨', '⟩')

    // The widths of the two-line formats (TD2/MRV-B 36, TD3/MRV-A 44), whose line 1 is the name line.
    private val TWO_LINE_WIDTHS: Set<Int> =
        listOf(Td1FormatSpec, Td2FormatSpec, Td3FormatSpec, MrvAFormatSpec, MrvBFormatSpec)
            .filter { it.lineCount == 2 }
            .map { it.lineLength }
            .toSet()

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
