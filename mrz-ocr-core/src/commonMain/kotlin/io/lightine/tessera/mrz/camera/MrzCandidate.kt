package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.parsing.ParseResult

/**
 * One reconstruction of the recognized MRZ, listed by tolerant saved-image reading.
 *
 * Tolerant reading ([SavedImageMrzReader] with `tolerant = true`) tries the alternatives for ambiguous
 * characters (`0`/`O`, `1`/`I`, `2`/`Z`, `5`/`S`, `8`/`B`), parses each distinct
 * reconstruction, and lists them without choosing one. Reconstructions that do not parse are
 * dropped. When any reconstruction passes every check digit, only those are listed; otherwise
 * every parseable one is listed. At most 8 are listed. Only characters in a field that a check
 * digit covers are varied; the name and every other unchecked field stay as recognized. `6`/`G` is
 * not tried, because a check digit cannot tell the two apart. At most 6
 * ambiguous positions are varied: those in fields whose check digit fails come first, then the
 * rest, each in reading order; later ones stay as recognized. An absent candidate means the reading
 * was not explored, not that it is ruled out. More than one reconstruction can pass every check
 * digit: two changed characters in one field can cancel out in its check digit. All of them are
 * listed, in no order of likelihood, and the app or the person chooses. The type holds document
 * data: do not log it verbatim.
 *
 * @since 0.3.0
 */
public data class MrzCandidate(
    /** The reconstructed MRZ lines. */
    public val mrzLines: List<String>,
    /** The parser's result for this reconstruction, with its check-digit outcomes. */
    public val parse: ParseResult,
    /**
     * The glyph changes that produce this candidate from the recognized text. Empty for the
     * as-recognized candidate.
     */
    public val disambiguations: List<CharacterDisambiguation>,
    /**
     * The `<` the reader added at the end of a name line that was read short, the same for every
     * candidate of one read and for its scan result; `null` when every line was read at full width.
     * See [MrzFillerPadding]. Added in 1.0.0.
     */
    public val fillerPadding: MrzFillerPadding?,
) {
    /**
     * Creates a candidate with no filler padding.
     *
     * @param mrzLines the reconstructed MRZ lines.
     * @param parse the parser's result for this reconstruction.
     * @param disambiguations the glyph changes that produce this candidate.
     * @since 0.3.0
     */
    public constructor(
        mrzLines: List<String>,
        parse: ParseResult,
        disambiguations: List<CharacterDisambiguation>,
    ) : this(mrzLines, parse, disambiguations, fillerPadding = null)
}

/**
 * One glyph change in an [MrzCandidate]: the [recognized] glyph at ([lineIndex], [columnIndex]) was
 * read as [resolved].
 *
 * @since 0.3.0
 */
public data class CharacterDisambiguation(
    /** The zero-based index of the line within [MrzCandidate.mrzLines]. */
    public val lineIndex: Int,
    /** The zero-based column of the glyph within its line. */
    public val columnIndex: Int,
    /** The glyph as the OCR engine recognized it, after folding to upper case. */
    public val recognized: Char,
    /** The glyph in this candidate. */
    public val resolved: Char,
)
