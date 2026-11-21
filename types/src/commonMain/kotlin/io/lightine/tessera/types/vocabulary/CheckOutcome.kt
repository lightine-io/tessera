package io.lightine.tessera.types.vocabulary

/**
 * The outcome of one check that a reader ran (or did not run) on read data.
 *
 * The outcomes are facts about the check, never a verdict on the document: [Passed] means the
 * check ran and its observation agreed with what the SDK computed from the read data, [Failed]
 * means it ran and they differed, [NotApplicable] means the check does not apply to this input,
 * and [NotRun] means it did not run.
 *
 * Every module that reports checks shares this vocabulary: MRZ validation today, chip reading
 * later.
 *
 * @since 1.0.0
 */
public sealed interface CheckOutcome {
    /**
     * The check ran and its observation agreed with what the SDK computed from the read data.
     *
     * @since 1.0.0
     */
    public data object Passed : CheckOutcome

    /**
     * The check ran and its observation differed from what the SDK computed from the read data.
     *
     * @since 1.0.0
     */
    public data object Failed : CheckOutcome

    /**
     * The check does not apply: the format has no such check, or the check position holds a
     * filler where the standard allows one.
     *
     * @since 1.0.0
     */
    public data object NotApplicable : CheckOutcome

    /**
     * The check did not run, for example because the structure could not be read.
     *
     * @property reason Why the check did not run.
     * @since 1.0.0
     */
    public data class NotRun(
        val reason: String,
    ) : CheckOutcome
}
