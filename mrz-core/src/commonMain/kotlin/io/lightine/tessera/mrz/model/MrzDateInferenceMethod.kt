package io.lightine.tessera.mrz.model

/**
 * How [MrzDate] chose the century of a date, or that it did not.
 *
 * @since 0.1.0
 */
public enum class MrzDateInferenceMethod {
    /**
     * Birth date. The century is the first of the 2000s and the 1900s that puts the date not after
     * the reference time and at most 130 years before it.
     */
    SLIDING_WINDOW_BIRTH,

    /**
     * Expiry date. The century is the first of the 2000s and the 1900s that puts the year from 10
     * years before to 50 years after the reference year.
     */
    SLIDING_WINDOW_EXPIRY,

    /**
     * No century fit, or the components are not a calendar date. Only the printed components are
     * set.
     */
    RAW_ONLY,
}
