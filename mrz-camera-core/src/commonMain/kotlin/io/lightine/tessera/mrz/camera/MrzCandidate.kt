package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.parsing.ParseResult

/**
 * One reconstruction of the recognized MRZ, listed by tolerant saved-image reading.
 *
 * Tolerant reading ([SavedImageMrzReader] with `tolerant = true`) tries the alternatives for ambiguous
 * characters (`0`/`O`, `1`/`I`, `2`/`Z`, `5`/`S`, `8`/`B`, `6`/`G`), parses each distinct
 * reconstruction, and lists them without choosing one. Reconstructions that do not parse are
 * dropped. When any reconstruction passes every check digit, only those are listed; otherwise
 * every parseable one is listed. At most 8 are listed. Only the first 6 ambiguous positions, in
 * reading order, are varied; later ones stay as recognized. An absent candidate means the reading
 * was not explored, not that it is ruled out. The type holds document data: do not log it
 * verbatim.
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
)

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
