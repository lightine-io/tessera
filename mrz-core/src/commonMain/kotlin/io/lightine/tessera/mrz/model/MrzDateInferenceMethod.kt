package io.lightine.tessera.mrz.model

/**
 * How [MrzDate] arrived at its [`computedDate`][MrzDate.computedDate] (or didn't), so
 * consumers can distinguish "we picked a century via the sliding window" from "we couldn't
 * infer anything; only the raw two-digit components are populated."
 *
 * See [ADR-008](https://lightine.youtrack.cloud/articles/TES-A-38)
 * for the rationale.
 */
public enum class MrzDateInferenceMethod {
    /**
     * A birth date whose century was picked by the sliding window: the candidate year
     * is in the past relative to the reference time and within
     * [MrzDate.MAX_PLAUSIBLE_AGE_YEARS] of it.
     */
    SLIDING_WINDOW_BIRTH,

    /**
     * An expiry date whose century was picked by the sliding window: the candidate year
     * is within a 10-year past / 50-year future window of the reference time.
     */
    SLIDING_WINDOW_EXPIRY,

    /**
     * Neither sliding window applied — the SDK exposes only the raw two-digit components
     * (and [MrzDate.componentsFormCalendarDate]). Returned when the raw components don't
     * parse as numerics, when no candidate century fits the inference window, or when
     * the components don't form a calendar date at all.
     */
    RAW_ONLY,
}
