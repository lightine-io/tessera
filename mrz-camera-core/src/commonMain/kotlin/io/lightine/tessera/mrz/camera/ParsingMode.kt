package io.lightine.tessera.mrz.camera

/**
 * How tolerant candidate extraction is of OCR formatting noise.
 *
 * Neither mode changes a data character; both fold case to upper. [MrzFrameAnalyzer] and the live
 * scanners default to [STRICT]. [SavedImageMrzReader] defaults to [LENIENT]. Disambiguating
 * ambiguous characters is the separate `tolerant` option of [SavedImageMrzReader].
 *
 * @since 0.2.0
 */
public enum class ParsingMode {
    /**
     * Accepts only lines of the exact MRZ length after trimming and upper-casing. A space inside a
     * line disqualifies it, so a line that OCR splits with stray spaces is not detected.
     */
    STRICT,

    /**
     * Also removes every space inside a line before checking its length. Changes no other
     * character.
     */
    LENIENT,
}
