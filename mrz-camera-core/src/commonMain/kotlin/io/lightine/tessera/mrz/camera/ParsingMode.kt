package io.lightine.tessera.mrz.camera

/**
 * How forgiving the analyse-frame core is of OCR formatting noise when it extracts a candidate MRZ
 * from recognized text. Consumer-chosen; the default is [STRICT]
 * ([ADR-020](https://lightine.youtrack.cloud/articles/TES-A-49)).
 *
 * Neither mode ever changes a data character — the difference is only which lines qualify as an MRZ
 * candidate. (Both fold case to upper, which recovers the intended glyph since the MRZ alphabet is
 * uppercase-only; that is case normalization, not a data decision.) Tolerant mode — check-digit-guided
 * disambiguation of genuinely ambiguous characters — is deferred to 0.3.0 and is not represented here.
 */
public enum class ParsingMode {
    /**
     * Accept a line as an MRZ candidate only when it already has an exact MRZ line length after
     * trimming surrounding whitespace (and folding to upper case). Internal spacing or a wrong length
     * disqualifies the line. For live camera this pairs with next-frame retry: a noisy frame yields no
     * candidate and is dropped; a clean frame arrives within milliseconds.
     */
    STRICT,

    /**
     * Additionally tolerate benign whitespace noise — strip every space within a line before checking
     * its length — so an MRZ the engine split with stray spaces still qualifies. Forgives formatting
     * only: it never substitutes, inserts, or removes a non-whitespace character (that would be a data
     * decision, which the reader-not-oracle stance forbids).
     */
    LENIENT,
}
