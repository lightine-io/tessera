package io.lightine.tessera.types.errors

/**
 * Warning: a TD3 personal number has content, but its check digit is `<`.
 *
 * Reported by the validator. No [MrzCheckDigitMismatch] is reported for that check digit
 * in this case.
 *
 * @property rawPersonalNumber The personal number as printed.
 * @property position Zero-based index of the check digit across the lines joined with no separator.
 * @since 0.1.0
 */
public data class MrzPersonalNumberCheckDigitFiller(
    val rawPersonalNumber: String,
    val position: Int,
) : MrzWarning() {
    /** English text for logs: the filler check digit and the personal number. */
    override val description: String
        get() =
            "TD3 personal number check digit is the filler character '<' but the personal number itself contains " +
                "non-filler content: \"$rawPersonalNumber\". This is a documented real-world deviation from ICAO " +
                "Doc 9303 Part 4 strict conformance — some issuing states leave the personal-number check digit as " +
                "filler even when the personal number is populated. Surfaced as a warning rather than a validation " +
                "failure so consumers can decide whether to treat the deviation as disqualifying."
}
