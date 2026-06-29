package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.parsing.ParseResult

/**
 * One candidate MRZ reconstruction surfaced by tolerant saved-image reading ([SavedImageMrzReader] with
 * `tolerant = true`).
 *
 * Tolerant reading enumerates how the genuinely ambiguous OCR glyphs in a recognized MRZ could be resolved
 * (a glyph that could be `0` or `O`, `1` or `I`, …), parses each distinct reconstruction, and surfaces them
 * **all** — it never picks one and never drops the ones whose check digits fail. Choosing among readings
 * because one's check digit computes would be the SDK *deciding* what the document says, which the
 * reader-not-oracle stance forbids ([ADR-020](https://lightine.youtrack.cloud/articles/TES-A-49),
 * [ADR-023](https://lightine.youtrack.cloud/articles/TES-A-63)). The consumer ranks, using each candidate's
 * own [parse] verdict — its `metadata.validationFailures` reports the check-digit outcomes.
 *
 * (Reconstructions that do not parse *structurally* are not surfaced — they are not plausible readings at
 * all; reconstructions that parse, including those whose check digits fail, are all surfaced.)
 *
 * **Bounded enumeration (provisional, `0.x`).** To keep the cost bounded, tolerant reading varies only the
 * first several ambiguous positions *in reading order* and leaves any beyond that cap as-recognized in every
 * candidate. For a real document the early positions often fall on the name line, so later positions —
 * including a data line's check-digit-bearing fields — may be left unexplored. The surfaced set is therefore
 * every parseable reconstruction of the *explored* positions, not literally every conceivable reading.
 * Field-aware position selection (prioritising data-field positions) is a tracked future refinement; until
 * then, treat absence of a candidate as "not explored," never as "ruled out."
 *
 * **Contains document PII — do not log verbatim.** [mrzLines] and [parse] hold document data; this type's
 * generated `toString()` surfaces it. Redact before logging (see the `telemetry` module's `Redaction`).
 */
public data class MrzCandidate(
    /** The reconstructed candidate MRZ lines — one disambiguation of the recognized text. PII. */
    public val mrzLines: List<String>,
    /** The parser's verdict for this reconstruction — success / partial success — with its check-digit outcomes. */
    public val parse: ParseResult,
    /**
     * The per-position disambiguations applied to reach this candidate from the recognized text; **empty**
     * for the as-recognized candidate. Each entry records where a glyph was resolved and from/to what, so a
     * consumer can audit exactly what tolerant reading changed (Principle 5 — expose the detail, never hide it).
     */
    public val disambiguations: List<CharacterDisambiguation>,
)

/**
 * A single per-position glyph disambiguation within an [MrzCandidate]: the [recognized] glyph at
 * ([lineIndex], [columnIndex]) was read as [resolved] in this candidate.
 */
public data class CharacterDisambiguation(
    /** Zero-based index of the MRZ line the glyph is on (within the candidate's [MrzCandidate.mrzLines]). */
    public val lineIndex: Int,
    /** Zero-based column of the glyph within its line. */
    public val columnIndex: Int,
    /** The glyph as the OCR engine recognized it (after case-folding). */
    public val recognized: Char,
    /** The glyph in this candidate reconstruction. */
    public val resolved: Char,
)
